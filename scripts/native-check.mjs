import {mkdtemp, rm} from "node:fs/promises";
import {existsSync} from "node:fs";
import {createServer} from "node:http";
import os from "node:os";
import path from "node:path";
import {spawn, spawnSync} from "node:child_process";
import {fileURLToPath} from "node:url";
import {withNativeParallelism} from "./native-parallelism.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const backend = path.join(root, "backend");
const wrapper = process.platform === "win32" ? "mvnw.cmd" : "mvnw";
const wrapperPath = path.join(backend, wrapper);

if (!existsSync(wrapperPath)) {
    console.error(`Missing ${wrapperPath}; generate the Maven Wrapper before native:check.`);
    process.exit(1);
}

if (process.env.NATIVE_CHECK_SKIP_BUILD !== "true") {
    const buildArgs = withNativeParallelism(["-Pnative", "native:compile"]);
    console.log(`Native Image parallelism: ${buildArgs.find((arg) => arg.startsWith("-Dnative.image.parallelism="))?.split("=")[1]}`);
    const build = spawnSync(wrapperPath, buildArgs, {
        cwd: backend,
        env: process.env,
        encoding: "utf8",
        shell: process.platform === "win32",
        maxBuffer: 16 * 1024 * 1024,
    });
    if (build.status !== 0) {
        process.stderr.write(build.stdout ?? "");
        process.stderr.write(build.stderr ?? "");
        process.exit(build.status ?? 1);
    }
}

const executable = path.join(backend, "target", process.platform === "win32" ? "agent-backend.exe" : "agent-backend");
if (!existsSync(executable)) {
    console.error(`Native executable not found: ${executable}`);
    process.exit(1);
}

const dataDir = await mkdtemp(path.join(os.tmpdir(), "desktop-learning-agent-"));
const database = path.join(dataDir, "agent.db");
const modelRequests = [];
const modelServer = createServer(async (request, response) => {
    let body = "";
    for await (const chunk of request) body += chunk;
    const prompt = JSON.parse(body);
    modelRequests.push(prompt);
    const hasToolResult = prompt.messages?.some((message) => message.role === "tool");
    const firstRequest = modelRequests.length === 1;
    if (!prompt.stream) {
        response.writeHead(200, {"content-type": "application/json"});
        response.end(JSON.stringify({
            id: `native-check-${modelRequests.length}`,
            object: "chat.completion",
            created: 1770000000,
            model: "native-check",
            choices: [{
                index: 0,
                message: firstRequest
                    ? {
                        role: "assistant",
                        content: null,
                        tool_calls: [{
                            id: "native-check-call",
                            type: "function",
                            function: {name: "echo", arguments: JSON.stringify({text: "native-check"})}
                        }]
                    }
                    : {
                        role: "assistant",
                        content: hasToolResult ? "Echo observed: native-check" : "native-check response"
                    },
                finish_reason: firstRequest ? "tool_calls" : "stop",
            }],
            usage: {prompt_tokens: 10, completion_tokens: 5, total_tokens: 15},
        }));
        return;
    }
    response.writeHead(200, {
        "cache-control": "no-cache",
        connection: "keep-alive",
        "content-type": "text/event-stream",
    });
    const chunks = firstRequest
        ? [
            {
                delta: {
                    role: "assistant",
                    reasoning_content: "native-check reasoning",
                    tool_calls: [{
                        index: 0,
                        id: "native-check-call",
                        type: "function",
                        function: {name: "echo", arguments: JSON.stringify({text: "native-check"})}
                    }]
                }, finish_reason: null
            },
            {delta: {}, finish_reason: "tool_calls"},
        ]
        : [
            {delta: {role: "assistant", content: "Echo observed: "}, finish_reason: null},
            {delta: {content: "native-check"}, finish_reason: null},
            {delta: {}, finish_reason: "stop"},
        ];
    for (const chunk of chunks) {
        response.write(`data: ${JSON.stringify({
            id: `native-check-${modelRequests.length}`,
            object: "chat.completion.chunk",
            model: "native-check",
            choices: [{index: 0, flag: 0, delta: chunk.delta, finish_reason: chunk.finish_reason}]
        })}\n\n`);
        await new Promise((resolve) => setTimeout(resolve, 20));
    }
    response.end("data: [DONE]\n\n");
});
await new Promise((resolve) => modelServer.listen(0, "127.0.0.1", resolve));
const modelUrl = `http://127.0.0.1:${modelServer.address().port}/v1`;
const child = spawn(executable, [
    `--app.openai.base-url=${modelUrl}`,
    "--app.openai.model=native-check",
], {
    cwd: backend,
    env: {...process.env, AGENT_DATA_DIR: dataDir, APP_DATABASE: database, OPENAI_API_KEY: "native-check"},
    stdio: ["ignore", "pipe", "pipe"],
});
let output = "";
child.stdout.setEncoding("utf8");
child.stderr.setEncoding("utf8");
child.stdout.on("data", (chunk) => {
    output += chunk;
});
child.stderr.on("data", (chunk) => {
    output += chunk;
});

