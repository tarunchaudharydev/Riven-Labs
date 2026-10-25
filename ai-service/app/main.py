from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.v1.requirements import router as requirements_router
from app.api.v1.substitutions import router as substitutions_router
from app.api.v1.timetables import router as timetables_router
from app.api.v1.policies import router as policies_router
from app.core.config import settings

app = FastAPI(title="Riven Labs Timetable AI", version="0.2.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[origin.strip() for origin in settings.cors_origins.split(",") if origin.strip()],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "X-AI-Service-Key"],
)


@app.get("/health")
def health_check():
    return {"status": "healthy", "requirement_interpreter_configured": bool(settings.gemini_api_key)}


app.include_router(requirements_router, prefix="/api/v1")
app.include_router(timetables_router, prefix="/api/v1")
app.include_router(substitutions_router, prefix="/api/v1")
app.include_router(policies_router, prefix="/api/v1")
