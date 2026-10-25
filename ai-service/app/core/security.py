import hmac

from fastapi import Header, HTTPException, status

from app.core.config import settings


async def require_backend_key(x_ai_service_key: str | None = Header(default=None)) -> None:
    expected = settings.ai_service_api_key
    if not expected or not x_ai_service_key or not hmac.compare_digest(expected, x_ai_service_key):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Backend service authentication required")
