"use client";

import { useEffect, useMemo, useState } from "react";
import type { User } from "firebase/auth";
import { backendRequest } from "../lib/backend";
import styles from "./StudentPortal.module.css";

type StudentEntry = {
  termId: string; requirement_id: string; subject_id: string; subject_name?: string; section_id: string;
  faculty_id: string; faculty_name?: string; faculty_cabin_no?: string; room_id?: string; room_name?: string;
  room_code?: string; room_building?: string; day: string; period: number; duration: number;
  time_start?: string; time_end?: string; time_slot_label?: string;
};
type StudentTerm = { id: string; name?: string; start_date?: string; end_date?: string; startDate?: string; endDate?: string; status?: string };
type StudentSubject = { id: string; name?: string; code?: string; credits?: number; description?: string; syllabus?: string; syllabusUrl?: string; syllabus_url?: string; units?: unknown[] | Record<string, unknown>; learningOutcomes?: unknown[] | string };
type StudentPortalData = {
  profile: { uid: string; displayName: string; email: string; photoUrl: string; collegeEmail: string; rollNo: string; sectionId: string; sectionName: string; departmentName: string; departmentCode: string; semester?: string; program?: string; batch?: string };
  terms: StudentTerm[];
  entries: StudentEntry[];
  subjects: StudentSubject[];
};
const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

function normalizeDay(value: string) { return value.slice(0, 3).toLowerCase(); }
function localDate(year: number, month: number, day: number) { return `${year}-${String(month + 1).padStart(2, "0")}-${String(day).padStart(2, "0")}`; }
function termBounds(term: StudentTerm) {
  return { start: term.start_date || term.startDate || "", end: term.end_date || term.endDate || "" };
}
function sectionText(value: unknown): string[] {
  if (typeof value === "string") return value.split(/\n+/).map((line) => line.trim()).filter(Boolean);
  const values = Array.isArray(value) ? value : value && typeof value === "object" ? Object.values(value as Record<string, unknown>) : [];
  return values.map((item) => {
    if (typeof item === "string") return item;
    if (!item || typeof item !== "object") return "";
    const record = item as Record<string, unknown>;
    return [record.title, record.name, record.topic, record.description, record.content].filter((part) => typeof part === "string" && part.trim()).join(" · ");
  }).filter(Boolean);
}

