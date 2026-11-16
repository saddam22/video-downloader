import type { HostResponse, MediaSourceReport, SourceView } from "./contract.js";
import { classify, formatDuration } from "./media.js";

const api: typeof chrome = (globalThis as any).browser ?? chrome;
const $ = <T extends HTMLElement>(sel: string) => document.querySelector(sel) as T;
const SITES = { origins: ["http://*/*", "https://*/*"] };

function el<K extends keyof HTMLElementTagNameMap>(tag: K, cls?: string, text?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text; // never innerHTML with page data
  return e;
}

/** The page tab: from ?tab= when opened as a window (floating notice), else the active tab. */
async function activeTab() {
  const fromQuery = Number(new URLSearchParams(location.search).get("tab"));
  if (Number.isInteger(fromQuery) && fromQuery > 0) return api.tabs.get(fromQuery);
  const [t] = await api.tabs.query({ active: true, currentWindow: true });
  return t;
}

async function send<T>(msg: unknown): Promise<T> {
  return (await api.runtime.sendMessage(msg)) as T;
}

function setStatus(text: string, tone: "ok" | "warn" | "err") {
  const s = $("#status");
  s.textContent = text;
  s.dataset.tone = tone;
}

async function refreshStatus() {
  const r = await send<{ config: any; status: HostResponse }>({ kind: "host-status" });
  if (r.status?.error) {
    setStatus(r.status.error.message, "err");
    return null;
  }
  if (!r.config?.enabled) setStatus("Integration with this browser is turned off in the app", "warn");
  else if (!r.config.detectionAllowedByPlan) setStatus("Connected · video detection needs a plan that includes it", "warn");
  else setStatus("Connected to WAN Download Manager", "ok");
  return r.config;
}

async function scanActiveTab() {
  const tab = await activeTab();
  if (!tab?.id) return;
  try {
    await api.scripting.executeScript({ target: { tabId: tab.id, allFrames: true }, files: ["content.js"] });
  } catch {
    // restricted page (store, settings): nothing to scan
  }
  setTimeout(render, 1200);
}

async function render() {
  const tab = await activeTab();
  const list = $("#list");
  list.replaceChildren();
  const data = tab?.id ? await send<{ pageUrl: string; title: string; sources: MediaSourceReport[] } | null>({ kind: "get-tab-media", tabId: tab.id }) : null;
  const sources = (data?.sources ?? []).filter((s) => !s.url.startsWith("blob:"));
  const streamingOnly = (data?.sources ?? []).length > 0 && sources.length === 0;
  if (sources.length === 0) {
    const empty = el("div", "empty");
    empty.append(el("strong", "", "No videos found on this page yet"),
      el("p", "", streamingOnly
        ? "This page plays media from a stream buffer. If it uses HLS/DASH, enable automatic detection and reload the page."
        : "Start playing the video, or scan the page."));
    list.append(empty);
    return;
  }
  for (const s of sources) list.append(sourceCard(tab!.id!, data!.title, s));
}

function sourceCard(tabId: number, title: string, s: MediaSourceReport): HTMLElement {
  const card = el("article", "card");
  const head = el("div", "card-head");
  const thumb = el("div", "thumb");
  if (s.poster) {
    const img = el("img");
    img.src = s.poster;
    img.alt = "";
    img.referrerPolicy = "no-referrer";
    thumb.append(img);
  }
  const dur = formatDuration(s.durationSec);
  if (dur) thumb.append(el("span", "dur", dur));
  const meta = el("div", "meta");
  meta.append(el("strong", "title", title || "Untitled video"));
  const kind = classify(s.url, s.mimeType) ?? "FILE";
  meta.append(el("span", "sub", `${kind}${s.height ? ` · playing at ${s.height}p` : ""}`));
  head.append(thumb, meta);
  const action = el("button", "btn primary", "Get formats");
  const formats = el("div", "formats");
  action.addEventListener("click", async () => {
    action.disabled = true;
    action.textContent = "Checking…";
    const res = await send<HostResponse<{ items: SourceView[] }>>({ kind: "verify", tabId, url: s.url });
    action.remove();
    if (res.error) {
      formats.append(el("p", "note err", res.error.message));
      return;
    }
    const v = res.result?.items?.[0];
    if (!v) {
      formats.append(el("p", "note", "This source could not be verified."));
      return;
    }
    if (v.note) formats.append(el("p", v.protectedContent ? "note err" : "note", v.note));
    for (const f of v.formats) {
      const row = el("div", "format");
      row.append(el("span", "", f.label));
      const b = el("button", "btn", "Download");
      b.addEventListener("click", async () => {
        b.disabled = true;
        const r = await send<HostResponse>({ kind: "download-format", sourceId: v.id, formatId: f.id });
        b.textContent = r.error ? "Failed" : "Added ✓";
        if (r.error) row.append(el("p", "note err", r.error.message));
      });
      row.append(b);
      formats.append(row);
    }
  });
  card.append(head, action, formats);
  return card;
}

async function setupPrefs() {
  const p = { popupNotice: true, handoff: false, disabledSites: [] as string[], ...((await api.storage.local.get("prefs")).prefs ?? {}) };
  const auto = $("#auto") as HTMLInputElement;
  const notice = $("#notice") as HTMLInputElement;
  const handoff = $("#handoff") as HTMLInputElement;
  const site = $("#site") as HTMLInputElement;
  auto.checked = await api.permissions.contains(SITES);
  notice.checked = p.popupNotice;
  handoff.checked = p.handoff && (await api.permissions.contains({ permissions: ["downloads"] }));
  const tab = await activeTab();
  const host = tab?.url ? new URL(tab.url).hostname : "";
  site.checked = !p.disabledSites.includes(host);
  $("#site-label").textContent = host ? `Detect on ${host}` : "Detect on this site";

  const save = () => api.storage.local.set({ prefs: p });
  auto.addEventListener("change", async () => {
    if (auto.checked) {
      auto.checked = await api.permissions.request(SITES); // must run in the click handler
      if (auto.checked) {
        await api.scripting.registerContentScripts([{ id: "wan-detect", js: ["content.js"], matches: ["http://*/*", "https://*/*"], runAt: "document_idle", allFrames: true }]).catch(() => undefined);
      }
    } else {
      await api.scripting.unregisterContentScripts({ ids: ["wan-detect"] }).catch(() => undefined);
      await api.permissions.remove(SITES);
    }
  });
  notice.addEventListener("change", () => {
    p.popupNotice = notice.checked;
    save();
  });
  handoff.addEventListener("change", async () => {
    if (handoff.checked) handoff.checked = await api.permissions.request({ permissions: ["downloads"] });
    p.handoff = handoff.checked;
    save();
  });
  site.addEventListener("change", () => {
    p.disabledSites = site.checked ? p.disabledSites.filter((x: string) => x !== host) : [...new Set([...p.disabledSites, host])];
    save();
  });
}

document.addEventListener("DOMContentLoaded", async () => {
  $("#scan").addEventListener("click", scanActiveTab);
  $("#link-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const input = $("#link") as HTMLInputElement;
    const tab = await activeTab();
    const r = await send<HostResponse>({ kind: "download-url", url: input.value.trim(), pageUrl: tab?.url });
    $("#link-result").textContent = r.error ? r.error.message : "Added to WAN Download Manager ✓";
    if (!r.error) input.value = "";
  });
  await setupPrefs();
  await refreshStatus();
  await render();
});
