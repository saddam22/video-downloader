/**
 * Message contract between the extension and the WAN Download Manager native host
 * (mirrors native-messaging/host/.../MessageContract.java — the host re-validates everything).
 */
export const PROTOCOL_VERSION = 1;
export const HOST_NAME = "com.wan.download_manager";

export interface MediaSourceReport {
  url: string;
  mimeType?: string;
  /** From the decoded element (videoWidth / videoHeight / duration) — never guessed. */
  width?: number;
  height?: number;
  durationSec?: number;
  poster?: string;
}

export type HostRequest =
  | { type: "hello"; id: string; v: 1; version: string }
  | { type: "status"; id: string; v: 1 }
  | { type: "ping"; id: string; v: 1 }
  | { type: "download"; id: string; v: 1; url: string; fileName?: string; pageUrl?: string; mimeType?: string }
  | { type: "media"; id: string; v: 1; pageUrl: string; title?: string; sources: MediaSourceReport[] }
  | { type: "media-download"; id: string; v: 1; sourceId: string; formatId: string };

export interface HostError {
  code: string;
  message: string;
}

export interface HostResponse<T = unknown> {
  id: string;
  result?: T;
  error?: HostError;
}

export interface ExtensionConfig {
  enabled: boolean;
  videoDetection: boolean;
  videoPopup: boolean;
  blockedSites: string[];
  detectionAllowedByPlan: boolean;
}

export interface FormatView {
  id: string;
  label: string;
  container?: string;
  width?: number;
  height?: number;
  estimatedBytes?: number;
}

export interface SourceView {
  id: string;
  title?: string;
  kind: "FILE" | "HLS" | "DASH";
  durationSec?: number;
  thumbnail?: string;
  protectedContent: boolean;
  note?: string;
  formats: FormatView[];
}

/** Messages between content script / popup and the background worker. */
export type InternalMessage =
  | { kind: "page-media"; title: string; sources: MediaSourceReport[] }
  | { kind: "get-tab-media"; tabId: number }
  | { kind: "verify"; tabId: number; url: string }
  | { kind: "download-format"; sourceId: string; formatId: string }
  | { kind: "download-url"; url: string; pageUrl?: string }
  | { kind: "host-status" }
  | { kind: "open-grabber" };

export const LIMITS = { maxSources: 50, maxUrl: 8192, maxTitle: 500 } as const;
