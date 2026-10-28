"use client";

import { useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import { getIdTokenResult, GoogleAuthProvider, onAuthStateChanged, signInWithEmailAndPassword, signInWithPopup, signOut } from "firebase/auth";
import { onValue, ref } from "firebase/database";
import type { User } from "firebase/auth";
import { backendRequest } from "../lib/backend";
import { getFirebaseAuth, getRealtimeDatabase } from "../lib/firebase";
import AcademicWorkspace from "../components/AcademicWorkspace";
import UserAdmin from "../components/UserAdmin";
import RoomAvailabilityFinder from "../components/RoomAvailabilityFinder";
import accessAdminStyles from "../components/access-admin.module.css";

type DashboardData = {
  institutionName: string;
  role: string;
  counts: Record<string, number>;
  terms: Record<string, { name?: string; status?: string }>;
  publishedTimetables: { termId: string; draftId: string; publishedAt: string; entries: TimetableEntry[] | Record<string, TimetableEntry> }[];
  drafts?: TimetableDraft[];
};
type TimetableEntry = { requirement_id: string; subject_id: string; subject_name: string; section_id: string; section_name?: string; section_code?: string; faculty_id: string; faculty_name?: string; faculty_cabin_no?: string; room_id: string; room_name?: string; room_code?: string; room_type?: string; department_id?: string; department_name?: string; department_code?: string; department_block_no?: string; day: string; period: number; duration: number; slot_ids: string[]; time_slot_label?: string; time_start?: string; time_end?: string; substitute?: boolean };
type TimetableDraft = { id: string; termId: string; status: string; solverStatus: string; entries: TimetableEntry[] | Record<string, TimetableEntry>; diagnostics?: { code: string; message: string }[]; validation?: { valid: boolean } };
type Interpretation = { original_text: string; changes: { subject_id: string; section_id: string; sessions_per_week?: number; max_sessions_per_week?: number; preferred_faculty_ids: string[]; excluded_slot_ids: string[]; preferred_slot_ids: string[]; periods_per_session?: number; priority: string }[]; faculty_unavailability?: { faculty_id: string; excluded_slot_ids: string[] }[]; clarification_questions: string[]; unsupported_requirements: string[]; requires_review: boolean };
type ParseResponse = { id: string; status: string; interpretation: Interpretation };
type Job = { jobId?: string; id?: string; status: string; draftId?: string; message?: string; diagnostics?: { code: string; message: string }[] };
type SubstituteResult = { candidates: { id: string; name: string; score: number; reasons: string[]; department_name?: string; department_code?: string; department_block_no?: string }[]; excluded_candidate_ids: string[]; weights: Record<string, number> };
type PolicyResponse = { answer: string; citations: { document_id: string; title: string; page?: number; section?: string }[]; supported_by_documents: boolean };

const iconPaths: Record<string, string> = {
  grid: "M3 3h7v7H3z M14 3h7v7h-7z M14 14h7v7h-7z M3 14h7v7H3z",
  calendar: "M8 2v4m8-4v4M3 10h18M5 4h14a2 2 0 0 1 2 2v13H3V6a2 2 0 0 1 2-2z",
  users: "M16 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2m16 0v-2a4 4 0 0 0-3-3.87M14 3.13a4 4 0 0 1 0 7.75M10 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0z",
  spark: "m12 3 1.5 5.5L19 10l-5.5 1.5L12 17l-1.5-5.5L5 10l5.5-1.5L12 3zm7 12 .8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15z",
  book: "M4 4.5A2.5 2.5 0 0 1 6.5 2H20v17H6.5A2.5 2.5 0 0 0 4 21.5v-17zM4 18.5A2.5 2.5 0 0 1 6.5 16H20",
  arrow: "M5 12h14m-7-7 7 7-7 7",
  clock: "M12 8v4l2.5 2.5M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z",
  logout: "M10 17l5-5-5-5m5 5H3m9-9h7a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-7",
  check: "m5 12 4 4L19 6",
  chevron: "m9 18 6-6-6-6",
};

function Icon({ name, size = 18 }: { name: string; size?: number }) {
  return <svg aria-hidden="true" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d={iconPaths[name] || iconPaths.grid} /></svg>;
}
function list<T>(value: T[] | Record<string, T> | undefined): T[] { return !value ? [] : Array.isArray(value) ? value : Object.values(value); }
function initials(user: User) { return (user.displayName || user.email || "User").split(/[\s@._-]/).filter(Boolean).slice(0, 2).map((part) => part[0]).join("").toUpperCase(); }
function dayIndex(day: string) { const index = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"].indexOf(day.slice(0, 3).toUpperCase()); return index < 0 ? 8 : index; }

export default function Home() {
  const [authReady, setAuthReady] = useState(false);
  const [user, setUser] = useState<User | null>(null);
  const [accessChecked, setAccessChecked] = useState(false);
  const [accessPending, setAccessPending] = useState(false);
  const [authError, setAuthError] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [signingIn, setSigningIn] = useState(false);
  const [data, setData] = useState<DashboardData | null>(null);
  const [loadError, setLoadError] = useState("");
  const [liveSyncError, setLiveSyncError] = useState("");
  const [liveSyncReady, setLiveSyncReady] = useState(false);
  const [selectedTerm, setSelectedTerm] = useState("");
  const [requirementText, setRequirementText] = useState("");
  const [parsing, setParsing] = useState(false);
  const [interpretationId, setInterpretationId] = useState("");
  const [interpretation, setInterpretation] = useState<Interpretation | null>(null);
  const [reviewed, setReviewed] = useState(false);
  const [job, setJob] = useState<Job | null>(null);
  const [draft, setDraft] = useState<TimetableDraft | null>(null);
  const [actionError, setActionError] = useState("");
  const [busy, setBusy] = useState(false);
  const [policyQuestion, setPolicyQuestion] = useState("");
  const [policyAnswer, setPolicyAnswer] = useState<PolicyResponse | null>(null);
  const [substitutions, setSubstitutions] = useState<Record<string, SubstituteResult>>({});

  useEffect(() => {
    try {
      return onAuthStateChanged(getFirebaseAuth(), (current) => { setUser(current); setData(null); setAccessPending(false); setAccessChecked(!current); setAuthReady(true); setAuthError(""); }, (error) => { setAuthError(error.message); setAuthReady(true); });
    } catch (error) {
      const message = error instanceof Error ? error.message : "Firebase could not be initialized.";
      queueMicrotask(() => { setAuthError(message); setAuthReady(true); });
    }
  }, []);

  useEffect(() => {
    if (!user) return;
    let cancelled = false;
    let pending = false;
    const checkAccess = async (refreshToken = false) => {
      try {
        if (refreshToken) await user.getIdToken(true);
        const result = await backendRequest<DashboardData>(user, "/api/v1/dashboard");
        if (cancelled) return;
        pending = false;
        setData(result); setLoadError(""); setAccessPending(false); setAccessChecked(true);
        setSelectedTerm((current) => current || Object.keys(result.terms || {})[0] || "");
      } catch (error: unknown) {
        if (cancelled) return;
        const message = error instanceof Error ? error.message : "The timetable backend could not be reached.";
        const missingRole = /not provisioned for timetable access|missing its section or faculty assignment/i.test(message);
        if (missingRole || pending) {
          pending = true;
          setData(null); setLoadError(""); setAccessPending(true); setAccessChecked(true);
        } else {
          setLoadError(message); setAccessChecked(true);
        }
      }
    };
    void checkAccess();
    const timer = window.setInterval(() => { if (pending) void checkAccess(true); }, 25_000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [user]);

  useEffect(() => {
    if (!user || !data?.terms) return;
    let cancelled = false;
    const cleanups: (() => void)[] = [];
    const termIds = Object.keys(data.terms);
    getIdTokenResult(user).then(({ claims }) => {
      if (cancelled) return;
      setLiveSyncReady(false);
      const institutionId = typeof claims.institutionId === "string" ? claims.institutionId : "";
      const departmentId = typeof claims.departmentId === "string" ? claims.departmentId : "";
      const role = typeof claims.role === "string" ? claims.role : "";
      if (!institutionId) return;
      const db = getRealtimeDatabase();
      const audiencePath = role === "STUDENT_VIEWER" && typeof claims.sectionId === "string"
        ? `sections/${claims.sectionId}`
        : role === "FACULTY" && typeof claims.facultyId === "string"
          ? `faculty/${claims.facultyId}`
          : role === "DEPARTMENT_ADMIN" && departmentId
            ? `departments/${departmentId}`
            : "";
      const canReadInstitution = ["SUPER_ADMIN", "TIMETABLE_INCHARGE", "ACADEMIC_APPROVER"].includes(role);
      if (!audiencePath && !canReadInstitution) return;
      setLiveSyncError("");
      for (const termId of termIds) {
        const path = audiencePath
          ? `institutions/${institutionId}/liveTimetables/${termId}/${audiencePath}`
          : `institutions/${institutionId}/liveTimetableSnapshots/${termId}`;
        cleanups.push(onValue(ref(db, path), (snapshot) => {
          const live = snapshot.val() as { draftId?: string; publishedAt?: string; entries?: TimetableEntry[] | Record<string, TimetableEntry> } | null;
          if (!cancelled) setLiveSyncReady(true);
          if (cancelled || !live) return;
          setData((current) => {
            if (!current) return current;
            const currentSchedule = list(current.publishedTimetables).find((schedule) => schedule.termId === termId);
            const directory = new Map<string, TimetableEntry>();
            for (const entry of list(currentSchedule?.entries)) directory.set(`${entry.requirement_id}-${entry.day}-${entry.period}`, entry);
            const liveEntries = list(live.entries).map((entry) => {
              const previous = directory.get(`${entry.requirement_id}-${entry.day}-${entry.period}`);
              if (!previous) return entry;
              const sameFaculty = previous.faculty_id === entry.faculty_id;
              const sameRoom = previous.room_id === entry.room_id;
              const sameSection = previous.section_id === entry.section_id;
              const sameDepartment = previous.department_id === entry.department_id;
              const sameSlots = previous.slot_ids?.join("|") === entry.slot_ids?.join("|");
              return {
                ...entry,
                ...(sameFaculty ? { faculty_name: previous.faculty_name || entry.faculty_name, faculty_cabin_no: previous.faculty_cabin_no || entry.faculty_cabin_no } : {}),
                ...(sameRoom ? { room_name: previous.room_name || entry.room_name, room_code: previous.room_code || entry.room_code, room_type: previous.room_type || entry.room_type } : {}),
                ...(sameSection ? { section_name: previous.section_name || entry.section_name, section_code: previous.section_code || entry.section_code } : {}),
                ...(sameDepartment ? { department_name: previous.department_name || entry.department_name, department_code: previous.department_code || entry.department_code, department_block_no: previous.department_block_no || entry.department_block_no } : {}),
                ...(sameSlots ? { time_slot_label: previous.time_slot_label || entry.time_slot_label, time_start: previous.time_start || entry.time_start, time_end: previous.time_end || entry.time_end } : {}),
              };
            });
            const otherSchedules = list(current.publishedTimetables).filter((schedule) => schedule.termId !== termId);
            return { ...current, publishedTimetables: [...otherSchedules, {
              termId, draftId: live.draftId || "", publishedAt: live.publishedAt || "", entries: liveEntries,
            }] };
          });
        }, (error) => {
          if (!cancelled) { setLiveSyncReady(false); setLiveSyncError(error.message || "Realtime Database denied the live schedule listener."); }
        }));
      }
    }).catch((error: unknown) => {
      if (!cancelled) setLiveSyncError(error instanceof Error ? error.message : "Realtime timetable updates could not connect.");
    });
    return () => { cancelled = true; cleanups.forEach((cleanup) => cleanup()); };
  }, [user, data?.terms]);

  useEffect(() => {
    if (!user || !job?.jobId || !["QUEUED", "RUNNING"].includes(job.status)) return;
    const timer = window.setTimeout(async () => {
      try {
        const next = await backendRequest<Job>(user, `/api/v1/timetable-jobs/${job.jobId}`);
        setJob({ ...next, jobId: job.jobId });
        if (next.status === "COMPLETED" && next.draftId) {
          const [nextDraft, dashboard] = await Promise.all([
            backendRequest<TimetableDraft>(user, `/api/v1/timetables/drafts/${next.draftId}`),
            backendRequest<DashboardData>(user, "/api/v1/dashboard"),
          ]);
          setDraft(nextDraft);
          setData(dashboard);
        }
      } catch (error) { setActionError(error instanceof Error ? error.message : "Could not check generation status."); }
    }, 1800);
    return () => window.clearTimeout(timer);
  }, [user, job]);

  const publishedSchedules = useMemo(() => {
    if (!data?.publishedTimetables) return [];
    return [...list(data.publishedTimetables)].sort((a, b) => String(b.publishedAt).localeCompare(String(a.publishedAt)));
  }, [data]);
  const activeSchedule = publishedSchedules[0];
  const activeEntries = [...list(activeSchedule?.entries)].sort((a, b) => dayIndex(a.day) - dayIndex(b.day) || a.period - b.period);
  const terms = data?.terms ? Object.entries(data.terms) : [];

  async function submitLogin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setSigningIn(true); setAuthError("");
    try { await signInWithEmailAndPassword(getFirebaseAuth(), email.trim(), password); setPassword(""); }
    catch (error) { setAuthError(error instanceof Error ? error.message : "Sign-in failed. Check your email and password."); }
    finally { setSigningIn(false); }
  }
  async function submitGoogle() {
    setSigningIn(true); setAuthError("");
    try {
      const provider = new GoogleAuthProvider();
      provider.setCustomParameters({ prompt: "select_account" });
      await signInWithPopup(getFirebaseAuth(), provider);
    } catch (error) { setAuthError(error instanceof Error ? error.message : "Google sign-in failed. Please try again."); }
    finally { setSigningIn(false); }
  }
  async function parseRequirements(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!user || !selectedTerm || !requirementText.trim()) return;
    setParsing(true); setActionError(""); setInterpretation(null); setInterpretationId(""); setReviewed(false); setJob(null); setDraft(null);
    try {
      const response = await backendRequest<ParseResponse>(user, "/api/v1/ai/parse-requirements", { method: "POST", body: JSON.stringify({ termId: selectedTerm, text: requirementText.trim() }) });
      setInterpretationId(response.id); setInterpretation(response.interpretation);
    } catch (error) { setActionError(error instanceof Error ? error.message : "Could not interpret the scheduling request."); }
    finally { setParsing(false); }
  }
  async function generateDraft() {
    if (!user || !interpretationId || !reviewed || !selectedTerm) return;
    setBusy(true); setActionError(""); setJob(null); setDraft(null);
    try {
      const response = await backendRequest<Job>(user, "/api/v1/timetables/generate", { method: "POST", body: JSON.stringify({ termId: selectedTerm, interpretationId }) });
      setJob({ ...response, jobId: response.jobId });
    } catch (error) { setActionError(error instanceof Error ? error.message : "Could not start timetable generation."); }
    finally { setBusy(false); }
  }
  async function queryPolicy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!user || !policyQuestion.trim()) return;
    setBusy(true); setActionError(""); setPolicyAnswer(null);
    try { setPolicyAnswer(await backendRequest<PolicyResponse>(user, "/api/v1/ai/policies/query", { method: "POST", body: JSON.stringify({ question: policyQuestion.trim() }) })); }
    catch (error) { setActionError(error instanceof Error ? error.message : "Could not search policy documents."); }
    finally { setBusy(false); }
  }
  async function reportAbsence(entry: TimetableEntry, reason: string) {
    if (!user || !activeSchedule) return false;
    setBusy(true); setActionError("");
    const request = { termId: activeSchedule.termId, requirementId: entry.requirement_id, day: entry.day, period: entry.period };
    try {
      await backendRequest(user, "/api/v1/absences", { method: "POST", body: JSON.stringify({ ...request, reason }) });
      const result = await backendRequest<SubstituteResult>(user, "/api/v1/substitutions/recommend", { method: "POST", body: JSON.stringify(request) });
      setSubstitutions((current) => ({ ...current, [`${entry.requirement_id}-${entry.day}-${entry.period}`]: result }));
      return true;
    } catch (error) { setActionError(error instanceof Error ? error.message : "Could not report this absence or find cover."); return false; }
    finally { setBusy(false); }
  }
  async function assignSubstitute(entry: TimetableEntry, facultyId: string) {
    if (!user || !activeSchedule) return;
    setBusy(true); setActionError("");
    try {
      await backendRequest(user, "/api/v1/substitutions/assign", { method: "POST", body: JSON.stringify({ termId: activeSchedule.termId, requirementId: entry.requirement_id, day: entry.day, period: entry.period, facultyId }) });
      const dashboard = await backendRequest<DashboardData>(user, "/api/v1/dashboard");
      setData(dashboard);
      setSubstitutions((current) => { const next = { ...current }; delete next[`${entry.requirement_id}-${entry.day}-${entry.period}`]; return next; });
    } catch (error) { setActionError(error instanceof Error ? error.message : "Could not assign this substitute."); }
    finally { setBusy(false); }
  }
  async function transitionDraft(action: "approve" | "publish") {
    if (!user || !draft) return;
    setBusy(true); setActionError("");
    try {
      const result = await backendRequest<{ status: string }>(user, `/api/v1/timetables/${draft.id}/${action}`, { method: "POST" });
      const dashboard = await backendRequest<DashboardData>(user, "/api/v1/dashboard");
      setDraft({ ...draft, status: result.status });
      setData(dashboard);
    } catch (error) { setActionError(error instanceof Error ? error.message : `Could not ${action} this timetable.`); }
    finally { setBusy(false); }
  }
  async function openDraft(draftId: string) {
    if (!user) return;
    setBusy(true); setActionError("");
    try { setDraft(await backendRequest<TimetableDraft>(user, `/api/v1/timetables/drafts/${draftId}`)); }
    catch (error) { setActionError(error instanceof Error ? error.message : "Could not open the selected draft."); }
    finally { setBusy(false); }
  }

  if (!authReady) return <LoadingScreen />;
  if (!user) return <LoginScreen email={email} password={password} setEmail={setEmail} setPassword={setPassword} onSubmit={submitLogin} onGoogleSignIn={submitGoogle} busy={signingIn} error={authError} />;
  if (!accessChecked) return <LoadingScreen />;
  if (accessPending) return <AccessPendingScreen email={user.email || ""} onSignOut={() => signOut(getFirebaseAuth())} />;
  const displayName = user.displayName || user.email?.split("@")[0] || "Administrator";
  const roleName = data?.role?.replaceAll("_", " ").toLowerCase() || "checking access";
  const currentJob = job?.jobId ? job : null;
  const canSchedule = ["SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE"].includes(data?.role || "");
  const canReview = ["SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE", "ACADEMIC_APPROVER"].includes(data?.role || "");
  const canManageCoverage = ["SUPER_ADMIN", "DEPARTMENT_ADMIN", "TIMETABLE_INCHARGE", "ACADEMIC_APPROVER"].includes(data?.role || "");
  const canManageUsers = data?.role === "SUPER_ADMIN";

  return <div className="app-shell">
    <aside className="sidebar">
      <a className="brand" href="#overview" aria-label="Riven Labs home"><span className="brand-mark"><span /></span><span><strong>riven</strong><small>ACADEMIC SYSTEMS</small></span></a>
      <div className="side-label">WORKSPACE</div>
      <nav className="side-nav" aria-label="Main navigation"><a className="nav-link active" href="#overview"><Icon name="grid" />Overview</a><a className="nav-link" href="#timetable"><Icon name="calendar" />Timetable</a><a className="nav-link" href="#academic-data"><Icon name="users" />Academic data</a><a className="nav-link" href="#assistant"><Icon name="spark" />AI assistant<span className="nav-pill">NEW</span></a><a className="nav-link" href="#policies"><Icon name="book" />Policy library</a>{canManageUsers && <a className="nav-link" href="#user-admin"><Icon name="users" />Users &amp; roles</a>}</nav>
      <div className="sidebar-bottom"><div className="sidebar-help"><div className="help-icon"><Icon name="spark" size={16} /></div><strong>Need a hand?</strong><p>Ask the policy assistant about your institution&apos;s scheduling rules.</p><a href="#policies">Open assistant <Icon name="arrow" size={14} /></a></div><button className="profile-row" onClick={() => signOut(getFirebaseAuth())} aria-label="Sign out"><span className="avatar">{initials(user)}</span><span className="profile-copy"><strong>{displayName}</strong><small>{roleName}</small></span><Icon name="logout" size={16} /></button></div>
    </aside>

    <main className="main-content" id="overview">
      <header className="topbar"><div className="breadcrumbs">Workspace <Icon name="chevron" size={14} /> <strong>Overview</strong></div><div className="top-actions"><span className={`secure-status ${liveSyncError ? "secure-warning" : ""}`} title={liveSyncError || "Published timetables sync from Firebase Realtime Database."}><i /> {liveSyncError ? "Live sync needs setup" : liveSyncReady ? "Realtime connected" : "Connecting realtime"}</span><button className="top-avatar" onClick={() => signOut(getFirebaseAuth())} aria-label={`Sign out, ${displayName}`} title="Sign out">{initials(user)}</button></div></header>
      <div className="page-wrap">
        <section className="welcome-row"><div><p className="eyebrow">ACADEMIC OPERATIONS</p><h1>Good day, {displayName.split(" ")[0]}<span className="greeting-dot">.</span></h1><p className="welcome-sub">Here&apos;s what&apos;s happening across {data?.institutionName || "your institution"}.</p></div>{canSchedule && <button className="button button-primary" onClick={() => document.getElementById("assistant")?.scrollIntoView({ behavior: "smooth" })}><Icon name="spark" size={16} />Generate a timetable <Icon name="arrow" size={15} /></button>}</section>
        {loadError && <div className="alert alert-error" role="alert"><strong>Backend connection issue</strong><span>{loadError}</span><button onClick={() => user && backendRequest<DashboardData>(user, "/api/v1/dashboard").then(setData).catch((error) => setLoadError(error.message))}>Try again</button></div>}
        {actionError && <div className="alert alert-error" role="alert"><strong>Action couldn&apos;t be completed</strong><span>{actionError}</span><button onClick={() => setActionError("")}>Dismiss</button></div>}

        <section className="hero-card"><div className="hero-copy"><div className="hero-kicker"><span className="hero-dot" /> YOUR SMART TIMETABLE HUB</div><h2>Make every period<br />count.</h2><p>Plan a clearer week with data-backed scheduling and faculty coverage, all in one place.</p><a href="#assistant" className="hero-link">Plan with AI <Icon name="arrow" size={16} /></a></div><div className="hero-art" aria-hidden="true"><div className="orbit orbit-one" /><div className="orbit orbit-two" /><div className="hero-sun" /><div className="hero-grid"><span /><span /><span /><span /><span /><span /><span /><span /><span /></div><div className="float-card float-card-top"><i className="float-dot green" /><span><b>Scheduling engine</b><small>Constraint validated</small></span><Icon name="check" size={15} /></div><div className="float-card float-card-bottom"><span className="mini-spark">✦</span><span><b>Smarter planning</b><small>Powered by your data</small></span></div><div className="art-ring" /></div></section>

        <section className="metrics-grid" id="academics" aria-label="Academic data summary"><MetricCard title="Faculty members" value={data?.counts.faculty} icon="users" tone="mint" detail="In your academic data" /><MetricCard title="Subjects" value={data?.counts.subjects} icon="book" tone="lavender" detail="Available to schedule" /><MetricCard title="Student sections" value={data?.counts.sections} icon="grid" tone="peach" detail="Across departments" /><MetricCard title="Timetable drafts" value={data?.counts.drafts} icon="calendar" tone="blue" detail="Ready for review" /></section>

        {canManageUsers && <UserAdmin user={user} />}

        {canSchedule && <AcademicWorkspace user={user} role={data?.role || ""} onSaved={() => { void backendRequest<DashboardData>(user, "/api/v1/dashboard").then(setData).catch((error) => setLoadError(error.message)); }} />}

        <div className="content-grid">
          <section className="panel schedule-panel" id="timetable"><div className="panel-heading"><div><p className="eyebrow">LIVE SCHEDULE</p><h3>{data?.role === "FACULTY" ? "My mapped classes" : "Published timetable"}</h3></div>{activeSchedule && <span className="status-badge published"><i />Published</span>}</div>
            {activeSchedule ? <><p className="panel-note">{data?.role === "FACULTY" ? "My assigned classes · Academic term" : "Academic term"} <strong>{activeSchedule.termId}</strong> · {activeEntries.length} scheduled sessions</p><TimetableBoard entries={activeEntries} termId={activeSchedule.termId} role={data?.role || ""} canRecommend={canManageCoverage || data?.role === "FACULTY"} canAssign={canManageCoverage} busy={busy} substitutions={substitutions} onReportAbsence={reportAbsence} onAssignSubstitute={assignSubstitute} /><RoomAvailabilityFinder user={user} termId={activeSchedule.termId} /></> : <EmptyState icon="calendar" title="No published timetable yet" body="Once a reviewed draft is approved and published, the active schedule will appear here." />}
          </section>

          {canSchedule && <section className="panel assistant-panel" id="assistant"><div className="panel-heading"><div><p className="eyebrow">CONSTRAINT-BASED PLANNING</p><h3>Timetable copilot</h3></div><span className="ai-badge"><Icon name="spark" size={14} /> AI + CP-SAT</span></div><p className="assistant-intro">Describe what needs to be scheduled. AI translates it into a reviewable request; the solver checks hard constraints.</p>
            <form onSubmit={parseRequirements} className="composer-form"><label className="field-label" htmlFor="term">Academic term</label>{terms.length ? <select id="term" className="select-input" value={selectedTerm} onChange={(event) => setSelectedTerm(event.target.value)}><option value="">Choose a term</option>{terms.map(([id, term]) => <option key={id} value={id}>{term.name || id}</option>)}</select> : <input id="term" className="text-input" value={selectedTerm} onChange={(event) => setSelectedTerm(event.target.value)} placeholder="Term ID from your academic setup" />}<label className="field-label" htmlFor="request">Scheduling request</label><textarea id="request" className="request-input" rows={4} value={requirementText} onChange={(event) => setRequirementText(event.target.value)} placeholder="Write naturally, for example: Schedule digital electronics for section 2C three times a week and keep Wednesdays off for Rahul." /><div className="composer-footer"><span><Icon name="clock" size={14} /> Requires your review before scheduling</span><button className="button button-dark" disabled={parsing || !selectedTerm || requirementText.trim().length < 4}>{parsing ? "Interpreting…" : <>Interpret request <Icon name="arrow" size={14} /></>}</button></div></form>
            {interpretation && <div className="interpretation-card"><div className="interpretation-title"><span className="review-icon"><Icon name="check" size={14} /></span><div><strong>Review the interpretation</strong><small>Nothing will be scheduled until you confirm.</small></div></div>{interpretation.clarification_questions?.length > 0 && <div className="review-warning"><b>Clarification needed</b>{interpretation.clarification_questions.map((question, i) => <p key={i}>{question}</p>)}</div>}{interpretation.unsupported_requirements?.length > 0 && <div className="review-warning"><b>Could not apply</b>{interpretation.unsupported_requirements.map((item, i) => <p key={i}>{item}</p>)}</div>}{interpretation.changes?.length > 0 ? <ul className="change-list">{interpretation.changes.map((change, index) => <li key={`${change.subject_id}-${change.section_id}-${index}`}><strong>{change.subject_id}</strong><span>{change.section_id}{change.max_sessions_per_week ? ` · up to ${change.max_sessions_per_week} sessions/week` : change.sessions_per_week ? ` · ${change.sessions_per_week} sessions/week` : ""}{change.periods_per_session && change.periods_per_session > 1 ? ` · ${change.periods_per_session}-period block` : ""}</span>{change.preferred_faculty_ids?.length > 0 && <small>Preferred faculty: {change.preferred_faculty_ids.join(", ")}</small>}{change.excluded_slot_ids?.length > 0 && <small>Avoid: {change.excluded_slot_ids.join(", ")}</small>}</li>)}</ul> : <p className="muted-copy">No scheduling changes were extracted from this request.</p>}{interpretation.faculty_unavailability?.length > 0 && <ul className="change-list">{interpretation.faculty_unavailability.map((absence, index) => <li key={`${absence.faculty_id}-${index}`}><strong>Recurring faculty availability</strong><span>{absence.faculty_id} · unavailable: {absence.excluded_slot_ids.join(", ")}</span></li>)}</ul>}<label className="review-check"><input type="checkbox" checked={reviewed} onChange={(event) => setReviewed(event.target.checked)} disabled={Boolean(interpretation.clarification_questions?.length)} /><span>I checked this interpretation and want to create a draft.</span></label><button className="button button-primary full-button" onClick={generateDraft} disabled={!reviewed || busy || Boolean(interpretation.clarification_questions?.length) || !interpretation.changes?.length}>{busy ? "Starting solver…" : "Generate validated draft"}<Icon name="arrow" size={15} /></button></div>}
            {currentJob && <><div className={`job-card ${currentJob.status === "FAILED" ? "job-failed" : ""}`}><span className="job-spinner" /><div><strong>{currentJob.status === "COMPLETED" ? "Draft ready for review" : currentJob.status === "FAILED" ? "Generation needs attention" : "Solving timetable constraints"}</strong><small>{currentJob.message || "Checking faculty, section, room, and availability conflicts…"}</small></div><span className="job-state">{currentJob.status}</span></div>{currentJob.diagnostics?.map((diagnostic, index) => <p className="diagnostic-line" key={`${diagnostic.code}-${index}`}>{diagnostic.code}: {diagnostic.message}</p>)}</>}
          </section>}
        </div>

        {canReview && <section className="panel queue-panel"><div className="panel-heading"><div><p className="eyebrow">VERSION REVIEW</p><h3>Draft queue</h3></div><span className="queue-count">{data?.drafts?.length || 0} open</span></div>{data?.drafts?.length ? <div className="draft-list">{data.drafts.map((item) => <div className="draft-list-item" key={item.id}><div><strong>{item.termId}</strong><small>{item.solverStatus || "Awaiting review"} · {list(item.entries).length} classes</small></div><span className={`status-badge ${item.status.toLowerCase()}`}><i />{item.status}</span><button className="button button-light" onClick={() => openDraft(item.id)} disabled={busy}>Open <Icon name="chevron" size={14} /></button></div>)}</div> : <EmptyLine text="No timetable drafts are waiting for review." />}</section>}
        {draft && <section className="panel draft-panel"><div className="panel-heading"><div><p className="eyebrow">VERSIONED DRAFT · {draft.solverStatus}</p><h3>Timetable ready for review</h3></div><span className={`status-badge ${draft.status.toLowerCase()}`}><i />{draft.status}</span></div><p className="panel-note">This version contains {list(draft.entries).length} classes and passed independent validation.</p>{draft.diagnostics?.map((item) => <p className="diagnostic-line" key={item.code}>{item.code}: {item.message}</p>)}<div className="draft-actions">{data?.role === "ACADEMIC_APPROVER" && draft.status === "DRAFT" && <button className="button button-primary" onClick={() => transitionDraft("approve")} disabled={busy}>Approve timetable <Icon name="check" size={15} /></button>}{data?.role === "SUPER_ADMIN" && draft.status === "APPROVED" && <button className="button button-primary" onClick={() => transitionDraft("publish")} disabled={busy}>Publish version <Icon name="arrow" size={15} /></button>}</div></section>}

        {data?.role !== "STUDENT_VIEWER" && <section className="panel policy-panel" id="policies"><div className="panel-heading"><div><p className="eyebrow">GROUNDED ANSWERS</p><h3>Ask your policy library</h3></div><span className="policy-lock">Institution-only</span></div><p className="assistant-intro">Answers use retrieved policy excerpts and include source citations. Unsupported questions are called out.</p><form className="policy-form" onSubmit={queryPolicy}><input className="text-input" aria-label="Ask your institution policy library" value={policyQuestion} onChange={(event) => setPolicyQuestion(event.target.value)} placeholder="Ask about workload limits, labs, or scheduling rules…" /><button className="button button-dark" disabled={busy || policyQuestion.trim().length < 4}>Ask <Icon name="arrow" size={14} /></button></form>{policyAnswer && <div className="policy-answer"><p>{policyAnswer.answer}</p>{!policyAnswer.supported_by_documents && <span className="unsupported-note">No supporting source found in indexed policy documents.</span>}{policyAnswer.citations?.length > 0 && <div className="citations">{policyAnswer.citations.map((citation, index) => <span key={`${citation.document_id}-${index}`}>{citation.title}{citation.page ? ` · p. ${citation.page}` : ""}{citation.section ? ` · ${citation.section}` : ""}</span>)}</div>}</div>}</section>}
        <footer className="page-footer"><span>Riven Labs · Academic scheduling</span><span>Signed in as <strong>{user.email}</strong></span></footer>
      </div>
    </main>
  </div>;
}

