"use client";

import { useCallback, useEffect, useState } from "react";
import type { User } from "firebase/auth";
import { backendRequest } from "../lib/backend";
import styles from "./CalendarSubscriptions.module.css";

type CalendarFeed = { id: string; name: string; createdAt: string; url: string; webcalUrl: string };

export default function CalendarSubscriptions({ user }: { user: User }) {
  const [feeds, setFeeds] = useState<CalendarFeed[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const loadFeeds = useCallback(async () => {
    try {
      const next = await backendRequest<CalendarFeed[]>(user, "/api/v1/calendar/feeds");
      setFeeds(next || []);
      setError("");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Calendar subscriptions could not be loaded.");
    } finally { setLoading(false); }
  }, [user]);

  useEffect(() => { void loadFeeds(); }, [loadFeeds]);

  async function createFeed() {
    setBusy(true); setError(""); setNotice("");
    try {
      const created = await backendRequest<CalendarFeed>(user, "/api/v1/calendar/feeds", { method: "POST" });
      setFeeds((current) => [created, ...current]);
      setNotice("Calendar link created. Subscribe with the link below; timetable updates will sync automatically.");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not create your calendar link.");
    } finally { setBusy(false); }
  }

  async function revoke(feed: CalendarFeed) {
    setBusy(true); setError(""); setNotice("");
    try {
      await backendRequest(user, `/api/v1/calendar/feeds/${encodeURIComponent(feed.id)}`, { method: "DELETE" });
      setFeeds((current) => current.filter((item) => item.id !== feed.id));
      setNotice("Calendar link revoked.");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not revoke the calendar link.");
    } finally { setBusy(false); }
  }

  async function copy(url: string) {
    try {
      await navigator.clipboard.writeText(url);
      setNotice("Calendar URL copied. Paste it into your calendar subscription settings.");
    } catch {
      setNotice("Copy is unavailable here. Select the calendar URL and copy it manually.");
    }
  }

  const localUrl = feeds.some((feed) => /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?\//i.test(feed.url));
  const insecureUrl = feeds.some((feed) => /^http:\/\//i.test(feed.url));

  return <section className={styles.panel} id="calendar-sync">
    <div className={styles.heading}>
      <div><p className={styles.eyebrow}>YOUR DEVICES</p><h2>Sync timetable to calendar</h2><p>Create a private subscription for Apple Calendar, Google Calendar, or another calendar app. Published timetable changes will appear when the calendar refreshes.</p></div>
      <button className={styles.createButton} type="button" onClick={() => void createFeed()} disabled={busy || feeds.length >= 10}>{busy ? "Working…" : feeds.length >= 10 ? "10 links created" : "Create calendar link"}</button>
    </div>
    {error && <div className={styles.error} role="alert">{error}<button type="button" onClick={() => void loadFeeds()}>Retry</button></div>}
    {notice && <div className={styles.notice} role="status">{notice}<button type="button" aria-label="Dismiss message" onClick={() => setNotice("")}>Dismiss</button></div>}
    {(localUrl || insecureUrl) && feeds.length > 0 && <div className={styles.warning}>
      {localUrl ? "This link points to localhost and works only on this computer. Set CALENDAR_PUBLIC_BASE_URL to your publicly reachable HTTPS API address before subscribing from a phone." : "Calendar apps need a publicly reachable HTTPS API URL for reliable mobile subscriptions. Set CALENDAR_PUBLIC_BASE_URL on the backend before sharing this link."}
    </div>}
    <div className={styles.instructions}>
      <article><strong>iPhone / iPad</strong><p>Tap “Subscribe on Apple device” on your phone, or open Calendar → Calendars → Add Calendar → Add Subscription Calendar and paste the URL.</p></article>
      <article><strong>Android / Google Calendar</strong><p>Copy the URL, open Google Calendar in a browser, choose Other calendars → + → From URL, then add it. The subscribed calendar will sync into the Android app.</p></article>
    </div>
    {loading ? <p className={styles.empty}>Loading calendar links…</p> : feeds.length === 0 ? <p className={styles.empty}>No calendar links yet. Create one to add your timetable to a phone or desktop calendar.</p> : <div className={styles.feedList}>
      {feeds.map((feed) => <article className={styles.feed} key={feed.id}>
        <div className={styles.feedTitle}><div><strong>{feed.name}</strong><small>Created {feed.createdAt ? new Date(feed.createdAt).toLocaleDateString() : "recently"} · read-only schedule</small></div><button type="button" className={styles.revoke} disabled={busy} onClick={() => void revoke(feed)}>Revoke</button></div>
        <label className={styles.urlLabel} htmlFor={`feed-url-${feed.id}`}>Private calendar subscription URL</label>
        <div className={styles.urlRow}><input id={`feed-url-${feed.id}`} value={feed.url} readOnly onFocus={(event) => event.currentTarget.select()} /><button type="button" onClick={() => void copy(feed.url)}>Copy URL</button></div>
        <div className={styles.feedActions}><a href={feed.webcalUrl}>Subscribe on Apple device</a><a href="https://calendar.google.com/calendar/u/0/r/settings/addbyurl" target="_blank" rel="noreferrer">Open Google Calendar</a></div>
      </article>)}
    </div>}
    <p className={styles.privacy}>Anyone with a private link can view that account’s published timetable. Keep the link private; revoke it here if it is shared by mistake. It does not grant access to sign in or edit schedules.</p>
  </section>;
}