export default function StudentPortal({ user }: { user: User }) {
  const [data, setData] = useState<StudentPortalData | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<"calendar" | "syllabus" | "profile">("calendar");
  const [year, setYear] = useState(new Date().getFullYear());
  const [selectedDate, setSelectedDate] = useState(() => localDate(new Date().getFullYear(), new Date().getMonth(), new Date().getDate()));

  useEffect(() => {
    let alive = true;
    backendRequest<StudentPortalData>(user, "/api/v1/student/portal")
      .then((next) => { if (alive) { setData(next); setError(""); } })
      .catch((caught) => { if (alive) setError(caught instanceof Error ? caught.message : "Could not load your student portal."); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [user]);

  const entriesByDay = useMemo(() => {
    const grouped = new Map<string, StudentEntry[]>();
    for (const entry of data?.entries || []) {
      const day = normalizeDay(entry.day);
      grouped.set(day, [...(grouped.get(day) || []), entry]);
    }
    for (const entries of grouped.values()) entries.sort((a, b) => a.period - b.period);
    return grouped;
  }, [data]);

  function entriesOn(date: string) {
    const weekday = normalizeDay(new Intl.DateTimeFormat("en-US", { weekday: "short", timeZone: "UTC" }).format(new Date(`${date}T12:00:00Z`)));
    return (entriesByDay.get(weekday) || []).filter((entry) => {
      const term = data?.terms.find((item) => item.id === entry.termId);
      if (!term) return true;
      const { start, end } = termBounds(term);
      return (!start || date >= start.slice(0, 10)) && (!end || date <= end.slice(0, 10));
    });
  }

  function renderMonth(month: number) {
    const firstOffset = (new Date(year, month, 1).getDay() + 6) % 7;
    const daysInMonth = new Date(year, month + 1, 0).getDate();
    const cells: (number | null)[] = [...Array(firstOffset).fill(null), ...Array.from({ length: daysInMonth }, (_, index) => index + 1)];
    while (cells.length % 7 !== 0) cells.push(null);
    return <article className={styles.monthCard} key={month}>
      <h3>{MONTHS[month]}</h3>
      <div className={styles.monthGrid}>{WEEKDAYS.map((day) => <span className={styles.weekday} key={day}>{day[0]}</span>)}{cells.map((day, index) => {
        if (!day) return <span className={styles.blankDay} key={`blank-${index}`} />;
        const date = localDate(year, month, day);
        const count = entriesOn(date).length;
        const selected = date === selectedDate;
        return <button type="button" key={date} aria-label={`${date}${count ? `, ${count} classes` : ""}`} aria-pressed={selected} onClick={() => setSelectedDate(date)} className={`${styles.day} ${count ? styles.hasClasses : ""} ${selected ? styles.selected : ""}`}>
          {day}{count > 0 && <i aria-hidden="true" />}
        </button>;
      })}</div>
    </article>;
  }

  if (loading) return <section className={styles.panel}><p className={styles.eyebrow}>STUDENT PORTAL</p><h2>Loading your academic workspace…</h2></section>;
  if (error || !data) return <section className={styles.panel}><p className={styles.eyebrow}>STUDENT PORTAL</p><h2>Your student workspace</h2><div className={styles.error}>{error || "Student information is not available."}</div></section>;

  const selectedEntries = entriesOn(selectedDate);
  const dateLabel = new Date(`${selectedDate}T12:00:00`).toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric", year: "numeric" });
  return <section className={styles.panel} id="student-portal">
    <div className={styles.heading}>
      <div><p className={styles.eyebrow}>GLA UNIVERSITY · STUDENT SERVICES</p><h2>My academic workspace</h2><p>Your section timetable, course syllabus, and student profile in one place.</p></div>
      <div className={styles.sectionBadge}>{data.profile.sectionName || data.profile.sectionId}<small>{data.profile.departmentName || data.profile.departmentCode || "Student section"}</small></div>
    </div>
    <div className={styles.tabs} role="tablist" aria-label="Student workspace">
      <button type="button" role="tab" aria-selected={activeTab === "calendar"} onClick={() => setActiveTab("calendar")}>Year calendar</button>
      <button type="button" role="tab" aria-selected={activeTab === "syllabus"} onClick={() => setActiveTab("syllabus")}>Syllabus</button>
      <button type="button" role="tab" aria-selected={activeTab === "profile"} onClick={() => setActiveTab("profile")}>My profile</button>
    </div>
    {activeTab === "calendar" && <div className={styles.calendarPane}>
      <div className={styles.calendarHeader}><div><h3>Academic year calendar</h3><p>Classes repeat on their scheduled weekdays during the published term dates.</p></div><div className={styles.yearPicker}><button type="button" aria-label="Previous year" onClick={() => setYear((value) => value - 1)}>‹</button><strong>{year}</strong><button type="button" aria-label="Next year" onClick={() => setYear((value) => value + 1)}>›</button></div></div>
      <div className={styles.yearGrid}>{MONTHS.map((_, month) => renderMonth(month))}</div>
      <div className={styles.selectedDay}><div className={styles.selectedDayHeading}><div><span className={styles.eyebrow}>SELECTED DAY</span><h3>{dateLabel}</h3></div><span>{selectedEntries.length} classes</span></div>
        {selectedEntries.length ? <div className={styles.classList}>{selectedEntries.map((entry, index) => <article className={styles.classCard} key={`${entry.termId}-${entry.requirement_id}-${entry.period}-${index}`}><time>{entry.time_start || `Period ${entry.period}`}<small>{entry.time_end || ""}</small></time><div><strong>{entry.subject_name || entry.subject_id}</strong><p>{entry.faculty_name || entry.faculty_id}{entry.faculty_cabin_no ? ` · Faculty cabin ${entry.faculty_cabin_no}` : ""}</p><small>{entry.room_building ? `${entry.room_building} · ` : ""}{entry.room_code || entry.room_name || "Room not set"} · {entry.termId}</small></div></article>)}</div> : <div className={styles.empty}>No classes are scheduled for this date.</div>}
      </div>
    </div>}
    {activeTab === "syllabus" && <div className={styles.syllabusPane}><div className={styles.calendarHeader}><div><h3>My subjects and syllabus</h3><p>Subjects mapped to your section’s published academic plan.</p></div><span>{data.subjects.length} subjects</span></div>
      {data.subjects.length ? <div className={styles.subjectList}>{data.subjects.map((subject) => {
        const units = sectionText(subject.units);
        const outcomes = sectionText(subject.learningOutcomes);
        return <article className={styles.subjectCard} key={subject.id}><div className={styles.subjectHeading}><div><p className={styles.subjectCode}>{subject.code || subject.id}</p><h4>{subject.name || subject.id}</h4></div>{subject.credits !== undefined && <span>{subject.credits} credits</span>}</div>
          {subject.description && <p className={styles.subjectDescription}>{subject.description}</p>}
          {subject.syllabus && <div className={styles.syllabusText}>{subject.syllabus}</div>}
          {units.length > 0 && <div className={styles.courseUnits}><strong>Units / topics</strong><ol>{units.map((unit, index) => <li key={`${subject.id}-unit-${index}`}>{unit}</li>)}</ol></div>}
          {outcomes.length > 0 && <div className={styles.courseUnits}><strong>Learning outcomes</strong><ul>{outcomes.map((outcome, index) => <li key={`${subject.id}-outcome-${index}`}>{outcome}</li>)}</ul></div>}
          {(subject.syllabusUrl || subject.syllabus_url) && /^https?:\/\//i.test(subject.syllabusUrl || subject.syllabus_url || "") && <a className={styles.syllabusLink} href={subject.syllabusUrl || subject.syllabus_url} target="_blank" rel="noreferrer">Open syllabus document ↗</a>}
          {!subject.description && !subject.syllabus && !units.length && !outcomes.length && !subject.syllabusUrl && !subject.syllabus_url && <p className={styles.empty}>Syllabus content has not been added for this subject yet.</p>}
        </article>;
      })}</div> : <div className={styles.empty}>No subjects are currently mapped to your section. Ask your department administrator to update the academic data.</div>}
    </div>}
    {activeTab === "profile" && <div className={styles.profilePane}><div className={styles.profileCard}><div className={styles.profileHeader}>{data.profile.photoUrl ? <img src={data.profile.photoUrl} alt="" /> : <div className={styles.profileInitial}>{data.profile.displayName?.slice(0, 1).toUpperCase() || "S"}</div>}<div><h3>{data.profile.displayName}</h3><p>Student · {data.profile.sectionName || data.profile.sectionId}</p></div></div><dl className={styles.profileGrid}><div><dt>College email / student ID</dt><dd>{data.profile.collegeEmail || "Not assigned"}</dd></div><div><dt>Google sign-in email</dt><dd>{data.profile.email || user.email || "—"}</dd></div><div><dt>Roll number</dt><dd>{data.profile.rollNo || "Not assigned"}</dd></div><div><dt>Section</dt><dd>{data.profile.sectionName || data.profile.sectionId}</dd></div><div><dt>Department</dt><dd>{data.profile.departmentName || data.profile.departmentCode || "—"}</dd></div><div><dt>Program / semester</dt><dd>{[data.profile.program, data.profile.semester && `Semester ${data.profile.semester}`, data.profile.batch].filter(Boolean).join(" · ") || "—"}</dd></div></dl><p className={styles.profileNote}>Your Google photo and sign-in email come from your account. Contact your class advisor to correct college enrollment details.</p></div></div>}
  </section>;
}
