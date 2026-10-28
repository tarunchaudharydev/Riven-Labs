export type OfflineAttendanceSession = {
  termId: string;
  requirement_id: string;
  section_id: string;
  section_name?: string;
  subject_id: string;
  subject_name?: string;
  day: string;
  period: number;
  date: string;
  sessionKey: string;
  time_start?: string;
  time_end?: string;
  attendanceSavedAt?: string;
  room_name?: string;
  room_code?: string;
  room_building?: string;
  students: { uid: string; name: string; collegeEmail: string; rollNo: string; status?: string }[];
};

export type OfflineAttendanceDraft = {
  id: string;
  ownerUid: string;
  session: OfflineAttendanceSession;
  capturedAt: string;
  students: { uid: string; status: "PRESENT" | "ABSENT" | "LATE" }[];
};

const DATABASE_NAME = "riven-offline-attendance";
const DATABASE_VERSION = 1;
const CACHE_STORE = "sessions";
const QUEUE_STORE = "queue";

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === "undefined") {
      reject(new Error("Offline attendance storage is not available in this browser."));
      return;
    }
    const request = indexedDB.open(DATABASE_NAME, DATABASE_VERSION);
    request.onupgradeneeded = () => {
      const database = request.result;
      if (!database.objectStoreNames.contains(CACHE_STORE)) database.createObjectStore(CACHE_STORE, { keyPath: "id" });
      if (!database.objectStoreNames.contains(QUEUE_STORE)) database.createObjectStore(QUEUE_STORE, { keyPath: "id" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("Could not open offline attendance storage."));
  });
}

function requestValue<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error || new Error("Offline attendance storage failed."));
  });
}

export async function cacheAttendanceSessions(ownerUid: string, date: string, sessions: OfflineAttendanceSession[]) {
  const database = await openDatabase();
  try {
    const transaction = database.transaction(CACHE_STORE, "readwrite");
    const store = transaction.objectStore(CACHE_STORE);
    for (const session of sessions) store.put({ id: `${ownerUid}:${date}:${session.sessionKey}`, ownerUid, date, session });
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error || new Error("Could not cache attendance sessions."));
      transaction.onabort = () => reject(transaction.error || new Error("Attendance session cache was interrupted."));
    });
  } finally { database.close(); }
}

export async function readCachedAttendanceSessions(ownerUid: string, date: string): Promise<OfflineAttendanceSession[]> {
  const database = await openDatabase();
  try {
    const transaction = database.transaction(CACHE_STORE, "readonly");
    const records = await requestValue<Array<{ ownerUid: string; date: string; session: OfflineAttendanceSession }>>(transaction.objectStore(CACHE_STORE).getAll());
    return records.filter((record) => record.ownerUid === ownerUid && record.date === date).map((record) => record.session);
  } finally { database.close(); }
}

export async function queueAttendanceDraft(draft: OfflineAttendanceDraft) {
  const database = await openDatabase();
  try {
    const transaction = database.transaction(QUEUE_STORE, "readwrite");
    transaction.objectStore(QUEUE_STORE).put(draft);
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error || new Error("Could not save attendance for offline sync."));
      transaction.onabort = () => reject(transaction.error || new Error("Attendance could not be saved locally."));
    });
  } finally { database.close(); }
}

export async function readAttendanceQueue(ownerUid: string): Promise<OfflineAttendanceDraft[]> {
  const database = await openDatabase();
  try {
    const transaction = database.transaction(QUEUE_STORE, "readonly");
    const records = await requestValue<OfflineAttendanceDraft[]>(transaction.objectStore(QUEUE_STORE).getAll());
    return records.filter((record) => record.ownerUid === ownerUid);
  } finally { database.close(); }
}

export async function removeAttendanceDraft(id: string) {
  const database = await openDatabase();
  try {
    const transaction = database.transaction(QUEUE_STORE, "readwrite");
    transaction.objectStore(QUEUE_STORE).delete(id);
    await new Promise<void>((resolve, reject) => {
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => reject(transaction.error || new Error("Could not remove synced attendance."));
      transaction.onabort = () => reject(transaction.error || new Error("Could not remove synced attendance."));
    });
  } finally { database.close(); }
}
