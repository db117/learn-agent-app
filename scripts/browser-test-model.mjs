import http from "node:http";

const port = 19090;
const assessmentStages = new Map();
const plan = JSON.stringify({
    chapters: [
        {
            code: "foundations",
            title: "基础",
            units: [
                {code: "variables", title: "变量与类型", objective: "能够声明变量并理解基本类型"},
                {code: "functions", title: "函数", objective: "能够声明带类型的函数"},
            ],
        },
        {
            code: "collections",
            title: "数据组织",
            units: [
                {code: "arrays", title: "数组", objective: "能够使用类型安全的数组"},
                {code: "objects", title: "对象", objective: "能够描述对象结构"},
            ],
        },
        {
            code: "runtime",
            title: "运行时实践",
            units: [
                {code: "modules", title: "模块", objective: "能够组织并导入 TypeScript 模块"},
                {code: "compile-and-run", title: "编译与运行", objective: "能够编译并运行 TypeScript 程序"},
            ],
        },
    ],
});

function textContent(value) {
    if (typeof value === "string") return value;
    if (Array.isArray(value)) return value.map(textContent).join("\n");
    if (value && typeof value === "object") return Object.values(value).map(textContent).join("\n");
    return "";
}

function toolCall(name, args) {
    return {
        id: `browser-tool-${Date.now()}`,
        type: "function",
        function: {name, arguments: JSON.stringify(args)},
    };
}

function hasLoadedSkill(prompt, skillId) {
    return prompt.includes(`Successfully loaded skill: ${skillId}`);
}

function hasSavedContent(prompt) {
    return prompt.includes("learnUnitCode");
}

function responseContent(body) {
    const messages = Array.isArray(body.messages) ? body.messages : [];
    const prompt = textContent(messages);
    if (prompt.includes("session-mode: PLANNING")) return {text: plan};
    if (prompt.includes("session-mode: LEARNING") && !hasLoadedSkill(prompt, "learning-content-generation")) {
        return {toolCall: toolCall("load_skill_through_path", {
            skillId: "learning-content-generation_learn-agent-built-in",
            path: "SKILL.md",
        })};
    }
    if (hasLoadedSkill(prompt, "learning-content-generation") && !hasSavedContent(prompt)) {
        return {toolCall: toolCall("save_learning_content", {
            content_json: JSON.stringify({
                concept: "模型生成的 Concept：理解当前 LearnUnit 的核心概念。",
                example: "export const answer: number = 42;",
                practice: "模型生成的 Practice：完成当前 LearnUnit 的编码练习。",
            }),
        })};
    }
    const assessmentAttemptId = [...prompt.matchAll(/不可变 attempt_id[：:]\s*(\d+)/g)].at(-1)?.[1];
    if (assessmentAttemptId) {
        const stage = assessmentStages.get(assessmentAttemptId) ?? 0;
        if (stage === 0) {
            assessmentStages.set(assessmentAttemptId, 1);
            return {toolCall: toolCall("list_files", {})};
        }
        if (stage === 1) {
            assessmentStages.set(assessmentAttemptId, 2);
            return {toolCall: toolCall("read_file", {path: "src/index.ts"})};
        }
        if (stage === 2) {
            assessmentStages.set(assessmentAttemptId, 3);
            return {
                toolCall: toolCall("record_practice_assessment", {
                    attempt_id: Number(assessmentAttemptId),
                    verdict: "READY",
                    rationale: "代码已检查；你说明了变量类型标注的作用，并指出错误是故意用于验证编译器，因此我豁免本次运行失败。",
                })
            };
        }
    }
    const current = prompt.match(/current-learn-unit:\s*([^\s<]+)/)?.[1] ?? "current";
    return {text: `已进入 ${current}。Concept、Example 和 Practice 已准备好，请完成当前练习后再继续。`};
}

function writeStream(response, result) {
    const id = `browser-test-${Date.now()}`;
    const common = {id, object: "chat.completion.chunk", created: Math.floor(Date.now() / 1000), model: "browser-test"};
    response.writeHead(200, {
        "Cache-Control": "no-cache",
        Connection: "keep-alive",
        "Content-Type": "text/event-stream",
    });
    const delta = result.toolCall
        ? {role: "assistant", tool_calls: [result.toolCall]}
        : {role: "assistant", content: result.text};
    response.write(`data: ${JSON.stringify({...common, choices: [{index: 0, delta, finish_reason: null}]})}\n\n`);
    response.write(`data: ${JSON.stringify({
        ...common,
        choices: [{index: 0, delta: {}, finish_reason: result.toolCall ? "tool_calls" : "stop"}],
    })}\n\n`);
    response.end("data: [DONE]\n\n");
}

const server = http.createServer(async (request, response) => {
    if (request.method === "GET" && request.url === "/health") {
        response.writeHead(200, {"Content-Type": "application/json"});
        response.end('{"status":"ok"}');
        return;
    }
    if (request.method !== "POST" || request.url !== "/v1/chat/completions") {
        response.writeHead(404);
        response.end();
        return;
    }

    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    const result = responseContent(body);
    if (body.stream) {
        writeStream(response, result);
        return;
    }
    response.writeHead(200, {"Content-Type": "application/json"});
    response.end(JSON.stringify({
        id: `browser-test-${Date.now()}`,
        object: "chat.completion",
        created: Math.floor(Date.now() / 1000),
        model: "browser-test",
        choices: [{
            index: 0,
            message: result.toolCall
                ? {role: "assistant", tool_calls: [result.toolCall]}
                : {role: "assistant", content: result.text},
            finish_reason: result.toolCall ? "tool_calls" : "stop",
        }],
    }));
});

server.listen(port, "127.0.0.1");
