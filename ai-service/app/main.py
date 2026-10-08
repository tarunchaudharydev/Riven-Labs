from fastapi import FastAPI

from app.api.v1.requirements import router as requirements_router


app = FastAPI(
    title="Smart Timetable AI Service",
    version="0.1.0",
)


@app.get("/health")
async def health_check():
    return {"status": "healthy"}


app.include_router(
    requirements_router,
    prefix="/api/v1",
)
