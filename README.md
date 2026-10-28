# GLA University · Smart Timetable

GLA University - Mathura is an AI-assisted timetable and faculty workspace. The web client signs users in with Firebase Authentication and sends Firebase ID tokens to the Spring Boot API for writes and administrative data. It subscribes to audience-filtered published timetable mirrors in Firebase Realtime Database for live schedule updates. Realtime Database rules bind those reads to trusted institution, department, section, faculty, and role claims. The API verifies token claims, scopes records to the user's institution, and uses Firebase Admin SDK for tenant data. The Python AI service is private to the backend.

Timetable creation is deterministic: Gemini may interpret a natural-language request into catalog IDs for a user to review, while Google OR-Tools CP-SAT creates the candidate schedule. The service independently validates each result. The backend only stores valid results as drafts; a separate approver and authorized publisher move a version through the lifecycle.

## Firebase project

The configured project is `timetable-5230e`. This repo uses Firebase Authentication and **Firebase Realtime Database**. The RTDB URL is region-specific, so copy its exact URL from Firebase Console rather than deriving it from the project ID. The console should have Email/Password sign-in enabled. Create a Realtime Database instance before starting the API.

`database.rules.json` denies client writes and grants narrow reads only to the published timetable mirrors. The Java Admin SDK bypasses database rules, so the API verifies each Firebase ID token, requires trusted `institutionId` and `role` custom claims, scopes every data path beneath that institution, and gates edits and lifecycle operations by role. Never copy service-account credentials or `AI_SERVICE_API_KEY` into the browser app.

Create a local env file from `.env.example` and provide `FIREBASE_DATABASE_URL`. The Spring API uses Application Default Credentials; for local development, run `gcloud auth application-default login` before starting it. For a deployed service, assign a service identity with access to the project and its Realtime Database. If you need a local service-account file, set `GOOGLE_APPLICATION_CREDENTIALS` in your shell to a file kept outside the repository; the example env file intentionally leaves this optional variable unset.

Create Firebase Auth users through a trusted admin workflow, then set custom claims from a trusted Admin SDK environment. For example:

```java
FirebaseAuth.getInstance().setCustomUserClaims(uid, Map.of(
    "institutionId", "timetable-5230e",
    "role", "SUPER_ADMIN"
));
```

Supported roles are `SUPER_ADMIN`, `DEPARTMENT_ADMIN`, `TIMETABLE_INCHARGE`, `ACADEMIC_APPROVER`, `FACULTY`, and `STUDENT_VIEWER`. Never let a user set their own role or institution claim.

## Realtime Database shape

Every record lives under `/institutions/{institutionId}`. The API reads common reference data from `departments`, `academicTerms`, `faculty`, `subjects`, `sections`, and `rooms`. Solver-ready, administrator-approved inputs are stored at `/institutions/{institutionId}/schedulingInputs/{termId}` and follow the AI service `SchedulingProblem` schema:

```json
{
  "time_slots": [{"id":"mon-1","day":"MON","period":1,"is_teaching_period":true}],
  "sections": [{"id":"cse-a","name":"CSE A","student_count":48}],
  "faculty": [{"id":"fac-1","name":"Dr Rao","department_id":"cse","qualified_subject_ids":["dbms"],"unavailable_slot_ids":[],"preferred_slot_ids":[],"max_weekly_periods":18,"active":true}],
  "rooms": [{"id":"room-1","name":"A-101","capacity":60,"room_type":"CLASSROOM","equipment":[],"unavailable_slot_ids":[]}],
  "requirements": [{"id":"req-1","subject_id":"dbms","subject_name":"Database Systems","section_id":"cse-a","sessions_per_week":3,"faculty_ids":["fac-1"],"room_type":"CLASSROOM","required_equipment":[],"periods_per_session":1,"preferred_slot_ids":[],"excluded_slot_ids":[]}]
}
```

The API stores interpretations under `interpretedRequirements`, asynchronous solver jobs under `generationJobs`, versioned draft records under `timetableDrafts`, immutable published snapshots and an active pointer under `publishedTimetables`, and audit records under `auditLogs`. Policy documents can be stored under `policyDocuments/{documentId}` with `title` and a `chunks` map containing `id`, `text`, `page`, and `section` fields. Policy answers retrieve text-matching chunks and return only citations mapped from those chunks.

## Run locally

