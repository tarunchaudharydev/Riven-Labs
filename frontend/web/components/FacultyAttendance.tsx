"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { User } from "firebase/auth";
import { backendRequest } from "../lib/backend";
import {
  cacheAttendanceSessions,
  queueAttendanceDraft,
  readAttendanceQueue,
  readCachedAttendanceSessions,
  removeAttendanceDraft,
  type OfflineAttendanceDraft,
  type OfflineAttendanceSession,
} from "../lib/offlineAttendance";
import styles from "./FacultyAttendance.module.css";

type AttendanceResponse = { date: string; sessions: (OfflineAttendanceSession & { attendanceSavedAt?: string })[] };
type Status = "PRESENT" | "ABSENT" | "LATE";

function todayInCollege() {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
}

function timeMinutes(value?: string) {
  if (!value) return null;
  const text = value.trim();
  const ampm = text.match(/^(\d{1,2}):(\d{2})(?:\s*)(AM|PM)$/i);
  if (ampm) {
    let hour = Number(ampm[1]) % 12;
    if (ampm[3].toUpperCase() === "PM") hour += 12;
    return hour * 60 + Number(ampm[2]);
  }
  const plain = text.match(/^(\d{1,2}):(\d{2})/);
  return plain ? Number(plain[1]) * 60 + Number(plain[2]) : null;
}

function currentCollegeMinutes() {
  const parts = new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Kolkata", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(new Date());
  return Number(parts.find((part) => part.type === "hour")?.value || 0) * 60 + Number(parts.find((part) => part.type === "minute")?.value || 0);
}

function canTakeAttendance(session: OfflineAttendanceSession, now: number) {
  const start = timeMinutes(session.time_start);
  const end = timeMinutes(session.time_end);
  return session.date === todayInCollege() && start !== null && end !== null && now >= start && now <= end;
}

