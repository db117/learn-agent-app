import http from "node:http";

const port = 19090;
const plan = JSON.stringify({
    chapters: [{
        code: "basics",
        title: "基础",
        units: [
            {
                code: "variables",
                title: "变量与类型",
                objective: "能够声明变量并理解基本类型",
                concept: "变量保存数据，并且可以通过类型约束减少错误。",
                example: "export const answer: number = 42;",
                practice: "修复 src/index.ts 中的类型错误。",
            },
            {
                code: "functions",
                title: "函数",
                objective: "能够声明带类型的函数",
                concept: "函数描述可复用的行为。",
                example: "const add = (a: number, b: number) => a + b;",
                practice: "为函数补充参数和返回值类型。",
            },
        ],
    }],
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
