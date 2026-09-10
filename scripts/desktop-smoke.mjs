import {mkdir, mkdtemp, readdir, readFile, rm, writeFile} from "node:fs/promises";
import {existsSync} from "node:fs";
import {spawn} from "node:child_process";
import {createConnection} from "node:net";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const backend = path.join(root, "backend");
const jar = path.join(backend, "target", "agent-backend.jar");
const schema = path.join(backend, "src", "main", "resources", "schema.sql");
const host = "127.0.0.1";
const port = 18080;

// 此 smoke 故意使用隔离的测试数据和空 API key，绝不接触用户数据。
function delay(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
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

/** 仅使用 sqlite3 创建确定性的测试数据并执行只读断言。 */
function run(command, args, input = "") {
    return new Promise((resolve, reject) => {
        const child = spawn(command, args, {cwd: root, stdio: ["pipe", "pipe", "pipe"]});
        let stdout = "";
        let stderr = "";
        child.stdout.setEncoding("utf8");
        child.stderr.setEncoding("utf8");
        child.stdout.on("data", (chunk) => stdout += chunk);
        child.stderr.on("data", (chunk) => stderr += chunk);
        child.once("error", reject);
        child.once("exit", (code, signal) => {
            if (code === 0) resolve(stdout.trim());
            else reject(new Error(`${command} exited with ${code ?? signal}: ${stderr.trim()}`));
        });
        child.stdin.end(input);
    });
}

/** 创建源数据库和目标数据库，并写入所有可移植数据类别的记录。 */
async function seedDatabase(database, kind) {
    const schemaSql = await readFile(schema, "utf8");
    const source = kind === "source";
    const values = source ? {
        languageId: "language-source",
        languageCode: "python-source",
        unitId: "unit-source",
        unitCode: "python.source",
        journeyId: "journey-source",
        goal: "Imported source journey",
        pathId: "path-source",
        questionId: "question-source",
        assessmentId: "assessment-source",
        attemptId: "attempt-source",
        sessionId: "session-source",
        eventId: "event-source",
        tutorId: "tutor-source",
        transitionId: "transition-source",
        setting: "source-setting",
    } : {
        languageId: "language-target",
        languageCode: "python-target",
        unitId: "unit-target",
        unitCode: "python.target",
        journeyId: "journey-target",
        goal: "Target-only journey",
        pathId: "path-target",
        questionId: "question-target",
        assessmentId: "assessment-target",
        attemptId: "attempt-target",
        sessionId: "session-target",
        eventId: "event-target",
        tutorId: "tutor-target",
        transitionId: "transition-target",
        setting: "target-setting",
    };
    const timestamps = source ? {
        created: "2026-09-10T01:00:00Z",
        updated: "2026-09-10T01:00:01Z",
    } : {
        created: "2026-09-10T01:00:02Z",
        updated: "2026-09-10T01:00:03Z",
    };
    const seed = `
PRAGMA foreign_keys = ON;
INSERT INTO learning_language VALUES ('${values.languageId}', '${values.languageCode}', '${source ? "Python Source" : "Python Target"}', 'Smoke language', 1);
INSERT INTO learn_unit VALUES ('${values.unitId}', '${values.languageCode}', '${values.unitCode}', 'Imported basics', 'Smoke unit', 1, '[]', 80, NULL, 1, '["read"]', 'Imported lesson', '["syntax"]', '["print()"]', 1);
INSERT INTO learning_journey VALUES ('${values.journeyId}', 'desktop-user', '${values.languageCode}', '${values.goal}', 'ACTIVE', '${timestamps.created}', '${timestamps.updated}');
INSERT INTO learning_journey_learn_unit VALUES ('${values.journeyId}', '${values.unitCode}');
INSERT INTO learner_profile VALUES ('${values.journeyId}', '中文', 2, 'desktop smoke', 'restore all state');
INSERT INTO learning_path_item (id, journey_id, learn_unit_code, sequence, status, mastery_score, best_assessment_score, attempt_count) VALUES ('${values.pathId}', '${values.journeyId}', '${values.unitCode}', 1, 'CURRENT', 42, 88, 1);
INSERT INTO question VALUES ('${values.questionId}', '${values.unitCode}', 'MULTIPLE_CHOICE', 1, 'Choose A', 10, '{"correctOptionIds":["A"]}', NULL, NULL, NULL, '[]', 1);
INSERT INTO assessment VALUES ('${values.assessmentId}', '${values.journeyId}', '${values.unitCode}', 'LEARN_UNIT', 'COMPLETED', '${timestamps.created}', '${timestamps.updated}');
INSERT INTO assessment_question VALUES ('${values.assessmentId}', '${values.questionId}', 1);
INSERT INTO assessment_attempt VALUES ('${values.attemptId}', '${values.assessmentId}', '${values.journeyId}', '${values.unitCode}', 1, 10, NULL, 100, 1, '${timestamps.created}', '${timestamps.updated}');
INSERT INTO question_attempt VALUES ('${values.questionId}', '${values.attemptId}', '{"selectedOptionIds":["A"]}', 10, 10, 'restored', 1, NULL, NULL, '["A"]');
INSERT INTO "session" VALUES ('${values.sessionId}', 'desktop-user', 'Restored tutor', '${timestamps.created}', '${timestamps.updated}');
INSERT INTO message VALUES ('${values.sessionId}-message-user', '${values.sessionId}', 'user', 'Remember this message', '${timestamps.created}');
INSERT INTO message VALUES ('${values.sessionId}-message-assistant', '${values.sessionId}', 'assistant', 'Restored assistant reply', '${timestamps.updated}');
INSERT INTO "event" (id, session_id, run_id, author, event_type, content, timestamp, raw_json) VALUES ('${values.eventId}', '${values.sessionId}', 'run-${kind}', 'tutor_agent', 'text_delta', 'Restored tutor event', '${timestamps.updated}', '{}');
INSERT INTO tutor_session VALUES ('${values.tutorId}', '${values.journeyId}', '${values.unitCode}', '${values.sessionId}');
INSERT INTO workflow_transition VALUES ('${values.transitionId}', '${values.journeyId}', NULL, 'smoke_restore', 'CURRENT', '{}', '${timestamps.updated}');
INSERT INTO agent_state VALUES ('desktop-user', '${values.sessionId}', 'agent_state', 'single', '{"messages":[]}', 1, '${timestamps.updated}');
INSERT INTO setting (key, value, updated_at) VALUES ('smoke.setting', '${values.setting}', '${timestamps.updated}');
`;
    await run("sqlite3", ["-bail", database], `${schemaSql}\n${seed}`);
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

/** 使用临时数据目录启动一个受管 JVM，并等待健康检查通过。 */
async function startBackend(dataDir) {
    const database = path.join(dataDir, "agent.db");
    const child = spawn("java", [
        "-jar",
        jar,
        `--server.address=${host}`,
        `--server.port=${port}`,
    ], {
        cwd: backend,
        env: {...process.env, AGENT_DATA_DIR: dataDir, APP_DATABASE: database, OPENAI_API_KEY: ""},
        stdio: ["ignore", "ignore", "ignore"],
    });
    let spawnError;
    child.once("error", (error) => spawnError = error);
    try {
        const deadline = Date.now() + 60_000;
        let lastError = "not ready";
        while (Date.now() < deadline) {
            if (spawnError) throw new Error(`Java could not start: ${spawnError.message}`);
            if (child.exitCode !== null) throw new Error("JVM backend exited before health became UP.");
            try {
                const health = await readHealth();
                if (health.status === "UP" && health.sqlite === "UP" && health.agent === "UP") {
                    if (health.llm !== "UnavailableModel") throw new Error("Smoke unexpectedly configured a real LLM provider.");
                    return {child, health};
                }
                lastError = JSON.stringify(health);
            } catch (error) {
                lastError = error instanceof Error ? error.message : String(error);
            }
            await delay(250);
        }
        throw new Error(`JVM backend did not become healthy: ${lastError}`);
    } catch (error) {
        if (processAlive(child)) await stopBackend(child);
        throw error;
    }
}

/** 停止本次 smoke 启动的 JVM，并确认固定端口已经释放。 */
async function stopBackend(child) {
    if (!processAlive(child)) return;
    const exited = new Promise((resolve) => child.once("exit", resolve));
    child.kill();
    await Promise.race([exited, delay(5000)]);
    if (processAlive(child)) child.kill("SIGKILL");
    await Promise.race([exited, delay(5000)]);
    if (processAlive(child)) throw new Error("JVM backend process did not exit after shutdown.");
    await waitForPort(false, 5000);
}

async function request(pathname, options = {}) {
    const response = await fetch(`http://${host}:${port}${pathname}`, {
        signal: AbortSignal.timeout(10_000),
        ...options,
    });
    const raw = await response.text();
    if (!response.ok) throw new Error(`${options.method ?? "GET"} ${pathname} failed with ${response.status}: ${raw}`);
    return raw ? JSON.parse(raw) : null;
}

/** 在停止源 JVM 前验证二进制下载协议。 */
async function exportDatabase() {
    const response = await fetch(`http://${host}:${port}/api/database/export`, {
        signal: AbortSignal.timeout(10_000),
    });
    if (!response.ok) throw new Error(`Database export failed with ${response.status}: ${await response.text()}`);
    const filename = response.headers.get("content-disposition") ?? "";
    if (!filename.includes("learning-agent-java-") || !/\.db"?$/.test(filename)) {
        throw new Error(`Unexpected database export filename: ${filename}`);
    }
    return Buffer.from(await response.arrayBuffer());
}

/** 确认恢复后的 TutorEvent 记录会通过公开 SSE 边界回放。 */
async function readFirstSseFrame(sessionId) {
    const response = await fetch(`http://${host}:${port}/api/sessions/${sessionId}/events`, {
        headers: {Accept: "text/event-stream"},
        signal: AbortSignal.timeout(2000),
    });
    if (!response.ok || !response.body) throw new Error(`Restored event stream failed with ${response.status}.`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let text = "";
    try {
        while (!text.includes("\n\n")) {
            const next = await reader.read();
            if (next.done) break;
            text += decoder.decode(next.value, {stream: true});
        }
    } finally {
        await reader.cancel();
    }
    return text;
}

async function sqliteScalar(database, sql) {
    return (await run("sqlite3", ["-batch", "-noheader", database, sql])).split("\n")[0] ?? "";
}

function assert(condition, message) {
    if (!condition) throw new Error(message);
}

let rootDataDir;
let sourceDataDir;
let targetDataDir;
let active;
let failure;

try {
    if (!existsSync(jar)) throw new Error(`Missing ${jar}; run pnpm backend:package first.`);
    await run("sqlite3", ["--version"]);
    if (await portReachable()) throw new Error(`Fixed backend port ${host}:${port} is already in use; refusing to touch another process.`);

    // 保持源数据库和目标数据库分离，使导入断言能够证明发生了完整替换。
    rootDataDir = await mkdtemp(path.join(os.tmpdir(), "desktop-learning-agent-smoke-"));
    sourceDataDir = path.join(rootDataDir, "source");
    targetDataDir = path.join(rootDataDir, "target");
    await mkdir(sourceDataDir, {recursive: true});
    await mkdir(targetDataDir, {recursive: true});
    const sourceDatabase = path.join(sourceDataDir, "agent.db");
    const targetDatabase = path.join(targetDataDir, "agent.db");
    await seedDatabase(sourceDatabase, "source");

    active = await startBackend(sourceDataDir);
    await waitForPort(true, 2000);
    const snapshot = await exportDatabase();
    assert(snapshot.subarray(0, 16).toString("ascii") === "SQLite format 3\0", "Export is not a SQLite database.");
    const snapshotPath = path.join(rootDataDir, "learning-agent-java-smoke.db");
    await writeFile(snapshotPath, snapshot);
    assert(await sqliteScalar(snapshotPath, "SELECT COUNT(*) FROM learning_journey WHERE id = 'journey-source';") === "1", "Export missed the source Journey.");
    assert(await sqliteScalar(snapshotPath, "SELECT COUNT(*) FROM tutor_session WHERE id = 'tutor-source';") === "1", "Export missed TutorSession.");
    assert(await sqliteScalar(snapshotPath, "SELECT COUNT(*) FROM agent_state WHERE session_id = 'session-source';") === "1", "Export missed AgentState.");
    const sourcePid = active.child.pid;
    await stopBackend(active.child);
    active = undefined;

    await seedDatabase(targetDatabase, "target");
    active = await startBackend(targetDataDir);
    assert((await request("/api/learning/journeys")).some((journey) => journey.id === "journey-target"), "Target fixture did not start.");
    // 只有目标 JVM 占用固定端口后才执行导入。
    const imported = await request("/api/database/import", {
        method: "POST",
        headers: {"Content-Type": "application/octet-stream"},
        body: snapshot,
    });
    assert(imported.schemaVersion === "1" && imported.restartRequired === true, "Import did not require a runtime restart.");

    const backupFiles = await readdir(path.join(targetDataDir, "backups"));
    const backup = backupFiles.find((file) => file.endsWith(".db"));
    assert(backup, "Import did not create a pre-import backup.");
    const backupPath = path.join(targetDataDir, "backups", backup);
    assert(await sqliteScalar(backupPath, "PRAGMA integrity_check;") === "ok", "Pre-import backup is not independently valid.");
    assert(await sqliteScalar(backupPath, "SELECT value FROM setting WHERE key = 'smoke.setting';") === "target-setting", "Pre-import backup did not preserve the target database.");

    // 响应要求先重启运行时，再查询新的 SQLite 数据库身份。
    await stopBackend(active.child);
    active = await startBackend(targetDataDir);
    assert(active.child.pid !== sourcePid, "JVM restart did not create a new managed process.");
    const journeys = await request("/api/learning/journeys");
    assert(journeys.length === 1 && journeys[0].id === "journey-source", "整库导入没有删除目标 Journey。");
    const journey = await request("/api/learning/journeys/journey-source");
    assert(journey.journey.goal === "Imported source journey" && journey.path[0].status === "CURRENT", "Imported Journey query did not recover its path.");
    const units = await request("/api/learning/journeys/journey-source/learn-units");
    assert(units.length === 1 && units[0].code === "python.source", "Imported LearnUnit query failed.");
    const restoredUnit = await request("/api/learning/journeys/journey-source/learn-units/python.source");
    assert(restoredUnit.attempts.length === 1 && restoredUnit.questionAttempts.length === 1, "Assessment history was not restored.");
    const tutor = await request("/api/learning/journeys/journey-source/learn-units/python.source/tutor", {method: "POST"});
    assert(tutor.session.id === "session-source" && tutor.journeyId === "journey-source", "TutorSession link was not restored.");
    const session = await request("/api/sessions/session-source");
    assert(session.messages.length === 2 && session.messages[0].content === "Remember this message", "Tutor messages were not restored.");
    const events = await readFirstSseFrame("session-source");
    assert(events.includes("Restored tutor event"), "TutorEvent was not replayed after restart.");
    assert(await sqliteScalar(targetDatabase, "SELECT value FROM setting WHERE key = 'smoke.setting';") === "source-setting", "Setting was not restored.");
    assert(await sqliteScalar(targetDatabase, "SELECT state_kind || ':' || state_json FROM agent_state WHERE session_id = 'session-source';") === "single:{\"messages\":[]}", "Compatible AgentState was not restored.");
    assert(await sqliteScalar(targetDatabase, "SELECT COUNT(*) FROM session WHERE id = 'session-target';") === "0", "整库导入没有删除目标 TutorSession。");
    console.log(JSON.stringify({
        backend: "jvm",
        openai: "not-configured",
        port: `${host}:${port}`,
        export: "SQLite snapshot",
        import: {schemaVersion: imported.schemaVersion, restartRequired: imported.restartRequired, backup},
        restart: "new JVM process",
        restored: ["Journey", "LearnUnit", "TutorSession", "message", "TutorEvent", "setting", "AgentState"],
    }));
} catch (error) {
    failure = error instanceof Error ? error.message : String(error);
} finally {
    if (active?.child && processAlive(active.child)) {
        try {
            await stopBackend(active.child);
        } catch (error) {
            failure = failure ? `${failure}; shutdown: ${error.message}` : `shutdown: ${error.message}`;
        }
    }
    if (await portReachable()) failure = failure ? `${failure}; port ${host}:${port} is still reachable` : `port ${host}:${port} is still reachable`;
    if (rootDataDir) await rm(rootDataDir, {recursive: true, force: true});
}

if (failure) {
    console.error(failure);
    process.exitCode = 1;
}
