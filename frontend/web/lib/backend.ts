import type { User } from "firebase/auth";

const API_BASE = (process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080").replace(/\/$/, "");

export async function backendRequest<T>(user: User, path: string, init: RequestInit = {}): Promise<T> {
  const idToken = await user.getIdToken();
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}`, ...init.headers },
    cache: "no-store",
  });
  const raw = await response.text();
  let body: unknown = null;
  if (raw) {
    try { body = JSON.parse(raw); } catch { body = { message: raw }; }
  }
  if (!response.ok) {
    const detail = body && typeof body === "object" ? body as Record<string, unknown> : {};
    throw new Error(String(detail.detail || detail.message || detail.error || `Request failed (${response.status})`));
  }
  return body as T;
}
