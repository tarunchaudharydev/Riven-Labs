import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Riven Labs | Smart Timetable",
  description: "AI-assisted timetable planning and faculty substitution management.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
