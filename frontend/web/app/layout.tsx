import type { Metadata } from "next";
import ServiceWorkerRegistration from "../components/ServiceWorkerRegistration";
import "./globals.css";

export const metadata: Metadata = {
  title: "GLA University - Mathura | Academic Portal",
  description: "Academic timetables, offline faculty attendance, student profiles, and syllabus for GLA University - Mathura.",
  manifest: "/manifest.webmanifest",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body><ServiceWorkerRegistration />{children}</body></html>;
}
