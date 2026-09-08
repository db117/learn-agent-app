import {spawn} from "node:child_process";
import {fileURLToPath} from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const backend = path.join(root, "backend");
const command = process.platform === "win32" ? "mvnw.cmd" : "./mvnw";
const args = ["spring-boot:run"];
if (!process.env.OPENAI_API_KEY?.trim()) {
    args.push("-Dspring-boot.run.arguments=--spring.ai.model.chat=none");
    console.warn("OPENAI_API_KEY is not set; starting dev backend without an LLM provider.");
}
const child = spawn(command, args, {
    cwd: backend,
    stdio: "inherit",
    shell: process.platform === "win32"
});
child.on("exit", (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => child.kill(signal));
