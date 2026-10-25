# Timetable AI service

This FastAPI service is private to the Spring backend. It interprets scheduling requests for human review, solves approved inputs with OR-Tools CP-SAT, validates candidate schedules independently, ranks faculty substitution candidates, and retrieves institution-scoped policy chunks.

## Local setup

From this directory, copy `.env.example` to `.env`, set `AI_SERVICE_API_KEY` to the same secret configured for the Spring backend, and optionally set `GEMINI_API_KEY` for Gemini requirement interpretation and policy summaries. Then run:

```sh
uv sync
uv run uvicorn app.main:app --reload --port 8000
```

The API requires the `X-AI-Service-Key` header on service routes. Keep this key and the Gemini key server-side. The `/health` endpoint reports whether Gemini is configured and does not expose either secret.

## Solver boundary

Gemini only maps a natural-language request to catalog identifiers and editable constraints. The backend checks those identifiers against approved Realtime Database inputs and requires human review before invoking the solver. The solver enforces faculty, section, room, availability, capacity, equipment, workload, session-count, and block-length constraints. Its result is checked by a separate validator before the backend stores it as a draft.

Solver work is deterministic within a fixed OR-Tools version and input: it uses one search worker, a fixed seed, a bounded time limit, and a maximum model-option count. A timeout without a feasible assignment is returned as no solution; it is never published automatically.

The current policy retrieval is lexical over pre-ingested chunks. It does not extract PDF or DOCX files or calculate vector embeddings. Reference data, scheduling inputs, and policy chunks must be provisioned by a trusted administrator process.

Run the focused service suite with:

```sh
uv run pytest
```
