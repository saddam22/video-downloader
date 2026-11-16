// Compiles shared/src with tsc and assembles dist/<browser>/ (manifest + scripts + static files).
import { execFileSync } from "node:child_process";
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

rmSync("dist", { recursive: true, force: true });
execFileSync(process.execPath, ["node_modules/typescript/bin/tsc", "-p", "tsconfig.json"], { stdio: "inherit" });
// content.js runs as a classic script: drop the (type-only) ES module syntax tsc leaves behind.
const content = readFileSync("dist/js/content.js", "utf8").replace(/^export \{\};?\s*$/m, "");
writeFileSync("dist/js/content.js", content);
for (const browser of ["chrome", "edge", "firefox"]) {
  const out = join("dist", browser);
  mkdirSync(out, { recursive: true });
  cpSync("dist/js", out, { recursive: true });
  cpSync("shared/static", out, { recursive: true });
  cpSync(join(browser, "manifest.json"), join(out, "manifest.json"));
}
rmSync("dist/js", { recursive: true, force: true });
console.log("Built:", readdirSync("dist").join(", "));
