/*
 * Content script (classic script — no imports at runtime). Finds <video>/<audio> elements and
 * reports what the browser itself decoded: src/currentSrc, <source> URLs, MIME type, videoWidth,
 * videoHeight, duration, poster. It never reads cookies, storage, forms or page text.
 */
import type { MediaSourceReport } from "./contract.js";

(() => {
  const api: typeof chrome = (globalThis as any).browser ?? chrome;
  if ((globalThis as any).__wanDmInjected) return;
  (globalThis as any).__wanDmInjected = true;

  let timer: number | undefined;
  let lastSignature = "";
  let overlayShown = false;

  function absolute(u: string | null | undefined): string | null {
    if (!u) return null;
    try {
      const x = new URL(u, document.baseURI);
      return x.protocol === "http:" || x.protocol === "https:" || x.protocol === "blob:" ? x.toString() : null;
    } catch {
      return null;
    }
  }

  function collect(): MediaSourceReport[] {
    const out: MediaSourceReport[] = [];
    document.querySelectorAll<HTMLMediaElement>("video, audio").forEach((el) => {
      const isVideo = el instanceof HTMLVideoElement;
      const meta: Partial<MediaSourceReport> = {};
      if (isVideo && el.videoWidth > 0) {
        meta.width = el.videoWidth;
        meta.height = el.videoHeight;
      }
      if (isFinite(el.duration) && el.duration > 0) meta.durationSec = Math.round(el.duration * 10) / 10;
      const poster = isVideo ? absolute(el.poster) : null;
      if (poster && poster.startsWith("https://")) meta.poster = poster;
      const urls = new Map<string, string | undefined>();
      const cur = absolute(el.currentSrc || el.src);
      if (cur) urls.set(cur, undefined);
      el.querySelectorAll("source").forEach((s) => {
        const u = absolute(s.src);
        if (u) urls.set(u, s.type || undefined);
      });
      urls.forEach((mime, url) => out.push({ url, mimeType: mime, ...meta }));
    });
    return out.slice(0, 50);
  }

  function report() {
    const sources = collect();
    const sig = JSON.stringify(sources);
    if (sources.length === 0 || sig === lastSignature) return;
    lastSignature = sig;
    api.runtime.sendMessage({ kind: "page-media", title: document.title.slice(0, 500), sources }).then(
      (res: { showOverlay?: boolean; count?: number } | undefined) => {
        if (res?.showOverlay && res.count && res.count > 0) showOverlay(res.count);
      },
      () => undefined,
    );
  }

  function schedule() {
    if (timer !== undefined) clearTimeout(timer);
    timer = window.setTimeout(report, 800);
  }

  /** Small floating notice in a closed shadow root (page CSS/JS cannot restyle or read it). */
  function showOverlay(count: number) {
    if (overlayShown) return;
    overlayShown = true;
    const host = document.createElement("div");
    host.style.cssText = "position:fixed;right:16px;bottom:16px;z-index:2147483647;";
    const root = host.attachShadow({ mode: "closed" });
    const label = count === 1 ? "1 video detected" : `${count} videos detected`;
    root.innerHTML = `
      <style>
        .pill{font:600 13px/1.2 "Segoe UI",system-ui,sans-serif;display:flex;align-items:center;gap:10px;padding:10px 12px 10px 14px;
          border-radius:14px;background:linear-gradient(135deg,#0a1640,#121b2f);color:#e7edf8;border:1px solid #31415f;
          box-shadow:0 18px 40px -16px rgba(5,13,43,.7)}
        .dot{width:22px;height:22px;border-radius:50%;background:linear-gradient(135deg,#3b7bff,#6d28d9);display:grid;place-items:center;font-size:12px}
        button{all:unset;cursor:pointer;padding:6px 10px;border-radius:9px;background:#1d5bf5;color:#fff;font-weight:600}
        button:focus-visible{outline:2px solid #8fb3ff;outline-offset:2px}
        .x{background:transparent;color:#8d9bb5;padding:6px}
      </style>
      <div class="pill" role="status"><span class="dot">⤓</span><span></span>
        <button class="open" type="button">Open grabber</button><button class="x" type="button" aria-label="Dismiss">✕</button></div>`;
    (root.querySelector(".pill span:nth-child(2)") as HTMLElement).textContent = `WAN · ${label}`;
    root.querySelector(".open")!.addEventListener("click", () => {
      api.runtime.sendMessage({ kind: "open-grabber" }).catch(() => undefined);
      host.remove();
    });
    root.querySelector(".x")!.addEventListener("click", () => host.remove());
    document.documentElement.appendChild(host);
    setTimeout(() => host.remove(), 12000);
  }

  document.addEventListener("loadedmetadata", schedule, true);
  document.addEventListener("play", schedule, true);
  new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  schedule();
})();
