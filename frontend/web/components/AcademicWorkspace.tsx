"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import type { FormEvent } from "react";
import type { User } from "firebase/auth";
import { backendRequest } from "../lib/backend";
import styles from "./AcademicWorkspace.module.css";

type CollectionKey = "departments" | "academicTerms" | "faculty" | "subjects" | "sections" | "rooms";
type Field = { key: string; label: string; hint?: string; type?: "text" | "number" | "email" | "tel" | "checkbox" | "select"; options?: { value: string; label: string }[]; required?: boolean };
type Resource = { title: string; description: string; singular: string; fields: Field[] };
type AcademicRecord = Record<string, unknown> & { id: string; name: string };
type ShiftConfig = { id: string; name: string; start_time: string; end_time: string };
const DEFAULT_SHIFTS: ShiftConfig[] = [
  { id: "shift-1", name: "Shift 1", start_time: "08:00", end_time: "16:00" },
  { id: "shift-2", name: "Shift 2", start_time: "10:00", end_time: "18:00" },
];
const WEEKDAYS = ["MON", "TUE", "WED", "THU", "FRI"];

function hourlySlots(shifts: ShiftConfig[]) {
  const parseHour = (value: string) => {
    if (!/^([01]\d|2[0-3]):00$/.test(value)) throw new Error("Shift times must use whole hours, such as 08:00 or 16:00.");
    return Number(value.slice(0, 2));
  };
  if (shifts.length !== 2) throw new Error("Configure exactly two shifts.");
  const ranges = shifts.map((shift) => ({ ...shift, start: parseHour(shift.start_time), end: parseHour(shift.end_time) }));
  if (ranges.some((shift) => shift.start >= shift.end)) throw new Error("Each shift must end after it starts.");
  const first = Math.min(...ranges.map((shift) => shift.start));
  const last = Math.max(...ranges.map((shift) => shift.end));
  const slots: { id: string; day: string; period: number; label: string; start_time: string; end_time: string; shift_ids: string[]; is_teaching_period: boolean }[] = [];
  for (const day of WEEKDAYS) {
    for (let hour = first; hour < last; hour += 1) {
      const assigned = ranges.filter((shift) => shift.start <= hour && shift.end >= hour + 1);
      if (!assigned.length) continue;
      const start_time = `${String(hour).padStart(2, "0")}:00`;
      const end_time = `${String(hour + 1).padStart(2, "0")}:00`;
      slots.push({
        id: `${day.toLowerCase()}-${start_time.replace(":", "")}`,
        day,
        period: hour - first + 1,
        label: `${assigned.map((shift) => shift.name).join(" & ")} · ${start_time}–${end_time}`,
        start_time,
        end_time,
        shift_ids: assigned.map((shift) => shift.id),
        is_teaching_period: true,
      });
    }
  }
  return slots;
}

