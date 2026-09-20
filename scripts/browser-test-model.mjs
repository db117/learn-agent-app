import http from "node:http";

const port = 19090;
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

function responseContent(body) {
    const messages = Array.isArray(body.messages) ? body.messages : [];
    const prompt = textContent(messages);
    if (prompt.includes("CHOICE_QUESTION_GENERATION")) {
        return JSON.stringify({
            prompt: "模型生成选择题：请判断本单元的核心目标。",
            options: [
                {id: "a", label: "完成当前 LearnUnit 的学习目标。"},
                {id: "b", label: "只修改无关的界面样式。"},
                {id: "c", label: "跳过当前学习目标。"},
                {id: "d", label: "删除本单元的练习。"},
            ],
            correctOptionId: "d",
        });
    }
    if (prompt.includes("LEARN_UNIT_CONTENT_GENERATION")) {
        return JSON.stringify({
            concept: "模型生成的 Concept：理解当前 LearnUnit 的核心概念。",
            example: "export const answer: number = 42;",
            practice: "模型生成的 Practice：完成当前 LearnUnit 的编码练习。",
        });
    }
    if (prompt.includes("session-mode: PLANNING")) return plan;
    const current = prompt.match(/current-learn-unit:\s*([^\s<]+)/)?.[1] ?? "current";
    return `已进入 ${current}。Concept、Example 和 Practice 已准备好，请完成当前练习后再继续。`;
}

function writeStream(response, content) {
    const id = `browser-test-${Date.now()}`;
    const common = {id, object: "chat.completion.chunk", created: Math.floor(Date.now() / 1000), model: "browser-test"};
    response.writeHead(200, {
        "Cache-Control": "no-cache",
        Connection: "keep-alive",
        "Content-Type": "text/event-stream",
    });
    response.write(`data: ${JSON.stringify({
        ...common,
        choices: [{index: 0, delta: {role: "assistant", content}, finish_reason: null}],
    })}\n\n`);
    response.write(`data: ${JSON.stringify({
        ...common,
        choices: [{index: 0, delta: {}, finish_reason: "stop"}],
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
    const content = responseContent(body);
    if (body.stream) {
        writeStream(response, content);
        return;
    }
    response.writeHead(200, {"Content-Type": "application/json"});
    response.end(JSON.stringify({
        id: `browser-test-${Date.now()}`,
        object: "chat.completion",
        created: Math.floor(Date.now() / 1000),
        model: "browser-test",
        choices: [{index: 0, message: {role: "assistant", content}, finish_reason: "stop"}],
    }));
});

server.listen(port, "127.0.0.1");