async function json(url, options) {
    const response = await fetch(url, {...options, signal: AbortSignal.timeout(5000)});
    if (!response.ok) throw new Error(`${response.status} ${url}: ${await response.text()}`);
    return response.json();
}

async function waitForHealth() {
    const deadline = Date.now() + 60_000;
    let lastError = "not ready";
    while (Date.now() < deadline) {
        try {
            const health = await json("http://127.0.0.1:18080/api/health");
            if (health.status === "UP" && health.sqlite === "UP" && health.agent === "UP") return health;
            lastError = JSON.stringify(health);
        } catch (error) {
            lastError = error instanceof Error ? error.message : String(error);
        }
        await new Promise((resolve) => setTimeout(resolve, 500));
    }
    throw new Error(`Native backend did not become healthy: ${lastError}`);
}

async function assertIdleStreamStaysOpen(sessionId) {
    const response = await fetch(`http://127.0.0.1:18080/api/sessions/${sessionId}/events`);
    if (!response.ok || !response.headers.get("content-type")?.includes("text/event-stream")) {
        throw new Error("Idle SSE boundary failed");
    }
    const reader = response.body?.getReader();
    if (!reader) throw new Error("Idle SSE response has no body");
    const result = await Promise.race([
        reader.read().then(({done}) => ({done})),
        new Promise((resolve) => setTimeout(() => resolve({timeout: true}), 300)),
    ]);
    await reader.cancel();
    if (result.done) throw new Error("Idle SSE closed before a run started");
}

async function readUntilMessage(sessionId) {
    const response = await fetch(`http://127.0.0.1:18080/api/sessions/${sessionId}/events`);
    if (!response.ok || !response.headers.get("content-type")?.includes("text/event-stream")) {
        throw new Error("Run SSE boundary failed");
    }
    const reader = response.body?.getReader();
    if (!reader) throw new Error("Run SSE response has no body");
    const decoder = new TextDecoder();
    let stream = "";
    try {
        while (!stream.includes('"eventType":"complete"')) {
            const {value, done} = await Promise.race([
                reader.read(),
                new Promise((_, reject) => setTimeout(() => reject(new Error("Timed out waiting for formal SSE")), 10_000)),
            ]);
            if (done) break;
            stream += decoder.decode(value, {stream: true});
            if (stream.includes('"eventType":"complete"')) return stream;
        }
        return stream;
    } finally {
        await reader.cancel();
    }
}

async function waitForAssistantMessage(sessionId) {
    const deadline = Date.now() + 10_000;
    let detail;
    while (Date.now() < deadline) {
        detail = await json(`http://127.0.0.1:18080/api/sessions/${sessionId}`);
        if (detail.messages.some((message) => message.role === "assistant" && message.content.includes("Echo observed: native-check"))) {
            return detail;
        }
        await new Promise((resolve) => setTimeout(resolve, 100));
    }
    throw new Error(`Formal native assistant message was not persisted: ${JSON.stringify(detail)}`);
}

try {
    const health = await waitForHealth();
    const session = await json("http://127.0.0.1:18080/api/sessions", {
        method: "POST",
        headers: {"content-type": "application/json"},
        body: JSON.stringify({title: "native-check"}),
    });
    await assertIdleStreamStaysOpen(session.id);
    const sessions = await json("http://127.0.0.1:18080/api/sessions");
    if (!sessions.some((item) => item.id === session.id)) throw new Error("SQLite session write/read failed");
    const eventStreamPromise = readUntilMessage(session.id);
    const receipt = await json(`http://127.0.0.1:18080/api/sessions/${session.id}/messages`, {
        method: "POST",
        headers: {"content-type": "application/json"},
        body: JSON.stringify({content: "verify native-check"}),
    });
    const eventStream = await eventStreamPromise;
    if (!eventStream.includes('"eventType":"tool_call"') || !eventStream.includes('"eventType":"tool_result"')) {
        throw new Error("Formal SSE did not contain AgentScope tool events");
    }
    if (eventStream.includes('"rawJson":"{}"')) throw new Error("Native AgentScope raw event JSON was empty");
    if (modelRequests.length !== 2 || modelRequests.some((request) => request.stream !== true)) {
        throw new Error("Formal native path did not use streaming model requests");
    }
    const toolResultMessage = modelRequests[1].messages?.find((message) => message.role === "tool");
    if (!toolResultMessage?.content?.includes("native-check")) {
        throw new Error("Formal native path did not resend the tool result");
    }
    const detail = await waitForAssistantMessage(session.id);
    console.log(JSON.stringify({
        native: "UP",
        health,
        sqlite: "UP",
        agent: "UP",
        tool: "UP",
        sse: "UP",
        formalPost: receipt.runId,
        messages: detail.messages.length
    }));
} catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    if (output) console.error(output.slice(-12_000));
    process.exitCode = 1;
} finally {
    if (child.exitCode === null) {
        child.kill();
        await new Promise((resolve) => child.once("exit", resolve));
    }
    modelServer.closeAllConnections();
    await new Promise((resolve) => modelServer.close(resolve));
    await rm(dataDir, {recursive: true, force: true});
}
