import { test } from "node:test";
import assert from "node:assert/strict";
import { classify, mediaKey, isSegment, mergeSources, hostBlocked, formatDuration } from "../dist/chrome/media.js";

test("classifies manifests and files, refuses other schemes", () => {
  assert.equal(classify("https://cdn.example/v/master.m3u8"), "HLS");
  assert.equal(classify("https://cdn.example/x", "application/vnd.apple.mpegurl"), "HLS");
  assert.equal(classify("https://cdn.example/manifest.mpd"), "DASH");
  assert.equal(classify("https://cdn.example/clip.webm"), "FILE");
  assert.equal(classify("https://cdn.example/page.html"), null);
  assert.equal(classify("file:///etc/passwd.mp4"), null);
  assert.equal(classify("javascript:alert(1)"), null);
});

test("dedupes by URL without fragment and keeps metadata learned later", () => {
  const a = [{ url: "https://e.com/a.mp4#t=10" }];
  const merged = mergeSources(a, [{ url: "https://e.com/a.mp4", width: 1280, height: 720 }, { url: "https://e.com/b.mp4" }]);
  assert.equal(merged.length, 2);
  assert.equal(merged[0].height, 720);
  assert.equal(mediaKey("https://e.com/a.mp4#x"), "https://e.com/a.mp4");
  assert.equal(mergeSources([], Array.from({ length: 80 }, (_, i) => ({ url: `https://e.com/${i}.mp4` }))).length, 50);
});

test("segments are ignored; blocked hosts include subdomains", () => {
  assert.ok(isSegment("https://cdn.example/seg-00012.ts"));
  assert.ok(isSegment("https://cdn.example/chunk_3.m4s"));
  assert.ok(!isSegment("https://cdn.example/movie.mp4"));
  assert.ok(hostBlocked("www.bank.example", ["bank.example"]));
  assert.ok(!hostBlocked("notbank.example", ["bank.example"]));
  assert.equal(formatDuration(3725), "1:02:05");
  assert.equal(formatDuration(NaN), "");
});
