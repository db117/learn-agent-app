import {spawn} from "node:child_process";
import {fileURLToPath} from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const command = process.platform === "win32" ? "pnpm.cmd" : "pnpm";
const env = {...process.env};
if (!env.APP_DATABASE?.trim() && !env.AGENT_DATA_DIR?.trim()) {
    env.APP_DATABASE = path.join(root, ".data", "dev", "agent.db");
}
const child = spawn(command, ["exec", "tauri", "dev"], {
    env,
    stdio: "inherit",
    shell: process.platform === "win32",
});
child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
