import { getApp, getApps, initializeApp } from "firebase/app";
import { connectAuthEmulator, getAuth, type Auth } from "firebase/auth";

let authInstance: Auth | undefined;

export function getFirebaseAuth() {
  if (authInstance) return authInstance;
  const config = {
    apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY,
    authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN,
    projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID,
    appId: process.env.NEXT_PUBLIC_FIREBASE_APP_ID,
  };
  if (!config.apiKey || !config.authDomain || !config.projectId || !config.appId) {
    throw new Error("Firebase web configuration is missing. Set NEXT_PUBLIC_FIREBASE_* in frontend/web/.env.local.");
  }
  const app = getApps().length ? getApp() : initializeApp(config);
  authInstance = getAuth(app);
  if (process.env.NEXT_PUBLIC_USE_FIREBASE_EMULATORS === "true") {
    connectAuthEmulator(authInstance, "http://127.0.0.1:9099", { disableWarnings: true });
  }
  return authInstance;
}
