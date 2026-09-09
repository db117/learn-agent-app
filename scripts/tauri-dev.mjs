import {spawn} from "node:child_process";

const command = process.platform === "win32" ? "pnpm.cmd" : "pnpm";
const child = spawn(command, ["exec", "tauri", "dev"], {
    stdio: "inherit",
    shell: process.platform === "win32",
});
child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
