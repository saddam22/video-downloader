/*
 * Background (Chrome/Edge service worker, Firefox event page). Keeps per-tab detected media, talks to
 * the native host, updates the toolbar badge. Detection of network-level manifests (.m3u8/.mpd) needs
 * the optional host permission the user grants in the popup; without it only the active tab is
 * scanned on request (activeTab).
 */
import {
  HOST_NAME,
  PROTOCOL_VERSION,
  type ExtensionConfig,
  type HostRequest,
  type HostResponse,
  type InternalMessage,
  type MediaSourceReport,
} from "./contract.js";
import { classify, hostBlocked, isSegment, mergeSources } from "./media.js";

const api: typeof chrome = (globalThis as any).browser ?? chrome;
const tabs = new Map<number, { pageUrl: string; title: string; sources: MediaSourceReport[] }>();
let config: ExtensionConfig | null = null;
let seq = 0;

type Prefs = { popupNotice: boolean; handoff: boolean; disabledSites: string[] };
const DEFAULT_PREFS: Prefs = { popupNotice: true, handoff: false, disabledSites: [] };

async function prefs(): Promise<Prefs> {
  const s = await api.storage.local.get("prefs");
  return { ...DEFAULT_PREFS, ...(s.prefs ?? {}) };
}

/** Every call opens a short native-messaging exchange (the host process exits when the port closes). */
async function host<T>(msg: Omit<HostRequest, "id" | "v">): Promise<HostResponse<T>> {
  const full = { ...msg, id: String(++seq), v: PROTOCOL_VERSION } as HostRequest;
  try {
    return (await api.runtime.sendNativeMessage(HOST_NAME, full)) as HostResponse<T>;
  } catch (e) {
    return { id: full.id, error: { code: "host_missing", message: "WAN Download Manager is not installed for this browser (native host not found)." } };
  }
}

async function hello(): Promise<ExtensionConfig | null> {
  const res = await host<ExtensionConfig>({ type: "hello", version: api.runtime.getManifest().version } as any);
  config = res.result ?? null;
  return config;
}

async function allowedOn(pageUrl: string): Promise<boolean> {
  let h: string;
  try {
    h = new URL(pageUrl).hostname;
  } catch {
    return false;
  }
  const p = await prefs();
  const cfg = config ?? (await hello());
  if (!cfg || !cfg.enabled || !cfg.videoDetection) return false;
  return !hostBlocked(h, [...cfg.blockedSites, ...p.disabledSites]);
}

async function addSources(tabId: number, pageUrl: string, title: string, incoming: MediaSourceReport[]): Promise<number> {
  if (!(await allowedOn(pageUrl))) return 0;
  const cur = tabs.get(tabId);
  const base = cur && cur.pageUrl === pageUrl ? cur.sources : [];
  const sources = mergeSources(base, incoming.filter((s) => !isSegment(s.url) || classify(s.url, s.mimeType) !== "FILE"));
  tabs.set(tabId, { pageUrl, title: title || cur?.title || "", sources });
  const n = sources.filter((s) => !s.url.startsWith("blob:")).length;
  await api.action.setBadgeBackgroundColor({ color: "#1d5bf5", tabId });
  await api.action.setBadgeText({ text: n > 0 ? String(n) : "", tabId });
  return n;
}

api.runtime.onInstalled.addListener(() => {
  api.contextMenus.create({ id: "wan-download-link", title: "Download with WAN Download Manager", contexts: ["link", "video", "audio"] });
});

api.contextMenus.onClicked.addListener(async (info, tab) => {
  const url = info.srcUrl ?? info.linkUrl;
  if (!url) return;
  await host({ type: "download", url, pageUrl: tab?.url } as any);
});

api.tabs.onRemoved.addListener((id) => tabs.delete(id));
api.tabs.onUpdated.addListener((id, change) => {
  if (change.url) {
    tabs.delete(id);
    api.action.setBadgeText({ text: "", tabId: id }).catch(() => undefined);
  }
});

// Network-level detection: manifests and media responses (needs the optional host permission).
api.webRequest?.onHeadersReceived.addListener(
  (d) => {
    if (d.tabId < 0 || d.statusCode >= 400) return;
    const type = d.responseHeaders?.find((h) => h.name.toLowerCase() === "content-type")?.value?.split(";")[0];
    const kind = classify(d.url, type);
    if (!kind || (kind === "FILE" && isSegment(d.url))) return;
    if (kind === "FILE" && !(type ?? "").match(/^(video|audio)\//)) return;
    api.tabs.get(d.tabId).then(
      (t) => {
        if (t.url) void addSources(d.tabId, t.url, t.title ?? "", [{ url: d.url, mimeType: type }]);
      },
      () => undefined,
    );
  },
  { urls: ["http://*/*", "https://*/*"], types: ["xmlhttprequest", "media", "other"] },
  ["responseHeaders"],
);

// Optional hand-off of browser downloads (off by default; needs the optional "downloads" permission).
api.downloads?.onCreated.addListener(async (item) => {
  const p = await prefs();
  if (!p.handoff || !/^https?:/.test(item.finalUrl || item.url)) return;
  await api.downloads.pause(item.id).catch(() => undefined);
  const res = await host({ type: "download", url: item.finalUrl || item.url, pageUrl: item.referrer || undefined, mimeType: item.mime } as any);
  if (res.error) {
    await api.downloads.resume(item.id).catch(() => undefined); // WAN could not take it: let the browser continue
  } else {
    await api.downloads.cancel(item.id).catch(() => undefined);
    await api.downloads.erase({ id: item.id }).catch(() => undefined);
  }
});

api.runtime.onMessage.addListener((msg: InternalMessage, sender, reply) => {
  (async () => {
    switch (msg.kind) {
      case "page-media": {
        const tab = sender.tab;
        if (!tab?.id || !tab.url) return reply({});
        const n = await addSources(tab.id, tab.url, msg.title, msg.sources);
        const p = await prefs();
        return reply({ count: n, showOverlay: n > 0 && p.popupNotice && !!config?.videoPopup });
      }
      case "get-tab-media":
        return reply(tabs.get(msg.tabId) ?? null);
      case "verify": {
        const t = tabs.get(msg.tabId);
        const src = t?.sources.find((s) => s.url === msg.url);
        if (!t || !src) return reply({ error: { code: "gone", message: "This media is no longer on the page" } });
        return reply(await host({ type: "media", pageUrl: t.pageUrl, title: t.title, sources: [src] } as any));
      }
      case "download-format":
        return reply(await host({ type: "media-download", sourceId: msg.sourceId, formatId: msg.formatId } as any));
      case "download-url":
        return reply(await host({ type: "download", url: msg.url, pageUrl: msg.pageUrl } as any));
      case "host-status": {
        const cfg = await hello();
        const st = await host<{ items: unknown[] }>({ type: "status" } as any);
        return reply({ config: cfg, status: st });
      }
      case "open-grabber":
        try {
          await (api.action as any).openPopup();
        } catch {
          const tab = sender.tab?.id ? `?tab=${sender.tab.id}` : "";
          await api.windows.create({ url: api.runtime.getURL("popup.html" + tab), type: "popup", width: 420, height: 640 });
        }
        return reply({});
    }
  })();
  return true; // async reply
});
