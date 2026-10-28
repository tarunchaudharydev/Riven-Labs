"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { User } from "firebase/auth";
import { backendRequest } from "../lib/backend";
import styles from "./access-admin.module.css";

type ManagedRole = "" | "STUDENT_VIEWER" | "FACULTY" | "DEPARTMENT_ADMIN" | "TIMETABLE_INCHARGE" | "ACADEMIC_APPROVER" | "SUPER_ADMIN";
type AdminUser = {
  uid: string;
  email: string;
  displayName: string;
  photoUrl?: string;
  providers?: string[];
  role: ManagedRole;
  disabled: boolean;
  departmentId?: string | null;
  facultyId?: string | null;
  sectionId?: string | null;
};
type UsersPage = { users: AdminUser[]; nextPageToken?: string | null };
type Assignment = { role: ManagedRole; departmentId: string; facultyId: string; sectionId: string };

const ROLE_OPTIONS: { value: ManagedRole; label: string }[] = [
  { value: "", label: "No role assigned" },
  { value: "STUDENT_VIEWER", label: "Student" },
  { value: "FACULTY", label: "Teacher" },
  { value: "DEPARTMENT_ADMIN", label: "Admin · Department admin" },
  { value: "TIMETABLE_INCHARGE", label: "Timetable incharge" },
  { value: "ACADEMIC_APPROVER", label: "Academic approver" },
  { value: "SUPER_ADMIN", label: "Super admin" },
];

const CSV_COLUMNS = ["uid", "email", "displayName", "role", "departmentId", "facultyId", "sectionId"] as const;
const REQUIRED_SCOPE: Partial<Record<ManagedRole, { field: "departmentId" | "facultyId" | "sectionId"; label: string }>> = {
  DEPARTMENT_ADMIN: { field: "departmentId", label: "Department" },
  FACULTY: { field: "facultyId", label: "Faculty" },
  STUDENT_VIEWER: { field: "sectionId", label: "Section" },
};

function assignmentScopeError(assignment: Assignment) {
  const required = REQUIRED_SCOPE[assignment.role];
  if (!required || assignment[required.field].trim()) return "";
  return `${required.label} ID is required for this role. Create it under Academic data, then enter its exact ID here.`;
}

function roleAssignmentError(cause: unknown, assignment: Assignment) {
  const message = cause instanceof Error ? cause.message : "Role assignment failed.";
  if (!/^(bad request|request failed \(400\))$/i.test(message.trim())) return message;
  const required = REQUIRED_SCOPE[assignment.role];
  if (required) return `${required.label} ID was not accepted. Check that it exactly matches an existing record under Academic data.`;
  return "The role or scope values were not accepted. Check them and try again.";
}

function escapeCsv(value: unknown) {
  const text = String(value ?? "");
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
}

function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') { field += '"'; index += 1; }
      else if (char === '"') quoted = false;
      else field += char;
    } else if (char === '"' && field.length === 0) quoted = true;
    else if (char === ",") { row.push(field); field = ""; }
    else if (char === "\n" || char === "\r") {
      if (char === "\r" && text[index + 1] === "\n") index += 1;
      row.push(field); field = "";
      if (row.some((item) => item.trim())) rows.push(row);
      row = [];
    } else field += char;
  }
  row.push(field);
  if (row.some((item) => item.trim())) rows.push(row);
  if (quoted) throw new Error("The CSV has an unfinished quoted field.");
  return rows;
}

function fileNameCsv() { return `riven-user-roles-${new Date().toISOString().slice(0, 10)}.csv`; }

