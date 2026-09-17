import {spawn} from "node:child_process";
import {fileURLToPath} from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const command = process.platform === "win32" ? "pnpm.cmd" : "pnpm";
const env = {...process.env};
const pathKey = Object.keys(env).find((key) => key.toLowerCase() === "path") ?? "PATH";
env[pathKey] = [path.join(root, "node_modules", ".bin"), env[pathKey]]
    .filter(Boolean)
    .join(path.delimiter);
const child = spawn(command, ["exec", "tauri", "dev"], {
    cwd: root,
    env,
    stdio: "inherit",
    shell: process.platform === "win32",
});
child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