const resources: Record<CollectionKey, Resource> = {
  departments: { title: "Departments", singular: "department", description: "Organize the institution into academic units.", fields: [
    { key: "name", label: "Department name", required: true }, { key: "code", label: "Department code", required: true }, { key: "block_no", label: "Block number", hint: "Two digits shared across the institution, e.g. 01", required: true }, { key: "admin_uid", label: "Department administrator UID", hint: "Optional Firebase Auth UID" },
  ] },
  academicTerms: { title: "Academic terms", singular: "term", description: "Create the periods for which timetables and scheduling inputs are maintained.", fields: [
    { key: "name", label: "Term name", required: true }, { key: "academic_year", label: "Academic year", required: true }, { key: "status", label: "Status", hint: "For example: ACTIVE, DRAFT, ARCHIVED" }, { key: "start_date", label: "Start date", type: "text" }, { key: "end_date", label: "End date", type: "text" },
  ] },
  faculty: { title: "Faculty", singular: "faculty member", description: "Track qualified subjects, weekly teaching limits, and availability.", fields: [
    { key: "name", label: "Full name", required: true }, { key: "aliases", label: "Other names", hint: "Comma-separated names or initials" }, { key: "department_id", label: "Department ID", required: true }, { key: "cabin_no", label: "Teacher cabin number", hint: "For example: AB1 208" }, { key: "mobile_number", label: "Mobile number", type: "tel", required: true }, { key: "college_email", label: "College email", type: "email", required: true }, { key: "personal_email", label: "Personal email (optional)", type: "email" }, { key: "max_weekly_periods", label: "Maximum weekly periods", type: "number", required: true }, { key: "qualified_subject_ids", label: "Qualified subject IDs", hint: "Comma-separated IDs" }, { key: "unavailable_slot_ids", label: "Unavailable slot IDs", hint: "Comma-separated IDs" }, { key: "preferred_slot_ids", label: "Preferred slot IDs", hint: "Comma-separated IDs" }, { key: "active", label: "Active faculty", type: "checkbox" },
  ] },
  subjects: { title: "Subjects", singular: "subject", description: "Set course identifiers, workload requirements, and room constraints.", fields: [
    { key: "name", label: "Subject name", required: true }, { key: "code", label: "Subject code", required: true }, { key: "aliases", label: "Other names", hint: "Comma-separated names or abbreviations" }, { key: "department_id", label: "Department ID", required: true }, { key: "term_id", label: "Academic term ID" }, { key: "faculty_ids", label: "Eligible faculty IDs", hint: "Comma-separated IDs" }, { key: "sessions_per_week", label: "Sessions per week", type: "number" }, { key: "periods_per_session", label: "Duration (1-hour periods)", hint: "1 = 1-hour lecture; 2 = 2-hour lab. Editable per subject.", type: "number" }, { key: "room_type", label: "Required room type", hint: "CLASSROOM or LAB" }, { key: "required_equipment", label: "Required equipment", hint: "Comma-separated values" },
  ] },
  sections: { title: "Sections", singular: "section", description: "Define student cohorts, sizes, and academic grouping.", fields: [
    { key: "name", label: "Section name", required: true }, { key: "code", label: "Section code", required: true }, { key: "aliases", label: "Other names", hint: "Comma-separated names or abbreviations" }, { key: "department_id", label: "Department ID", required: true }, { key: "student_count", label: "Student count", type: "number", required: true }, { key: "shift_id", label: "College shift", type: "select", required: true, options: [{ value: "shift-1", label: "Shift 1" }, { value: "shift-2", label: "Shift 2" }] }, { key: "academic_year", label: "Academic year" }, { key: "term_id", label: "Academic term ID" },
  ] },
  rooms: { title: "Rooms & labs", singular: "room", description: "Set room capacities, equipment, and teaching type.", fields: [
    { key: "name", label: "Room name", required: true }, { key: "code", label: "Room code", required: true }, { key: "capacity", label: "Capacity", type: "number", required: true }, { key: "room_type", label: "Room type", hint: "CLASSROOM or LAB", required: true }, { key: "equipment", label: "Equipment", hint: "Comma-separated values" }, { key: "unavailable_slot_ids", label: "Unavailable slot IDs", hint: "Comma-separated IDs" },
  ] },
};

const emptyForm = (resource: Resource) => Object.fromEntries(resource.fields.map((field) => [field.key, field.type === "checkbox" ? false : ""])) as Record<string, string | boolean>;
const slug = (value: string) => value.trim().toLowerCase().normalize("NFKD").replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9_-]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 100);
const arrayFields = new Set(["aliases", "qualified_subject_ids", "faculty_ids", "unavailable_slot_ids", "preferred_slot_ids", "required_equipment", "equipment"]);
const numberFields = new Set(["max_weekly_periods", "sessions_per_week", "periods_per_session", "student_count", "capacity"]);

function recordSummary(collection: CollectionKey, record: AcademicRecord) {
  if (collection === "departments") return record.block_no ? `Block ${String(record.block_no)}` : "Block not set";
  if (collection === "academicTerms") return [record.academic_year, record.status, record.start_date && record.end_date ? `${record.start_date} – ${record.end_date}` : ""].filter(Boolean).join(" · ");
  if (collection === "sections") return [record.shift_id === "shift-2" ? "Shift 2" : record.shift_id === "shift-1" ? "Shift 1" : "Shift not set", record.student_count != null ? `${record.student_count} students` : ""].filter(Boolean).join(" · ");
  if (collection === "subjects") return [record.room_type, record.sessions_per_week != null ? `${record.sessions_per_week} sessions/week` : "", record.periods_per_session != null ? `${record.periods_per_session} hour block` : ""].filter(Boolean).join(" · ");
  if (collection === "rooms") return [record.room_type, record.capacity != null ? `${record.capacity} seats` : ""].filter(Boolean).join(" · ");
  return "";
}

