import { chmod, copyFile, mkdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const backend = path.join(root, "backend");
const tauri = path.join(root, "src-tauri");
const triple = process.env.TAURI_TARGET_TRIPLE ?? spawnSync("rustc", ["-vV"], { encoding: "utf8" }).stdout.match(/host: (.+)/)?.[1]?.trim();
if (!triple) throw new Error("Unable to determine the Rust target triple");

const extension = process.platform === "win32" ? ".exe" : "";
const source = path.join(backend, "target", `agent-backend${extension}`);
const destination = path.join(tauri, "binaries", `agent-backend-${triple}${extension}`);
if (!existsSync(source)) throw new Error(`Missing native backend: ${source}`);
await mkdir(path.dirname(destination), { recursive: true });
await copyFile(source, destination);
if (process.platform !== "win32") await chmod(destination, 0o755);
console.log(`Prepared ${destination}`);