export default function UserAdmin({ user }: { user: User }) {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [nextPageToken, setNextPageToken] = useState("");
  const [pageTokens, setPageTokens] = useState<string[]>([""]);
  const [query, setQuery] = useState("");
  const [edits, setEdits] = useState<Record<string, Assignment>>({});
  const [loading, setLoading] = useState(true);
  const [busyUid, setBusyUid] = useState("");
  const [importing, setImporting] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);

  const loadUsers = useCallback(async (token = "", reset = false) => {
    setLoading(true);
    setError("");
    try {
      const params = new URLSearchParams({ limit: "100" });
      if (token) params.set("pageToken", token);
      const result = await backendRequest<UsersPage>(user, `/api/v1/admin/users?${params.toString()}`);
      setUsers(result.users || []);
      setNextPageToken(result.nextPageToken || "");
      if (reset) setPageTokens([""]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load the user directory.");
    } finally { setLoading(false); }
  }, [user]);

  useEffect(() => { void loadUsers(); }, [loadUsers]);

  const visibleUsers = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return users;
    return users.filter((item) => [item.displayName, item.email, item.uid, item.role].some((value) => String(value || "").toLowerCase().includes(normalized)));
  }, [users, query]);

  function assignmentFor(item: AdminUser): Assignment {
    return edits[item.uid] || {
      role: item.role || "",
      departmentId: item.departmentId || "",
      facultyId: item.facultyId || "",
      sectionId: item.sectionId || "",
    };
  }

  function patchAssignment(uid: string, patch: Partial<Assignment>) {
    const current = users.find((item) => item.uid === uid);
    if (!current) return;
    const previous = assignmentFor(current);
    const assignment = { ...previous, ...patch };
    if (patch.role !== undefined && patch.role !== previous.role) {
      if (patch.role === "STUDENT_VIEWER") { assignment.departmentId = ""; assignment.facultyId = ""; }
      else if (patch.role === "FACULTY") assignment.sectionId = "";
      else if (patch.role === "DEPARTMENT_ADMIN" || patch.role === "TIMETABLE_INCHARGE" || patch.role === "ACADEMIC_APPROVER") { assignment.facultyId = ""; assignment.sectionId = ""; }
      else { assignment.departmentId = ""; assignment.facultyId = ""; assignment.sectionId = ""; }
    }
    setEdits((old) => ({ ...old, [uid]: assignment }));
  }

  async function saveAssignment(item: AdminUser, assignment = assignmentFor(item)) {
    const scopeError = assignmentScopeError(assignment);
    if (scopeError) { setError(scopeError); setNotice(""); return false; }
    setBusyUid(item.uid); setError(""); setNotice("");
    try {
      await backendRequest(user, `/api/v1/admin/users/${encodeURIComponent(item.uid)}/role`, {
        method: "PUT",
        body: JSON.stringify({
          role: assignment.role,
          departmentId: assignment.departmentId.trim() || null,
          facultyId: assignment.facultyId.trim() || null,
          sectionId: assignment.sectionId.trim() || null,
        }),
      });
      setNotice(`Updated ${item.email || item.uid}. Their access will refresh automatically within 25 seconds.`);
      setEdits((old) => { const next = { ...old }; delete next[item.uid]; return next; });
      await loadUsers(pageTokens.at(-1) || "");
      return true;
    } catch (cause) {
      setError(roleAssignmentError(cause, assignment));
      return false;
    } finally { setBusyUid(""); }
  }

  async function exportAllUsers() {
    setError(""); setNotice("");
    try {
      const all: AdminUser[] = [];
      let token = "";
      do {
        const params = new URLSearchParams({ limit: "100" });
        if (token) params.set("pageToken", token);
        const result = await backendRequest<UsersPage>(user, `/api/v1/admin/users?${params.toString()}`);
        all.push(...(result.users || []));
        token = result.nextPageToken || "";
      } while (token);
      const csv = [CSV_COLUMNS.join(","), ...all.map((item) => CSV_COLUMNS.map((column) => escapeCsv(column === "role" ? item.role || "" : item[column as keyof AdminUser])).join(","))].join("\r\n");
      const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
      const anchor = document.createElement("a");
      anchor.href = url; anchor.download = fileNameCsv(); anchor.click(); URL.revokeObjectURL(url);
      setNotice(`Exported ${all.length} Firebase accounts. Edit role and scope columns, then import the file to apply assignments.`);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Could not export the user directory."); }
  }

  async function importCsv(file?: File) {
    if (!file) return;
    setImporting(true); setError(""); setNotice("");
    try {
      const rows = parseCsv(await file.text());
      if (rows.length < 2) throw new Error("Add at least one user row to the CSV.");
      const headers = rows[0].map((value) => value.trim().replace(/^\uFEFF/, ""));
      const required = ["uid", "role"];
      const missing = required.filter((header) => !headers.includes(header));
      if (missing.length) throw new Error(`CSV must include ${missing.join(" and ")} columns. Export the user table for a template.`);
      if (rows.length - 1 > 200) throw new Error("Import is limited to 200 user rows per file. Split larger files into smaller batches.");
      const column = (record: string[], name: string) => { const index = headers.indexOf(name); return index < 0 ? "" : (record[index] || "").trim(); };
      const failures: string[] = [];
      let saved = 0;
      for (const [index, record] of rows.slice(1).entries()) {
        const uid = column(record, "uid");
        const role = column(record, "role").toUpperCase() as ManagedRole;
        if (!uid) { failures.push(`Row ${index + 2}: missing uid`); continue; }
        if (!ROLE_OPTIONS.some((option) => option.value === role)) { failures.push(`Row ${index + 2}: unsupported role`); continue; }
        const assignment = {
          role,
          departmentId: column(record, "departmentId"),
          facultyId: column(record, "facultyId"),
          sectionId: column(record, "sectionId"),
        };
        const scopeError = assignmentScopeError(assignment);
        if (scopeError) { failures.push(`Row ${index + 2}: ${scopeError}`); continue; }
        try {
          await backendRequest(user, `/api/v1/admin/users/${encodeURIComponent(uid)}/role`, {
            method: "PUT",
            body: JSON.stringify({ role, departmentId: assignment.departmentId || null, facultyId: assignment.facultyId || null, sectionId: assignment.sectionId || null }),
          });
          saved += 1;
        } catch (cause) { failures.push(`Row ${index + 2} (${uid}): ${roleAssignmentError(cause, assignment)}`); }
      }
      await loadUsers(pageTokens.at(-1) || "");
      setNotice(`Imported ${saved} role assignment${saved === 1 ? "" : "s"}.${failures.length ? ` ${failures.length} row${failures.length === 1 ? "" : "s"} need attention: ${failures.slice(0, 4).join("; ")}` : ""}`);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "Could not import this CSV file."); }
    finally { setImporting(false); if (fileInput.current) fileInput.current.value = ""; }
  }

  function downloadTemplate() {
    const csv = `${CSV_COLUMNS.join(",")}\r\n`;
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    const anchor = document.createElement("a"); anchor.href = url; anchor.download = "riven-user-roles-template.csv"; anchor.click(); URL.revokeObjectURL(url);
  }

  return <section className={`panel user-admin-panel ${styles.root}`} id="user-admin" aria-labelledby="user-admin-title">
    <div className="panel-heading"><div><p className="eyebrow">ACCESS CONTROL</p><h3 id="user-admin-title">Users &amp; roles</h3></div><span className="queue-count">Super admin</span></div>
    <p className="user-admin-intro">Google sign-ins appear here automatically. Assign a role and the matching academic scope to grant timetable access.</p>
    <p className="user-admin-intro">Department Admin needs a Department ID, Teacher needs a Faculty ID, and Student needs a Section ID. Use IDs already listed under Academic data.</p>
    {error && <div className="alert alert-error" role="alert"><strong>Couldn&apos;t complete this action</strong><span>{error}</span><button onClick={() => setError("")}>Dismiss</button></div>}
    {notice && <div className="alert alert-success" role="status"><strong>Done</strong><span>{notice}</span><button onClick={() => setNotice("")}>Dismiss</button></div>}
    <div className="user-admin-toolbar"><label className="user-search-label" htmlFor="user-search">Search users<input id="user-search" className="text-input" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Name, email or UID" /></label><div className="user-admin-actions"><button className="button button-light" onClick={downloadTemplate} type="button">CSV template</button><label className={`button button-light import-button ${importing ? "disabled" : ""}`}>Import CSV<input ref={fileInput} type="file" accept=".csv,text/csv" hidden disabled={importing} onChange={(event) => void importCsv(event.target.files?.[0])} /></label><button className="button button-light" onClick={() => void exportAllUsers()} type="button" disabled={loading}>Export CSV</button><button className="button button-primary" onClick={() => void loadUsers(pageTokens.at(-1) || "")} type="button" disabled={loading}>{loading ? "Loading…" : "Refresh"}</button></div></div>
    <div className="user-table-scroll"><table className="user-table"><thead><tr><th>User</th><th>Role</th><th>Department ID</th><th>Faculty ID</th><th>Section ID</th><th>Access</th><th /></tr></thead><tbody>
      {loading && !users.length ? <tr><td colSpan={7} className="user-table-empty">Loading Firebase users…</td></tr> : visibleUsers.length ? visibleUsers.map((item) => {
        const assignment = assignmentFor(item);
        const changed = Boolean(edits[item.uid]);
        const provider = item.providers?.includes("google.com") ? "Google" : item.providers?.[0]?.replace(".com", "") || "Firebase";
        return <tr key={item.uid}>
          <td><div className="user-identity"><span className="user-profile-avatar">{(item.displayName || item.email || "U").slice(0, 1).toUpperCase()}{item.photoUrl && <img src={item.photoUrl} alt="" referrerPolicy="no-referrer" onError={(event) => { event.currentTarget.style.display = "none"; }} />}</span><div><strong>{item.displayName || "Google user"}</strong><small>{item.email || "No email"}</small><span className="user-provider-badge">{provider} account</span><code>{item.uid}</code></div></div></td>
          <td><select aria-label={`Role for ${item.email || item.uid}`} className="user-role-select" value={assignment.role} onChange={(event) => patchAssignment(item.uid, { role: event.target.value as ManagedRole })}>{ROLE_OPTIONS.map((option) => <option key={option.value || "none"} value={option.value}>{option.label}</option>)}</select></td>
          {(["departmentId", "facultyId", "sectionId"] as const).map((key) => <td key={key}><input aria-label={`${key} for ${item.email || item.uid}`} className="user-scope-input" value={assignment[key]} onChange={(event) => patchAssignment(item.uid, { [key]: event.target.value })} placeholder={REQUIRED_SCOPE[assignment.role]?.field === key ? "Required ID" : "Not set"} /></td>)}
          <td><span className={`user-access-pill ${item.role ? "assigned" : "pending"}`}>{item.disabled ? "Disabled" : item.role ? "Assigned" : "Pending"}</span></td>
          <td><button className="button button-light user-save-button" type="button" disabled={busyUid === item.uid || !changed} onClick={() => void saveAssignment(item)}>{busyUid === item.uid ? "Saving…" : "Save"}</button></td>
        </tr>;
      }) : <tr><td colSpan={7} className="user-table-empty">{query ? "No users match this search on the current page." : "No Firebase accounts were found."}</td></tr>}
    </tbody></table></div>
    <div className="user-admin-footer"><span>Showing {users.length} account{users.length === 1 ? "" : "s"} on this page. Unassigned users cannot enter the timetable workspace.</span><div><button className="button button-light" type="button" disabled={pageTokens.length <= 1 || loading} onClick={() => { const next = pageTokens.slice(0, -1); const token = next.at(-1) || ""; setPageTokens(next); void loadUsers(token); }}>Previous</button><button className="button button-light" type="button" disabled={!nextPageToken || loading} onClick={() => { setPageTokens((old) => [...old, nextPageToken]); void loadUsers(nextPageToken); }}>Next</button></div></div>
  </section>;
}
