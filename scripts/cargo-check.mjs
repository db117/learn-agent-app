import { spawn } from "node:child_process";

const child = spawn("cargo", ["check", "--manifest-path", "src-tauri/Cargo.toml"], {
  env: { ...process.env, TAURI_CONFIG: JSON.stringify({ bundle: { externalBin: [] } }) },
  stdio: "inherit",
  shell: process.platform === "win32",
});
child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
