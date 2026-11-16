import { LIMITS, type MediaSourceReport } from "./contract.js";

/** HLS / DASH / direct file, from URL path or MIME type. Null = not media. */
export function classify(url: string, mime?: string | null): "HLS" | "DASH" | "FILE" | null {
  let path: string;
  try {
    const u = new URL(url);
    if (u.protocol !== "http:" && u.protocol !== "https:") return null;
    path = u.pathname.toLowerCase();
  } catch {
    return null;
  }
  const m = (mime ?? "").toLowerCase();
  if (path.endsWith(".m3u8") || m.includes("mpegurl")) return "HLS";
  if (path.endsWith(".mpd") || m.includes("dash+xml")) return "DASH";
  if (m.startsWith("video/") || m.startsWith("audio/")) return "FILE";
  if (/\.(mp4|webm|m4v|mov|mkv|mp3|m4a|ogg|oga|opus|flac|wav)$/.test(path)) return "FILE";
  return null;
}

/** Same resource regardless of fragment / letter case of scheme and host. */
export function mediaKey(url: string): string {
  try {
    const u = new URL(url);
    u.hash = "";
    return u.toString();
  } catch {
    return url;
  }
}

/** Ignore HLS/DASH segments and byte-range chunks (the manifest is what matters). */
export function isSegment(url: string): boolean {
  try {
    const p = new URL(url).pathname.toLowerCase();
    return /\.(ts|m4s|aac|cmfv|cmfa)$/.test(p) || /\/(seg|segment|chunk|frag)[-_]?\d+/.test(p);
  } catch {
    return true;
  }
}

/** Merge reports for one tab: keep the first-seen order, fill in metadata learned later, cap the size. */
export function mergeSources(existing: MediaSourceReport[], incoming: MediaSourceReport[]): MediaSourceReport[] {
  const out = new Map<string, MediaSourceReport>();
  for (const s of existing) out.set(mediaKey(s.url), s);
  for (const s of incoming) {
    if (s.url.length > LIMITS.maxUrl) continue;
    const key = mediaKey(s.url);
    const prev = out.get(key);
    out.set(key, prev ? { ...prev, ...definedOnly(s) } : s);
  }
  return [...out.values()].slice(0, LIMITS.maxSources);
}

function definedOnly<T extends object>(o: T): Partial<T> {
  return Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined && v !== null)) as Partial<T>;
}

export function hostBlocked(hostname: string, blocked: string[]): boolean {
  const h = hostname.toLowerCase();
  return blocked.some((b) => {
    const x = b.trim().toLowerCase();
    return x !== "" && (h === x || h.endsWith("." + x));
  });
}

export function formatDuration(sec?: number): string {
  if (!sec || !isFinite(sec)) return "";
  const s = Math.round(sec);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${r}` : `${m}:${r}`;
}
