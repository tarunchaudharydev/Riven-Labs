"use client";

import { useEffect, useMemo, useState } from "react";
import type { User } from "firebase/auth";
import { backendRequest } from "../lib/backend";
import styles from "./RoomAvailabilityFinder.module.css";

type RoomSlot = { id: string; day: string; period: number; label?: string; start_time?: string; end_time?: string };
type Room = {
  id: string;
  name: string;
  code?: string;
  capacity?: number;
  room_type?: string;
  equipment?: string[];
  department_name?: string;
  department_code?: string;
  department_block_no?: string;
  available: boolean;
  reason?: string;
};
type RoomResponse = { termId: string; timeSlots?: RoomSlot[]; timeSlot?: RoomSlot; rooms?: Room[]; published?: boolean };

function slotLabel(slot: RoomSlot) {
  const time = [slot.start_time, slot.end_time].filter(Boolean).join("–");
  const period = slot.label || "Period " + slot.period;
  return slot.day + " · " + period + (time ? " · " + time : "");
}

export default function RoomAvailabilityFinder({ user, termId }: { user: User; termId: string }) {
  const [slots, setSlots] = useState<RoomSlot[]>([]);
  const [slotsTerm, setSlotsTerm] = useState("");
  const [slotId, setSlotId] = useState("");
  const [response, setResponse] = useState<RoomResponse | null>(null);
  const [loadingSlots, setLoadingSlots] = useState(false);
  const [loadingRooms, setLoadingRooms] = useState(false);
  const [showAllRooms, setShowAllRooms] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!termId) return;
    let cancelled = false;
    setSlots([]);
    setSlotsTerm("");
    setSlotId("");
    setResponse(null);
    setError("");
    setLoadingSlots(true);
    backendRequest<RoomResponse>(user, "/api/v1/rooms/availability?termId=" + encodeURIComponent(termId))
      .then((result) => {
        if (cancelled) return;
        const nextSlots = result.timeSlots || [];
        setSlots(nextSlots);
        setSlotsTerm(termId);
        setSlotId(nextSlots[0]?.id || "");
        setLoadingSlots(false);
      })
      .catch((caught) => {
        if (cancelled) return;
        setError(caught instanceof Error ? caught.message : "Could not load the term time slots.");
        setLoadingSlots(false);
      });
    return () => { cancelled = true; };
  }, [termId, user]);

  useEffect(() => {
    if (!termId || slotsTerm !== termId || !slotId || !slots.some((slot) => slot.id === slotId)) return;
    let cancelled = false;
    setLoadingRooms(true);
    setError("");
    backendRequest<RoomResponse>(user, "/api/v1/rooms/availability?termId=" + encodeURIComponent(termId) + "&slotId=" + encodeURIComponent(slotId))
      .then((result) => {
        if (cancelled) return;
        setResponse(result);
        setLoadingRooms(false);
      })
      .catch((caught) => {
        if (cancelled) return;
        setError(caught instanceof Error ? caught.message : "Could not check room availability.");
        setLoadingRooms(false);
      });
    return () => { cancelled = true; };
  }, [termId, slotId, slots, slotsTerm, user]);

  const rooms = response?.rooms || [];
  const availableCount = rooms.filter((room) => room.available).length;
  const visibleRooms = useMemo(() => showAllRooms ? rooms : rooms.filter((room) => room.available), [rooms, showAllRooms]);
  const currentSlot = slots.find((slot) => slot.id === slotId);

  return <section className={styles.finder} aria-labelledby="room-finder-title">
    <div className={styles.heading}>
      <div><p className={styles.eyebrow}>ROOM AVAILABILITY</p><h4 id="room-finder-title">Find an open classroom or lab</h4><p>Availability follows the active published timetable and each room’s blocked periods.</p></div>
      <span className={styles.count}>{availableCount} of {rooms.length} open</span>
    </div>
    <div className={styles.controls}>
      <label><span>Academic time</span><select value={slotId} onChange={(event) => setSlotId(event.target.value)} disabled={loadingSlots || !slots.length}><option value="">{loadingSlots ? "Loading periods…" : "Select a period"}</option>{slots.map((slot) => <option key={slot.id} value={slot.id}>{slotLabel(slot)}</option>)}</select></label>
      <button type="button" onClick={() => setShowAllRooms((current) => !current)} disabled={!rooms.length}>{showAllRooms ? "Show open rooms only" : "Show all rooms"}</button>
    </div>
    {error && <p className={styles.error} role="alert">{error}</p>}
    {loadingRooms ? <p className={styles.empty}>Checking room use for this period…</p>
      : !slots.length && !loadingSlots ? <p className={styles.empty}>No teaching periods are configured for this term yet.</p>
      : !rooms.length && response ? <p className={styles.empty}>No classrooms or labs are in the room catalog.</p>
      : visibleRooms.length ? <div className={styles.tableScroll}><table><thead><tr><th>Classroom / lab</th><th>Department / block</th><th>Capacity</th><th>Equipment</th><th>Status</th></tr></thead><tbody>{visibleRooms.map((room) => <tr key={room.id}><td><strong>{room.code || room.name}</strong><small>{room.name}{room.room_type ? " · " + room.room_type : ""}</small></td><td>{room.department_name || "Shared"}{room.department_block_no ? <small>Block {room.department_block_no}</small> : null}</td><td>{room.capacity ?? "—"}</td><td>{room.equipment?.length ? room.equipment.join(", ") : "—"}</td><td><span className={room.available ? styles.available : styles.unavailable}>{room.available ? "Available" : "Occupied"}</span>{room.reason ? <small>{room.reason}</small> : null}</td></tr>)}</tbody></table></div>
        : <p className={styles.empty}>{showAllRooms ? "No rooms were found." : "No rooms are free for this period. Choose “Show all rooms” to see occupied spaces."}</p>}
    {currentSlot && <p className={styles.footnote}>Showing {slotLabel(currentSlot)}{response?.published ? " from the published timetable." : ". No timetable has been published yet, so only room block periods are checked."}</p>}
  </section>;
}
