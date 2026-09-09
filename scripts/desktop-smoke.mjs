import {mkdtemp, rm} from "node:fs/promises";
import {existsSync} from "node:fs";
import {spawn} from "node:child_process";
import {createConnection} from "node:net";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const backend = path.join(root, "backend");
const jar = path.join(backend, "target", "agent-backend.jar");
const host = "127.0.0.1";
const port = 18080;

function delay(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function portReachable() {
    return new Promise((resolve) => {
        const socket = createConnection({host, port});
        let settled = false;
        const finish = (reachable) => {
            if (settled) return;
            settled = true;
            socket.destroy();
            resolve(reachable);
        };
        socket.once("connect", () => finish(true));
        socket.once("error", () => finish(false));
        socket.setTimeout(500, () => finish(false));
    });
}

async function waitForPort(reachable, timeoutMilliseconds) {
    const deadline = Date.now() + timeoutMilliseconds;
    while (Date.now() < deadline) {
        if ((await portReachable()) === reachable) return;
        await delay(200);
    }
    throw new Error(`Fixed backend port ${host}:${port} did not become ${reachable ? "reachable" : "closed"}.`);
}

async function readHealth() {
    const response = await fetch(`http://${host}:${port}/api/health`, {
        signal: AbortSignal.timeout(1000),
    });
    if (!response.ok) throw new Error(`Health request failed with ${response.status}.`);
    return response.json();
}

async function waitForHealthyBackend(child, getSpawnError) {
    const deadline = Date.now() + 60_000;
    let lastError = "not ready";
    while (Date.now() < deadline) {
        const spawnError = getSpawnError();
        if (spawnError) throw new Error(`Java could not start: ${spawnError.message}`);
        if (child.exitCode !== null) {
            throw new Error("JVM backend exited before health became UP.");
        }
        try {
            const health = await readHealth();
            if (health.status === "UP" && health.sqlite === "UP" && health.agent === "UP") return health;
            lastError = JSON.stringify(health);
        } catch (error) {
            lastError = error instanceof Error ? error.message : String(error);
        }
        await delay(250);
    }
    throw new Error(`JVM backend did not become healthy: ${lastError}`);
}

async function waitForExit(child, exitPromise) {
    if (!processAlive(child)) return true;
    await Promise.race([exitPromise, delay(5000)]);
    return !processAlive(child);
}

function processAlive(child) {
    if (child.exitCode !== null || child.pid === undefined) return false;
    try {
        process.kill(child.pid, 0);
        return true;
    } catch {
        return false;
    }
}

let dataDir;
let child;
let health;
let failure;
let shutdownFailure;
let exitPromise;

try {
    if (!existsSync(jar)) {
        throw new Error(`Missing ${jar}; run pnpm backend:package first.`);
    }
    if (await portReachable()) {
        throw new Error(`Fixed backend port ${host}:${port} is already in use; refusing to touch another process.`);
    }

    dataDir = await mkdtemp(path.join(os.tmpdir(), "desktop-learning-agent-smoke-"));
    const database = path.join(dataDir, "agent.db");
    child = spawn("java", [
        "-jar",
        jar,
        "--spring.ai.model.chat=none",
        `--server.address=${host}`,
        `--server.port=${port}`,
    ], {
        cwd: backend,
        env: {...process.env, AGENT_DATA_DIR: dataDir, APP_DATABASE: database, OPENAI_API_KEY: ""},
        stdio: ["ignore", "ignore", "ignore"],
    });
    exitPromise = new Promise((resolve) => child.once("exit", resolve));
    let spawnError;
    child.once("error", (error) => {
        spawnError = error;
    });

    health = await waitForHealthyBackend(child, () => spawnError);
    await waitForPort(true, 2000);

    child.kill();
    let stopped = await waitForExit(child, exitPromise);
    if (!stopped) {
        child.kill();
        stopped = await waitForExit(child, exitPromise);
    }
    if (!stopped) {
        throw new Error("JVM backend process did not exit after shutdown.");
    }
    await waitForPort(false, 5000);
} catch (error) {
    failure = error instanceof Error ? error.message : String(error);
} finally {
    if (child && processAlive(child)) {
        child.kill();
        await waitForExit(child, exitPromise ?? new Promise((resolve) => child.once("exit", resolve)));
    }
    if (child && await portReachable()) {
        shutdownFailure = `JVM backend still owns ${host}:${port} after shutdown.`;
    }
    if (dataDir) await rm(dataDir, {recursive: true, force: true});
}

if (failure || shutdownFailure) {
    console.error(failure || shutdownFailure);
    if (failure && shutdownFailure) console.error(shutdownFailure);
    process.exitCode = 1;
} else {
    console.log(JSON.stringify({backend: "jvm", startup: "UP", port: `${host}:${port}`, shutdown: "UP", health}));
}