function MetricCard({ title, value, icon, tone, detail }: { title: string; value?: number; icon: string; tone: string; detail: string }) {
  return <article className="metric-card"><div className={`metric-icon ${tone}`}><Icon name={icon} size={18} /></div><div className="metric-value">{value === undefined ? "—" : value}</div><div className="metric-title">{title}</div><div className="metric-detail">{detail}</div></article>;
}
function TimetableBoard({ entries, termId, role, canRecommend, canAssign, busy, substitutions, onReportAbsence, onAssignSubstitute }: { entries: TimetableEntry[]; termId: string; role: string; canRecommend: boolean; canAssign: boolean; busy: boolean; substitutions: Record<string, SubstituteResult>; onReportAbsence: (entry: TimetableEntry, reason: string) => Promise<boolean>; onAssignSubstitute: (entry: TimetableEntry, facultyId: string) => void }) {
  const [section, setSection] = useState("all");
  const [faculty, setFaculty] = useState("all");
  const [reporting, setReporting] = useState<TimetableEntry | null>(null);
  const [reason, setReason] = useState("");
  const [reportBusy, setReportBusy] = useState(false);
  const sections = [...new Set(entries.map((entry) => entry.section_id).filter(Boolean))].sort();
  const facultyMembers = [...new Set(entries.map((entry) => entry.faculty_id).filter(Boolean))].sort();
  const visible = entries.filter((entry) => (section === "all" || entry.section_id === section) && (faculty === "all" || entry.faculty_id === faculty));
  const days = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"].filter((day) => visible.some((entry) => entry.day.slice(0, 3).toUpperCase() === day));
  const periods = [...new Set(visible.map((entry) => entry.period))].sort((a, b) => a - b);

  function exportCsv() {
    const fields = ["day", "period", "time", "duration_hours", "subject", "section", "department", "department_block", "faculty", "faculty_cabin", "room", "room_type"];
    const escape = (value: unknown) => `"${String(value ?? "").replaceAll('"', '""')}"`;
    const rows = visible.map((entry) => [entry.day, entry.period, [entry.time_start, entry.time_end].filter(Boolean).join("–"), entry.duration, entry.subject_name || entry.subject_id, entry.section_name || entry.section_id, entry.department_name || entry.department_id, entry.department_block_no ? "Block " + entry.department_block_no : "", entry.faculty_name || entry.faculty_id, entry.faculty_cabin_no, entry.room_code || entry.room_name || entry.room_id, entry.room_type].map(escape).join(","));
    const blob = new Blob([`\uFEFF${fields.join(",")}\r\n${rows.join("\r\n")}`], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a"); link.href = url; link.download = `timetable-${termId}.csv`; link.click(); URL.revokeObjectURL(url);
  }

  async function submitAbsence() {
    if (!reporting || reason.trim().length < 3) return;
    setReportBusy(true);
    const saved = await onReportAbsence(reporting, reason.trim());
    setReportBusy(false);
    if (saved) { setReporting(null); setReason(""); }
  }

  return <div className="timetable-board">
    <div className="timetable-toolbar"><div className="timetable-filters"><label>Section<select value={section} onChange={(event) => setSection(event.target.value)}><option value="all">All sections</option>{sections.map((value) => <option key={value}>{value}</option>)}</select></label><label>Faculty<select value={faculty} onChange={(event) => setFaculty(event.target.value)}><option value="all">All faculty</option>{facultyMembers.map((value) => <option key={value}>{value}</option>)}</select></label></div><button className="button button-light csv-button" type="button" onClick={exportCsv} disabled={!visible.length}>Export CSV</button></div>
    {!visible.length ? <EmptyLine text="No classes match the selected filters." /> : role === "FACULTY" ? <div className="timetable-scroll"><table className="timetable-table timetable-teacher-table"><thead><tr><th>Day</th><th>Time / period</th><th>Subject</th><th>Section</th><th>Department / block</th><th>Classroom / lab</th><th>Cabin</th><th>Coverage</th></tr></thead><tbody>{visible.map((entry) => { const key = `${entry.requirement_id}-${entry.day}-${entry.period}`; const result = substitutions[key]; const span = entry.duration > 1 ? `–${entry.period + entry.duration - 1}` : ""; const clock = [entry.time_start, entry.time_end].filter(Boolean).join("–"); const time = (entry.time_slot_label || `Period ${entry.period}${span}`) + (clock ? ` · ${clock}` : ""); return <tr key={key}><td>{entry.day}</td><td>{time}<small>{entry.duration} hour{entry.duration === 1 ? "" : "s"}</small></td><td><strong>{entry.subject_name || entry.subject_id}</strong></td><td>{entry.section_name || entry.section_id}</td><td>{entry.department_name || entry.department_id || "—"}<small>{entry.department_block_no ? `Block ${entry.department_block_no}` : "Block not set"}</small></td><td>{entry.room_code || entry.room_name || entry.room_id}<small>{entry.room_type || "Room"}</small></td><td>{entry.faculty_cabin_no || "—"}</td><td>{canRecommend && !entry.substitute && <button type="button" onClick={() => { setReporting(entry); setReason(""); }} disabled={busy}>Report absence</button>}{result && <div className="substitute-results">{result.candidates.length ? result.candidates.slice(0, 3).map((candidate) => <p key={candidate.id}><span><b>{candidate.name}</b><small>{[candidate.department_name, candidate.department_code, candidate.department_block_no ? `Block ${candidate.department_block_no}` : "", ...(candidate.reasons?.slice(0, 2) ?? [])].filter(Boolean).join(" · ")}</small></span>{canAssign && <button type="button" disabled={busy} onClick={() => onAssignSubstitute(entry, candidate.id)}>Assign</button>}</p>) : <small>No eligible faculty available.</small>}</div>}</td></tr>; })}</tbody></table></div> : <div className="timetable-scroll"><table className="timetable-table"><thead><tr><th>Period</th>{days.map((day) => <th key={day}>{day}</th>)}</tr></thead><tbody>{periods.map((period) => <tr key={period}><th scope="row">{period}</th>{days.map((day) => { const cellEntries = visible.filter((item) => item.day.slice(0, 3).toUpperCase() === day && item.period === period); return <td key={day} className={cellEntries.length ? "" : "timetable-empty-cell"}>{cellEntries.map((entry) => { const key = `${entry.requirement_id}-${entry.day}-${entry.period}`; const result = substitutions[key]; const room = entry.room_code || entry.room_name || entry.room_id; const block = entry.department_block_no ? `Block ${entry.department_block_no}` : ""; const clock = [entry.time_start, entry.time_end].filter(Boolean).join("–"); return <article className="timetable-class" key={key}><strong>{entry.subject_name || entry.subject_id}{entry.substitute ? " · COVER" : ""}</strong><span>{entry.section_name || entry.section_id} · {entry.department_name || entry.department_id || "Department"} {block} · {room}{entry.room_type ? ` · ${entry.room_type}` : ""}</span><small>{entry.faculty_name || entry.faculty_id}{entry.faculty_cabin_no ? ` · Cabin ${entry.faculty_cabin_no}` : ""} · {clock || `Period ${entry.period}`}{entry.duration > 1 ? ` · ${entry.duration} hours` : " · 1 hour"}</small>{canRecommend && !entry.substitute && <button type="button" onClick={() => { setReporting(entry); setReason(""); }} disabled={busy}>Report absence &amp; find cover</button>}{result && <div className="substitute-results">{result.candidates.length ? result.candidates.slice(0, 3).map((candidate) => <p key={candidate.id}><span><b>{candidate.name}</b><small>{[candidate.department_name, candidate.department_code, candidate.department_block_no ? `Block ${candidate.department_block_no}` : "", ...(candidate.reasons?.slice(0, 2) ?? [])].filter(Boolean).join(" · ")}</small></span>{canAssign && <button type="button" disabled={busy} onClick={() => onAssignSubstitute(entry, candidate.id)}>Assign</button>}</p>) : <small>No eligible faculty available.</small>}</div>}</article>; })}</td>; })}</tr>)}</tbody></table></div>}
    <p className="timetable-footnote">Showing {visible.length} of {entries.length} sessions · updates follow the published Realtime Database version.</p>
    {reporting && <div className="absence-backdrop"><section className="absence-dialog" role="dialog" aria-modal="true" aria-labelledby="absence-title"><p className="eyebrow">FACULTY COVERAGE</p><h4 id="absence-title">Report a class absence</h4><p className="absence-class-label">{reporting.subject_name || reporting.subject_id} · {reporting.section_id} · {reporting.day} period {reporting.period}</p><label htmlFor="absence-reason">Reason for absence</label><textarea id="absence-reason" value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} placeholder="Add a short note for the timetable team…" autoFocus /><div className="absence-actions"><button type="button" className="button button-light" onClick={() => setReporting(null)} disabled={reportBusy}>Cancel</button><button type="button" className="button button-primary" onClick={() => void submitAbsence()} disabled={reportBusy || reason.trim().length < 3}>{reportBusy ? "Finding cover…" : "Report & find cover"}</button></div></section></div>}
  </div>;
}
function EmptyState({ icon, title, body }: { icon: string; title: string; body: string }) { return <div className="empty-state"><span className="empty-icon"><Icon name={icon} size={21} /></span><strong>{title}</strong><p>{body}</p></div>; }
function EmptyLine({ text }: { text: string }) { return <p className="empty-line">{text}</p>; }
function LoadingScreen() { return <div className="auth-screen"><div className="loading-mark"><span className="brand-mark"><span /></span><p>Loading your workspace…</p></div></div>; }
function AccessPendingScreen({ email, onSignOut }: { email: string; onSignOut: () => void }) {
  return <main className={`access-pending-screen ${accessAdminStyles.root}`}><section className="access-pending-card" aria-labelledby="access-pending-title"><a className="brand access-pending-brand" href="#"><span className="brand-mark"><span /></span><span><strong>riven</strong><small>ACADEMIC SYSTEMS</small></span></a><div className="access-pending-icon"><Icon name="users" size={22} /></div><p className="eyebrow">ROLE NOT ASSIGNED</p><h1 id="access-pending-title">Request your class advisor to assign a role, or request the admin to check your role.</h1><p className="access-pending-email">Signed in as {email}</p><button className="button button-light" type="button" onClick={onSignOut}>Sign out</button></section></main>;
}
function LoginScreen({ email, password, setEmail, setPassword, onSubmit, onGoogleSignIn, busy, error }: { email: string; password: string; setEmail: (value: string) => void; setPassword: (value: string) => void; onSubmit: (event: FormEvent<HTMLFormElement>) => void; onGoogleSignIn: () => void; busy: boolean; error: string }) {
  return <main className={`auth-screen ${accessAdminStyles.root}`}><div className="auth-aside"><a className="brand auth-brand" href="#"><span className="brand-mark"><span /></span><span><strong>riven</strong><small>ACADEMIC SYSTEMS</small></span></a><div className="auth-aside-copy"><span className="hero-kicker"><span className="hero-dot" /> SMARTER ACADEMIC PLANNING</span><h1>Make room for<br />a better week.</h1><p>A clearer way to plan classes, protect faculty time, and find the right cover when plans change.</p><div className="auth-art"><div className="art-ring" /><div className="hero-sun" /><div className="auth-art-grid"><span /><span /><span /><span /><span /><span /><span /><span /><span /></div></div></div><div className="auth-aside-footer">Riven Labs · Timetable management</div></div><div className="auth-main"><section className="login-card"><div className="login-mark"><Icon name="grid" size={20} /></div><p className="eyebrow">WELCOME TO RIVEN</p><h2>Sign in to Riven</h2><p className="login-intro">Continue with Google. New accounts are created automatically and can access the timetable after an administrator assigns a role.</p>{error && <div className="login-error" role="alert">{error}</div>}<button className="google-login-button" type="button" onClick={onGoogleSignIn} disabled={busy}><GoogleMark />{busy ? "Connecting…" : "Continue with Google"}</button><div className="login-divider"><span>or sign in with email</span></div><form onSubmit={onSubmit}><label htmlFor="email">Work email</label><input id="email" className="text-input" type="email" autoComplete="email" required value={email} onChange={(event) => setEmail(event.target.value)} placeholder="you@institution.edu" /><label htmlFor="password">Password</label><input id="password" className="text-input" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} placeholder="Enter your password" /><button className="button button-primary login-button" disabled={busy}>{busy ? "Signing in…" : "Continue with email"}<Icon name="arrow" size={15} /></button></form><p className="login-footnote">Access is managed by your class advisor or timetable administrator.</p></section></div></main>;
}
function GoogleMark() { return <svg aria-hidden="true" width="17" height="17" viewBox="0 0 48 48"><path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/><path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94a11.1 11.1 0 0 1-4.8 7.27l7.75 6.01c4.53-4.18 7.09-10.34 7.09-17.75z"/><path fill="#FBBC05" d="M10.53 28.59a14.4 14.4 0 0 1 0-9.18l-7.98-6.19a24 24 0 0 0 0 21.56z"/><path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.9-5.78l-7.75-6.01c-2.15 1.45-4.9 2.3-8.15 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/></svg>; }
