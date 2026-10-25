# Riven Labs Timetable

This repository is the starting point for the smart timetable and faculty substitution platform. The checked-in app was a Next.js and FastAPI starter and did not contain a Supabase integration. This change establishes Firebase as the persistence and authentication platform using Firebase SQL Connect with Cloud SQL for PostgreSQL, preserving the relational data model and vector-ready policy storage described in the project brief.

## Firebase project

The Firebase project is `timetable-5230e`. The SQL Connect service is configured as `timetable` in `asia-south1`, with a PostgreSQL database named `timetable` and Cloud SQL instance ID `timetable-sql`. SQL Connect services and their Cloud SQL instances must use the same region. If your Firebase project already has a SQL Connect service or Cloud SQL instance, update `dataconnect/dataconnect.yaml` to match those existing resource names and region before provisioning or deploying.

The root `.firebaserc` selects this project for Firebase CLI commands. Do not commit Firebase service-account keys or other secrets; none are needed for the web client configuration.

## Set up SQL Connect

Install the Firebase CLI, sign in, then run these commands from the repository root:

```sh
firebase login
firebase use timetable-5230e
firebase dataconnect:sql:setup
firebase dataconnect:sdk:generate
firebase emulators:start --only auth,dataconnect
```

The SQL setup step provisions or links the Cloud SQL for PostgreSQL instance and SQL Connect service. Review the selected region and any billing or trial prompts in Firebase before proceeding. The emulator uses a local PGLite database; it does not require production database access.

When the local schema and operations are ready, deploy them with:

```sh
firebase deploy --only dataconnect
```

## Data model and access

`dataconnect/schema/schema.gql` defines institutions, academic terms, departments, faculty, subjects, sections, rooms, availability, teaching requirements, timetable versions and entries, generation jobs, absences, substitutions, policy documents and chunks, and audit logs. Policy chunks have a 768-dimension vector column.

Connector operations are in `dataconnect/connector/`. The initial operations cover profile and reference-data reads, published student timetable reads, and selected setup and absence mutations. Tenant access is scoped using Firebase Authentication custom claims: `institutionId`, `role`, and (for student timetable reads) `sectionId`. Assign these claims only from a trusted Admin SDK process. Never accept role or institution claims from a client request.

This is a Firebase backend foundation, not a completed application migration: the repository had no Supabase implementation to port. The Next.js UI, Firebase sign-in flows, trusted account provisioning, timetable solver integration, approval lifecycle, and end-to-end tests still need to be built. Review and compile the SQL Connect schema and operations with the Firebase CLI before deploying them.
