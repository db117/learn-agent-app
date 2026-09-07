import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const backend = path.join(root, "backend");
const command = process.platform === "win32" ? "mvnw.cmd" : "./mvnw";
const child = spawn(command, process.argv.slice(2), { cwd: backend, stdio: "inherit", shell: process.platform === "win32" });
child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