1. From `frontend/web`, install dependencies with `pnpm install`, then copy `frontend/web/.env.example` to `frontend/web/.env.local` and add the Web App config from Firebase project settings, including the exact Realtime Database URL shown in the Firebase console.
2. Copy the repository root `.env.example` to `.env` and `ai-service/.env.example` to `ai-service/.env`. Set the exact `FIREBASE_DATABASE_URL` in the root file. Create the shared backend key with `openssl rand -base64 32` and set it as `AI_SERVICE_API_KEY` in both files.
3. Add the Gemini key to `ai-service/.env` for requirement parsing and policy summaries.
4. Run the AI service from `ai-service`: `uv sync && uv run uvicorn app.main:app --reload --port 8000`.
5. Run the Spring API from `backend`, loading the root env first: `set -a && source ../.env && set +a && mvn spring-boot:run`.
6. Run Next.js from `frontend/web` with `pnpm dev`.

For mobile calendar subscriptions, set `CALENDAR_PUBLIC_BASE_URL` in the repository root `.env` to the public HTTPS origin of the Spring API (for example `https://api.example.edu`) before starting the backend. The web page creates private, revocable `.ics` feeds from published timetables. Student feeds contain their assigned section, faculty feeds contain their mapped classes, department-admin feeds contain their department, and timetable-wide roles receive the institution schedule. Local `localhost` feed links work only on the development computer and are not useful on a phone. On iOS, subscribe by opening the link or pasting it into Calendar's subscription flow. For Google Calendar, add the URL using its web interface; the subscribed calendar then syncs to the Android app. Feed links are bearer secrets: anyone who has one can view that account's published timetable until the owner revokes the link.

Firebase emulators are configured for Auth on port 9099 and RTDB on port 9000. Start them from the repository root with `npx -y firebase-tools@latest emulators:start --only auth,database`. For the Spring Admin SDK to use the RTDB emulator, set `FIREBASE_DATABASE_EMULATOR_HOST=127.0.0.1:9000` in the shell before starting the API and point `FIREBASE_DATABASE_URL` at the emulator database URL. For the web app, set `NEXT_PUBLIC_USE_FIREBASE_EMULATORS=true` in `frontend/web/.env.local` to use the Auth emulator. Never set the emulator flag in production.

Deploy the scoped live-timetable read rules before enabling the web Realtime Database listener: `npx -y firebase-tools@latest deploy --only database --project timetable-5230e`. All client writes remain denied; catalog edits and timetable publishing go through the authenticated backend.

## API flow

- `GET /api/v1/dashboard` returns institution-scoped counts, academic terms, and published timetable pointers.
- `POST /api/v1/ai/parse-requirements` reads the approved term catalog, asks Gemini to interpret a request, validates every returned ID against that catalog, and stores the result as `PENDING_REVIEW`.
- `POST /api/v1/timetables/generate` requires the reviewed interpretation ID and queues a job. `GET /api/v1/timetable-jobs/{id}` reports progress; the result is stored as a draft only when the CP-SAT solver and independent validator both pass.
- `POST /api/v1/timetables/{draftId}/approve` and `/publish` enforce separate role-gated lifecycle transitions and preserve published snapshots.
- `POST /api/v1/substitutions/recommend` filters unqualified, unavailable, and over-capacity candidates before applying the 40/20/15/15/10 default score.
- `POST /api/v1/absences` records an absence against a published class; `POST /api/v1/substitutions/assign` revalidates eligibility and workload, assigns approved coverage, updates the realtime mirrors, and records the audit event.
- `POST /api/v1/ai/policies/query` searches institution-scoped policy chunks and returns grounded answers with source references. This first pass uses lexical retrieval; policy document upload, PDF/DOCX extraction, vector embeddings, and richer ranking are not included yet.
- `GET/POST /api/v1/calendar/feeds` lists and creates private read-only calendar subscriptions; `DELETE /api/v1/calendar/feeds/{feedId}` revokes one; the `.ics` feed is scoped to the subscriber's current role and uses published timetable versions.
- `GET /api/v1/attendance/sessions` and `POST /api/v1/attendance/sync` support faculty attendance for mapped lectures. The web app keeps the day's roster and queued marks in IndexedDB for offline capture, then syncs after reconnecting.
- `GET /api/v1/student/portal` returns a student's own section timetable, profile, and available course/syllabus data for the year calendar.

## Current implementation boundary

This is an integrated MVP foundation, not a deployed production service. Academic records and solver inputs can be managed in the admin workspace and stored in RTDB. Published timetable views update live; faculty can report absences, managers can review ranked candidates and assign cover, and the backend mirrors the approved substitution to authorized dashboards. Cancellation and reassignment workflows, an absence queue, PDF/DOCX ingestion, embeddings, and deployment automation remain planned. The policy library currently supports lexical retrieval over pre-ingested text chunks. Do not expose the Admin SDK or deploy permissive RTDB rules. Review the role grants, AI provider settings, and institution-specific policies before production use.
