import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "GLA University - Mathura",
    short_name: "GLA University",
    description: "Student services, class timetables, and faculty attendance for GLA University.",
    start_url: "/",
    display: "standalone",
    background_color: "#f7f8f6",
    theme_color: "#1b6c4e",
  };
}