export default function AcademicWorkspace({ user, role, onSaved }: { user: User; role: string; onSaved: () => void }) {
  const [collection, setCollection] = useState<CollectionKey>("departments");
  const [records, setRecords] = useState<AcademicRecord[]>([]);
  const [form, setForm] = useState<Record<string, string | boolean>>(emptyForm(resources.departments));
  const [recordId, setRecordId] = useState("");
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [ownDepartmentId, setOwnDepartmentId] = useState("");
  const resource = resources[collection];
  const writable = role === "SUPER_ADMIN" || role === "DEPARTMENT_ADMIN";
  const canWriteCollection = writable && !(role === "DEPARTMENT_ADMIN" && collection === "departments");

  useEffect(() => {
    if (role !== "DEPARTMENT_ADMIN") { setOwnDepartmentId(""); return; }
    let cancelled = false;
    backendRequest<Record<string, AcademicRecord>>(user, "/api/v1/academics/departments")
      .then((result) => {
        if (cancelled) return;
        const [id] = Object.keys(result || {});
        setOwnDepartmentId(id || "");
        if (id) setForm((current) => current.department_id ? current : { ...current, department_id: id });
      })
      .catch((caught) => { if (!cancelled) setError(caught instanceof Error ? caught.message : "Could not load your department assignment."); });
    return () => { cancelled = true; };
  }, [role, user]);

  const load = useCallback(async () => {
    try {
      const result = await backendRequest<Record<string, AcademicRecord>>(user, `/api/v1/academics/${collection}`);
      setRecords(Object.values(result || {}).filter((record) => record && typeof record === "object"));
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not load academic records.");
    } finally {
      setLoading(false);
    }
  }, [collection, user]);

  useEffect(() => {
    let cancelled = false;
    backendRequest<Record<string, AcademicRecord>>(user, `/api/v1/academics/${collection}`)
      .then((result) => { if (!cancelled) setRecords(Object.values(result || {}).filter((record) => record && typeof record === "object")); })
      .catch((caught) => { if (!cancelled) setError(caught instanceof Error ? caught.message : "Could not load academic records."); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [collection, user]);

  const sortedRecords = useMemo(() => [...records].sort((a, b) => {
    const aCurrent = ownDepartmentId && a.department_id === ownDepartmentId ? 0 : 1;
    const bCurrent = ownDepartmentId && b.department_id === ownDepartmentId ? 0 : 1;
    return aCurrent - bCurrent
      || String(a.department_name || "").localeCompare(String(b.department_name || ""))
      || String(a.name || a.id).localeCompare(String(b.name || b.id));
  }), [records, ownDepartmentId]);
  const canManageRecord = (record: AcademicRecord) => canWriteCollection
    && (role !== "DEPARTMENT_ADMIN" || collection !== "faculty" || record.department_id === ownDepartmentId);

  function freshForm(forResource: Resource) {
    const next = emptyForm(forResource);
    if (role === "DEPARTMENT_ADMIN" && "department_id" in next) next.department_id = ownDepartmentId;
    return next;
  }

  function startNew() {
    setRecordId(""); setForm(freshForm(resource)); setEditing(false); setError(""); setNotice("");
  }

  function startEdit(record: AcademicRecord) {
    const next = emptyForm(resource);
    for (const field of resource.fields) {
      const value = field.key === "college_email" ? record.college_email || record.email : record[field.key];
      if (Array.isArray(value)) next[field.key] = value.join(", ");
      else if (typeof value === "boolean") next[field.key] = value;
      else if (value != null) next[field.key] = String(value);
    }
    setRecordId(record.id); setForm(next); setEditing(true); setError(""); setNotice("");
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!writable) return;
    const label = String(form.name || "").trim();
    const nextId = recordId || slug(String(form.code || label));
    if (!nextId) { setError("Add a name or code so this record can get a database ID."); return; }
    const body: Record<string, unknown> = {};
    for (const field of resource.fields) {
      const value = form[field.key];
      if (field.type === "checkbox") body[field.key] = Boolean(value);
      else if (arrayFields.has(field.key)) body[field.key] = String(value || "").split(",").map((item) => item.trim()).filter(Boolean);
      else if (numberFields.has(field.key)) body[field.key] = String(value || "").trim() === "" ? undefined : Number(value);
      else if (String(value ?? "").trim()) body[field.key] = String(value).trim();
    }
    setSaving(true); setError(""); setNotice("");
    try {
      await backendRequest(user, `/api/v1/academics/${collection}/${encodeURIComponent(nextId)}`, { method: "PUT", body: JSON.stringify(body) });
      setNotice(`${resource.singular[0].toUpperCase()}${resource.singular.slice(1)} saved.`);
      startNew();
      await load();
      onSaved();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not save this record.");
    } finally { setSaving(false); }
  }

  async function remove(record: AcademicRecord) {
    if (!writable || !window.confirm(`Delete ${record.name || record.id}? This cannot be undone.`)) return;
    setError(""); setNotice("");
    try {
      await backendRequest(user, `/api/v1/academics/${collection}/${encodeURIComponent(record.id)}`, { method: "DELETE" });
      if (recordId === record.id) startNew();
      setNotice(`${resource.singular[0].toUpperCase()}${resource.singular.slice(1)} deleted.`);
      await load(); onSaved();
    } catch (caught) { setError(caught instanceof Error ? caught.message : "Could not delete this record."); }
  }

  function changeCollection(next: CollectionKey) {
    setCollection(next); setLoading(true); setForm(freshForm(resources[next])); setRecordId(""); setEditing(false); setError(""); setNotice("");
  }

  return <section className={styles.workspace} id="academic-data">
    <div className={styles.heading}>
      <div><p className={styles.eyebrow}>INSTITUTION SETUP</p><h2>Academic data</h2><p className={styles.description}>Manage the trusted records used to validate AI requirements and build timetables.</p></div>
      <button className={styles.refresh} onClick={() => { setLoading(true); setError(""); void load(); }} disabled={loading} type="button">{loading ? "Refreshing…" : "Refresh records"}</button>
    </div>
    <div className={styles.tabs} role="tablist" aria-label="Academic data categories">
      {(Object.keys(resources) as CollectionKey[]).map((key) => <button key={key} type="button" role="tab" aria-selected={collection === key} className={collection === key ? styles.tabActive : styles.tab} onClick={() => changeCollection(key)}>{resources[key].title}<span>{key === collection ? records.length : ""}</span></button>)}
    </div>
    <div className={styles.columns}>
      <div className={styles.listPane}>
        <div className={styles.listHeading}><div><h3>{resource.title}</h3><p>{resource.description}</p></div><span>{records.length} records</span></div>
        {error && <p className={styles.error} role="alert">{error}</p>}
        {notice && <p className={styles.notice} role="status">{notice}</p>}
        {loading ? <div className={styles.empty}>Loading records…</div> : sortedRecords.length ? collection === "faculty" ? <div className={styles.tableScroll}><table className={styles.facultyTable}><thead><tr><th>Teacher</th><th>Department</th><th>Cabin</th><th>Mobile</th><th>College email</th><th>Personal email</th><th>Actions</th></tr></thead><tbody>
          {sortedRecords.map((record) => { const canManage = canManageRecord(record); const block = String(record.department_block_no || ""); return <tr key={record.id}><td><strong>{record.name || record.id}</strong><small>{record.code ? `${record.code} · ` : ""}{record.id}</small></td><td><strong>{String(record.department_name || record.department_id || "Unassigned")}</strong><small>{record.department_code ? `${String(record.department_code)} · ` : ""}{block ? `Block ${block}` : "Block not set"}</small></td><td>{String(record.cabin_no || "—")}</td><td>{String(record.mobile_number || "—")}</td><td>{String(record.college_email || record.email || "—")}</td><td>{String(record.personal_email || "—")}</td><td>{canManage ? <div className={styles.recordActions}><button type="button" onClick={() => startEdit(record)}>Edit</button><button type="button" className={styles.delete} onClick={() => void remove(record)}>Delete</button></div> : <span className={styles.viewOnly}>Coverage roster</span>}</td></tr>; })}
        </tbody></table></div> : <div className={styles.tableScroll}><table className={styles.facultyTable}><thead><tr><th>Name</th><th>ID</th><th>Code</th><th>Department</th><th>Details</th><th>Actions</th></tr></thead><tbody>
          {sortedRecords.map((record) => { const canManage = canManageRecord(record); return <tr key={record.id}><td><strong>{record.name || record.id}</strong></td><td>{record.id}</td><td>{String(record.code || "—")}</td><td>{String(record.department_name || record.department_id || "—")}</td><td>{recordSummary(collection, record) || "—"}</td><td>{canManage ? <div className={styles.recordActions}><button type="button" onClick={() => startEdit(record)}>Edit</button><button type="button" className={styles.delete} onClick={() => void remove(record)}>Delete</button></div> : <span className={styles.viewOnly}>View only</span>}</td></tr>; })}
        </tbody></table></div> : <div className={styles.empty}><strong>No {resource.title.toLowerCase()} yet</strong><span>Add the first record to start building your academic catalog.</span></div>}
      </div>
      {canWriteCollection && <form className={styles.formPane} onSubmit={save}>
        <div className={styles.formHeading}><div><h3>{editing ? `Edit ${resource.singular}` : `Add ${resource.singular}`}</h3><p>{editing ? `Record ID: ${recordId}` : "New records are stored in your institution’s Realtime Database."}</p></div>{editing && <button type="button" onClick={startNew} className={styles.cancel}>Cancel</button>}</div>
        <div className={styles.fields}>{resource.fields.map((field) => field.type === "checkbox" ? <label className={styles.checkbox} key={field.key}><input type="checkbox" checked={Boolean(form[field.key])} onChange={(event) => setForm((previous) => ({ ...previous, [field.key]: event.target.checked }))} /><span>{field.label}</span></label> : <label className={styles.field} key={field.key}><span>{field.label}{field.required && <i> *</i>}</span>{field.type === "select" ? <select required={field.required} value={String(form[field.key] || "")} onChange={(event) => setForm((previous) => ({ ...previous, [field.key]: event.target.value }))}><option value="">Choose a shift</option>{field.options?.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select> : <input type={field.type || "text"} required={field.required} min={field.type === "number" ? 1 : undefined} readOnly={role === "DEPARTMENT_ADMIN" && field.key === "department_id"} value={String(form[field.key] || "")} onChange={(event) => setForm((previous) => ({ ...previous, [field.key]: event.target.value }))} placeholder={field.hint || "Enter a value"} />}</label>)}</div>
        <div className={styles.formFooter}><p>ID is generated from the code, or from the name if no code is provided.</p><button type="submit" disabled={saving}>{saving ? "Saving…" : editing ? "Save changes" : `Add ${resource.singular}`}</button></div>
      </form>}
    </div>
    <SchedulingInputEditor user={user} writable={role === "SUPER_ADMIN" || role === "TIMETABLE_INCHARGE"} />
  </section>;
}

function SchedulingInputEditor({ user, writable }: { user: User; writable: boolean }) {
  const [terms, setTerms] = useState<Record<string, { name?: string }>>({});
  const [termId, setTermId] = useState("");
  const [json, setJson] = useState("");
  const [shifts, setShifts] = useState<ShiftConfig[]>(DEFAULT_SHIFTS);
  const [catalog, setCatalog] = useState<Record<string, Record<string, AcademicRecord>>>({});
  const [loadedTerm, setLoadedTerm] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      backendRequest<Record<string, { name?: string }>>(user, "/api/v1/academics/academicTerms"),
      ...(["sections", "faculty", "subjects", "rooms"] as const).map((key) => backendRequest<Record<string, AcademicRecord>>(user, `/api/v1/academics/${key}`)),
    ]).then(([nextTerms, sectionData, facultyData, subjectData, roomData]) => {
      if (cancelled) return;
      setTerms(nextTerms || {});
      setTermId((current) => current || Object.keys(nextTerms || {})[0] || "");
      setCatalog({ sections: sectionData || {}, faculty: facultyData || {}, subjects: subjectData || {}, rooms: roomData || {} });
    }).catch((caught) => { if (!cancelled) setError(caught instanceof Error ? caught.message : "Could not load timetable setup data."); });
    return () => { cancelled = true; };
  }, [user]);

  useEffect(() => {
    if (!termId) return;
    let cancelled = false;
    backendRequest<Record<string, unknown>>(user, `/api/v1/academics/terms/${encodeURIComponent(termId)}/scheduling-inputs`)
      .then((value) => {
        if (!cancelled) {
          setJson(value && Object.keys(value).length ? JSON.stringify(value, null, 2) : "");
          const configured = Array.isArray(value?.shifts) ? value.shifts : null;
          setShifts(configured?.length === 2
            ? configured.map((shift, index) => ({
                id: String(shift.id || DEFAULT_SHIFTS[index].id),
                name: String(shift.name || DEFAULT_SHIFTS[index].name),
                start_time: String(shift.start_time || DEFAULT_SHIFTS[index].start_time),
                end_time: String(shift.end_time || DEFAULT_SHIFTS[index].end_time),
              }))
            : DEFAULT_SHIFTS);
          setLoadedTerm(termId);
        }
      })
      .catch((caught) => { if (!cancelled) { setError(caught instanceof Error ? caught.message : "Could not load timetable inputs."); setLoadedTerm(termId); } });
    return () => { cancelled = true; };
  }, [termId, user]);

  function buildInputs() {
    const values = (key: string) => Object.values(catalog[key] || {});
    const sectionRecords = values("sections").filter((row) => !row.term_id || row.term_id === termId);
    const sections = sectionRecords.map((row) => ({ id: row.id, name: row.name, code: row.code || "", aliases: Array.isArray(row.aliases) ? row.aliases : [], department_id: row.department_id || "", student_count: Number(row.student_count || 0), shift_id: row.shift_id || shifts[0]?.id || DEFAULT_SHIFTS[0].id }));
    const faculty = values("faculty").map((row) => ({ id: row.id, name: row.name, aliases: Array.isArray(row.aliases) ? row.aliases : [], department_id: row.department_id || "", department_name: row.department_name || "", department_code: row.department_code || "", department_block_no: row.department_block_no || "", qualified_subject_ids: Array.isArray(row.qualified_subject_ids) ? row.qualified_subject_ids.filter((id): id is string => typeof id === "string") : [], unavailable_slot_ids: row.unavailable_slot_ids || [], preferred_slot_ids: row.preferred_slot_ids || [], max_weekly_periods: Number(row.max_weekly_periods || 18), active: row.active !== false }));
    const rooms = values("rooms").map((row) => ({ id: row.id, name: row.name, code: row.code || "", capacity: Number(row.capacity || 0), room_type: row.room_type || "CLASSROOM", equipment: row.equipment || [], unavailable_slot_ids: row.unavailable_slot_ids || [] }));
    const subjects = values("subjects").filter((row) => !row.term_id || row.term_id === termId);
    const requirements = subjects.flatMap((subject) => sectionRecords.filter((section) => !subject.department_id || section.department_id === subject.department_id).map((section) => ({
      id: `${subject.id}-${section.id}`.slice(0, 120), department_id: subject.department_id || section.department_id || "", subject_id: subject.id, subject_name: subject.name, code: subject.code || "", aliases: Array.isArray(subject.aliases) ? subject.aliases : [], section_id: section.id,
      sessions_per_week: Number(subject.sessions_per_week || 1), faculty_ids: Array.isArray(subject.faculty_ids) ? subject.faculty_ids : faculty.filter((member) => member.qualified_subject_ids.includes(subject.id)).map((member) => member.id),
      room_type: subject.room_type || "CLASSROOM", required_equipment: subject.required_equipment || [], periods_per_session: Number(subject.periods_per_session || (String(subject.room_type || "CLASSROOM").toUpperCase().includes("LAB") ? 2 : 1)), preferred_slot_ids: [], excluded_slot_ids: [],
    })));
    let time_slots;
    try { time_slots = hourlySlots(shifts); }
    catch (caught) { setError(caught instanceof Error ? caught.message : "Check the shift time ranges."); return; }
    setJson(JSON.stringify({ shifts, time_slots, sections, faculty, rooms, requirements }, null, 2));
    setNotice("Built one-hour slots across both shifts. Overlapping hours share the same clock slots, so a teacher or room cannot be double-booked between shifts. Labs use two consecutive hours by default.");
    setError("");
  }

  function editShift(index: number, key: "name" | "start_time" | "end_time", value: string) {
    setShifts((current) => current.map((shift, position) => position === index ? { ...shift, [key]: value } : shift));
  }

  function applyShiftHours() {
    let existing: Record<string, unknown> = {};
    try { existing = json.trim() ? JSON.parse(json) as Record<string, unknown> : {}; }
    catch { setError("Fix the scheduling JSON before applying the shift hours."); return; }
    try {
      const time_slots = hourlySlots(shifts);
      const sections = Array.isArray(existing.sections)
        ? existing.sections.map((section) => section && typeof section === "object"
            ? { ...(section as Record<string, unknown>), shift_id: (section as Record<string, unknown>).shift_id || shifts[0].id }
            : section)
        : existing.sections;
      setJson(JSON.stringify({ ...existing, shifts, time_slots, ...(sections ? { sections } : {}) }, null, 2));
      setNotice("Updated the JSON with the two configured shifts and shared hourly slots.");
      setError("");
    } catch (caught) { setError(caught instanceof Error ? caught.message : "Check the shift time ranges."); }
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!termId) return;
    let parsed: Record<string, unknown>;
    try { parsed = JSON.parse(json) as Record<string, unknown>; }
    catch { setError("The timetable inputs are not valid JSON. Fix the JSON before saving."); return; }
    setSaving(true); setError(""); setNotice("");
    try {
      await backendRequest(user, `/api/v1/academics/terms/${encodeURIComponent(termId)}/scheduling-inputs`, { method: "PUT", body: JSON.stringify(parsed) });
      setNotice("Validated timetable inputs saved to Firebase Realtime Database.");
    } catch (caught) { setError(caught instanceof Error ? caught.message : "Could not save timetable inputs."); }
    finally { setSaving(false); }
  }

  return <section className={styles.inputEditor}>
    <div className={styles.formHeading}><div><p className={styles.eyebrow}>SOLVER CONFIGURATION</p><h3>Timetable inputs</h3><p>Build the approved, term-specific data used by the CP-SAT engine.</p></div></div>
    <div className={styles.inputToolbar}><label><span>Academic term</span><select value={termId} onChange={(event) => setTermId(event.target.value)}><option value="">Select a term</option>{Object.entries(terms).map(([id, term]) => <option value={id} key={id}>{term.name || id}</option>)}</select></label><button type="button" className={styles.secondary} onClick={buildInputs} disabled={!termId}>Build from academic records</button></div>
    <div className={styles.shiftGrid} aria-label="College shift hours">
      {shifts.map((shift, index) => <fieldset className={styles.shiftCard} key={shift.id}><legend>{shift.name}</legend><label><span>Shift name</span><input value={shift.name} onChange={(event) => editShift(index, "name", event.target.value)} /></label><label><span>Starts</span><input type="time" step={3600} value={shift.start_time} onChange={(event) => editShift(index, "start_time", event.target.value)} /></label><label><span>Ends</span><input type="time" step={3600} value={shift.end_time} onChange={(event) => editShift(index, "end_time", event.target.value)} /></label></fieldset>)}
      <button type="button" className={styles.secondary} onClick={applyShiftHours} disabled={!termId}>Apply shift hours to JSON</button>
    </div>
    {notice && <p className={styles.notice} role="status">{notice}</p>}{error && <p className={styles.error} role="alert">{error}</p>}
    {!termId || loadedTerm !== termId ? <div className={styles.empty}>Loading timetable inputs…</div> : <form onSubmit={save}>
      <label className={styles.jsonLabel} htmlFor="scheduling-inputs-json">Approved scheduling input JSON</label>
      <textarea id="scheduling-inputs-json" className={styles.jsonEditor} value={json} onChange={(event) => setJson(event.target.value)} spellCheck={false} placeholder="Select a term, then build a starter from your catalog or paste valid JSON." />
      <div className={styles.formFooter}><p>Shift windows create a shared hourly clock grid from 08:00 to 18:00 by default. Assign each section to Shift 1 or Shift 2; overlapping hours share room and teacher availability checks. Edit slot flags for breaks.</p>{writable && <button type="submit" disabled={saving || !termId || !json.trim()}>{saving ? "Saving…" : "Validate & save inputs"}</button>}</div>
    </form>}
  </section>;
}
