import {spawn} from "node:child_process";
import {fileURLToPath} from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const command = process.platform === "win32" ? "pnpm.cmd" : "pnpm";
const env = {...process.env};
if (!env.APP_DATABASE?.trim() && !env.AGENT_DATA_DIR?.trim()) {
    // Schema 变更时开发环境使用新的数据库文件，保留旧文件以便回溯，不做运行时兼容。
    env.APP_DATABASE = path.join(root, ".data", "dev", "agent-v6.db");
}
const child = spawn(command, ["exec", "tauri", "dev"], {
    env,
    stdio: "inherit",
    shell: process.platform === "win32",
});
child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