export default function FacultyAttendance({ user }: { user: User }) {
  const [sessions, setSessions] = useState<OfflineAttendanceSession[]>([]);
  const [marks, setMarks] = useState<Record<string, Record<string, Status>>>({});
  const [online, setOnline] = useState(true);
  const [pendingCount, setPendingCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [clock, setClock] = useState(0);
  const syncLock = useRef(false);

  const applySessions = useCallback((next: OfflineAttendanceSession[]) => {
    setSessions(next);
    setMarks((current) => {
      const merged: Record<string, Record<string, Status>> = {};
      for (const session of next) {
        merged[session.sessionKey] = Object.fromEntries(session.students.map((student) => [
          student.uid,
          current[session.sessionKey]?.[student.uid] || (student.status as Status) || "PRESENT",
        ]));
      }
      return merged;
    });
  }, []);

  const loadSessions = useCallback(async () => {
    const today = todayInCollege();
    if (typeof navigator !== "undefined" && !navigator.onLine) {
      setOnline(false);
      const cached = await readCachedAttendanceSessions(user.uid, today).catch(() => []);
      applySessions(cached);
      setLoading(false);
      return;
    }
    try {
      const response = await backendRequest<AttendanceResponse>(user, "/api/v1/attendance/sessions");
      applySessions(response.sessions || []);
      await cacheAttendanceSessions(user.uid, response.date, response.sessions || []).catch(() => undefined);
      setOnline(true);
      setError("");
    } catch (caught) {
      const cached = await readCachedAttendanceSessions(user.uid, today).catch(() => []);
      if (cached.length) {
        applySessions(cached);
        setOnline(false);
        setNotice("Using the last roster saved on this device. Attendance will sync when connection returns.");
      } else {
        setError(caught instanceof Error ? caught.message : "Could not load today’s faculty sessions.");
      }
    } finally { setLoading(false); }
  }, [applySessions, user]);

  const syncQueue = useCallback(async () => {
    if (syncLock.current || typeof navigator === "undefined" || !navigator.onLine) return;
    syncLock.current = true;
    setSyncing(true);
    try {
      const queue = await readAttendanceQueue(user.uid);
      setPendingCount(queue.length);
      for (const draft of queue) {
        const session = draft.session;
        await backendRequest(user, "/api/v1/attendance/sync", {
          method: "POST",
          body: JSON.stringify({
            termId: session.termId,
            requirementId: session.requirement_id,
            sectionId: session.section_id,
            sessionKey: session.sessionKey,
            date: session.date,
            day: session.day.slice(0, 3).toUpperCase(),
            period: Number(session.period),
            capturedAt: draft.capturedAt,
            students: draft.students,
          }),
        });
        await removeAttendanceDraft(draft.id);
      }
      const remaining = await readAttendanceQueue(user.uid);
      setPendingCount(remaining.length);
      if (queue.length > 0) setNotice(`${queue.length} attendance ${queue.length === 1 ? "record" : "records"} synced to the timetable server.`);
      await loadSessions();
    } catch (caught) {
      const pending = await readAttendanceQueue(user.uid).catch(() => []);
      setPendingCount(pending.length);
      setError(caught instanceof Error ? caught.message : "Attendance is saved on this device and will retry later.");
    } finally {
      syncLock.current = false;
      setSyncing(false);
    }
  }, [loadSessions, user]);

  useEffect(() => {
    let alive = true;
    const updateOnline = () => {
      setOnline(navigator.onLine);
      if (navigator.onLine) void syncQueue().then(() => loadSessions());
    };
    void (async () => {
      const cached = await readCachedAttendanceSessions(user.uid, todayInCollege()).catch(() => []);
      if (!alive) return;
      if (cached.length) applySessions(cached);
      const pending = await readAttendanceQueue(user.uid).catch(() => []);
      if (!alive) return;
      setPendingCount(pending.length);
      setOnline(navigator.onLine);
      if (navigator.onLine) {
        await loadSessions();
        await syncQueue();
      } else {
        setLoading(false);
        if (!cached.length) setNotice("Open this page while connected once to save today’s class list for offline attendance.");
      }
    })();
    const timer = window.setInterval(() => setClock((value) => value + 1), 30_000);
    window.addEventListener("online", updateOnline);
    window.addEventListener("offline", updateOnline);
    return () => {
      alive = false;
      window.clearInterval(timer);
      window.removeEventListener("online", updateOnline);
      window.removeEventListener("offline", updateOnline);
    };
  }, [applySessions, loadSessions, syncQueue, user.uid]);

  const now = currentCollegeMinutes() + clock * 0;
  const orderedSessions = useMemo(() => [...sessions].sort((a, b) => (timeMinutes(a.time_start) ?? 0) - (timeMinutes(b.time_start) ?? 0)), [sessions]);

  async function saveSession(session: OfflineAttendanceSession) {
    const marksForSession = marks[session.sessionKey] || {};
    const students = session.students.map((student) => ({ uid: student.uid, status: marksForSession[student.uid] || (student.status as Status) || "PRESENT" }));
    const draft: OfflineAttendanceDraft = {
      id: `${user.uid}:${session.sessionKey}`,
      ownerUid: user.uid,
      session,
      capturedAt: new Date().toISOString(),
      students,
    };
    setError("");
    try {
      await queueAttendanceDraft(draft);
      setPendingCount((count) => Math.max(count, 1));
      setNotice(online ? "Attendance saved on this device. Syncing it to Firebase…" : "Attendance saved offline on this device. It will sync when the connection returns.");
      if (online) await syncQueue();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not save attendance on this device.");
    }
  }

  return <section className={styles.panel} id="attendance">
    <div className={styles.heading}>
      <div><p className={styles.eyebrow}>FACULTY WORKSPACE</p><h2>Lecture attendance</h2><p>Only your published classes for today appear here. Save during the lecture; offline entries sync when internet returns.</p></div>
      <div className={`${styles.connection} ${online ? styles.connected : styles.disconnected}`}><i />{online ? "Online" : "Offline"}</div>
    </div>
    {pendingCount > 0 && <div className={styles.pending}>{pendingCount} attendance {pendingCount === 1 ? "record is" : "records are"} waiting to sync <button disabled={!online || syncing} onClick={() => void syncQueue()}>{syncing ? "Syncing…" : "Sync now"}</button></div>}
    {notice && <div className={styles.notice} role="status">{notice}</div>}
    {error && <div className={styles.error} role="alert">{error}</div>}
    {loading ? <div className={styles.empty}>Loading today’s assigned classes…</div> : orderedSessions.length === 0 ? <div className={styles.empty}>No published classes are assigned to you today.</div> : <div className={styles.sessions}>
      {orderedSessions.map((session) => {
        const open = canTakeAttendance(session, now);
        const presentCount = session.students.filter((student) => (marks[session.sessionKey]?.[student.uid] || student.status || "PRESENT") === "PRESENT").length;
        return <article className={styles.session} key={session.sessionKey}>
          <div className={styles.sessionHeader}>
            <div><span className={styles.sessionTime}>{session.time_start || "Time unavailable"} – {session.time_end || ""}</span><h3>{session.subject_name || session.subject_id}</h3><p>{session.section_name || session.section_id} · {session.room_building ? `${session.room_building} · ` : ""}{session.room_code || session.room_name || "Room not set"}</p></div>
            <span className={`${styles.status} ${open ? styles.active : styles.inactive}`}>{open ? "Attendance open" : "Outside lecture time"}</span>
          </div>
          <div className={styles.rosterTop}><strong>{session.students.length} students</strong><span>{presentCount} marked present</span></div>
          {session.students.length ? <div className={styles.tableWrap}><table><thead><tr><th>Student</th><th>College email</th><th>Roll no.</th><th>Attendance</th></tr></thead><tbody>{session.students.map((student) => <tr key={student.uid}><td>{student.name}</td><td>{student.collegeEmail || "—"}</td><td>{student.rollNo || "—"}</td><td><select aria-label={`Attendance for ${student.name}`} value={marks[session.sessionKey]?.[student.uid] || (student.status as Status) || "PRESENT"} onChange={(event) => setMarks((current) => ({ ...current, [session.sessionKey]: { ...current[session.sessionKey], [student.uid]: event.target.value as Status } }))}><option value="PRESENT">Present</option><option value="ABSENT">Absent</option><option value="LATE">Late</option></select></td></tr>)}</tbody></table></div> : <p className={styles.noRoster}>No students have been assigned to this section yet.</p>}
          <div className={styles.footer}><span>{session.attendanceSavedAt ? `Last synced ${new Date(session.attendanceSavedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}` : "Not submitted yet"}</span><button disabled={!open || session.students.length === 0 || syncing} onClick={() => void saveSession(session)}>{online ? "Save attendance" : "Save offline"}</button></div>
        </article>;
      })}
    </div>}
  </section>;
}
