import {expect, type Page, test} from "@playwright/test";

async function replaceEditor(page: Page, source: string) {
    const editor = page.locator(".workspace-editor .monaco-editor").first();
    await expect(editor).toBeVisible();
    const input = editor.getByRole("textbox", {name: "Editor content"});
    await expect(input).toBeVisible();
    await input.focus();
    await input.press("Control+A");
    await input.press(process.platform === "darwin" ? "Meta+A" : "Control+A");
    await input.press("Backspace");
    await page.keyboard.insertText(source);
}

async function readProgressCounter(page: Page) {
    const counter = page.locator(".learn-heading .journey-status");
    await expect(counter).toHaveText(/^\d+ \/ \d+$/);
    const match = (await counter.textContent())?.match(/(\d+) \/ (\d+)/);
    if (!match) throw new Error("无法读取 Learn Mode 进度");
    return {completed: Number(match[1]), total: Number(match[2])};
}

test("learner completes Learn Mode through the real browser", async ({page}) => {
    await page.goto("/");

    await page.locator("#learner-background").fill("TypeScript 初学者，想掌握基础编程。");
    await page.getByRole("button", {name: "保存并继续"}).click();
    await expect(page.getByRole("heading", {name: "你的 Journey"})).toBeVisible();

    await page.locator("#journey-goal").fill("在浏览器中完成 TypeScript Learn Mode。");
    await page.getByRole("button", {name: "创建 Journey"}).click();
    await page.getByRole("button", {name: "生成学习路径"}).click();

    const chat = page.getByLabel("Tutor 对话记录");
    await expect(page.getByRole("heading", {name: "规划草稿"})).toBeVisible();
    await expect(chat).toContainText('"chapters"');
    const planningDraft = await chat.locator(".message.assistant").last().textContent();
    expect(planningDraft ?? "").not.toMatch(/"(concept|example|practice)"\s*:/);
    await page.getByRole("button", {name: "完成设计"}).click();

    await expect(page.getByRole("heading", {name: "代码练习"})).toBeVisible({timeout: 120_000});
    await expect(page.getByRole("heading", {name: "Concept"})).toBeVisible({timeout: 120_000});
    await expect(page.getByRole("heading", {name: "Example"})).toBeVisible({timeout: 120_000});
    await expect(page.getByRole("heading", {name: "Practice"})).toBeVisible({timeout: 120_000});
    await expect(page.locator(".lesson-section").first().locator(".learn-content"))
        .not.toHaveText("", {timeout: 120_000});
    await expect(page.locator(".unit-label")).toHaveText(/当前 LearnUnit：\S+/);
    await expect(chat.locator(".message.assistant").last()).toContainText(/\S+/);

    const initialProgress = await readProgressCounter(page);
    expect(initialProgress.completed).toBe(0);
    expect(initialProgress.total).toBeGreaterThan(1);
    const chapters = page.locator(".chapter-path-item");
    expect(await chapters.count()).toBeGreaterThan(0);
    for (const chapter of await chapters.all()) {
        expect(await chapter.locator("li").count()).toBeGreaterThan(0);
    }

    let completed = initialProgress.completed;
    let firstAttempt = true;
    let choiceAttempted = false;
    while (completed < initialProgress.total) {
        if (!choiceAttempted && completed === 1) {
            await expect(page.getByRole("button", {name: "开始选择题"})).toBeEnabled({timeout: 120_000});
            await page.getByRole("button", {name: "开始选择题"}).click();
            await expect(page.locator("#choice-question-title")).toBeVisible({timeout: 120_000});
            const options = page.getByRole("radio", {name: /.+/});
            await expect(options).toHaveCount(4);
            await expect(page.locator(".choice-practice p").nth(1)).not.toHaveText("");
            await expect(page.locator(".choice-practice")).not.toContainText("correctOptionId");

            let choiceVerified = false;
            for (let optionIndex = 0; optionIndex < 4; optionIndex++) {
                await page.getByRole("radio", {name: /.+/}).nth(optionIndex).check();
                const responsePromise = page.waitForResponse((response) =>
                    response.request().method() === "POST"
                    && response.url().includes("/practice/choice/")
                    && !response.url().endsWith("/choice/start"));
                await page.getByRole("button", {name: "提交选择题"}).click();
                const response = await responsePromise;
                expect(response.ok()).toBeTruthy();
                const result = await response.json() as { verified: boolean; choiceCorrect: boolean };
                if (result.verified) {
                    choiceVerified = true;
                    break;
                }
                await expect(page.locator("p.form-feedback").filter({hasText: "选择不正确"})).toBeVisible();
                await expect(page.locator(".learn-heading .journey-status"))
                    .toHaveText(`${completed} / ${initialProgress.total}`);
            }
            expect(choiceVerified).toBe(true);
            choiceAttempted = true;
            completed++;
            if (completed < initialProgress.total) {
                await expect(page.locator(".learn-heading .journey-status"))
                    .toHaveText(`${completed} / ${initialProgress.total}`, {timeout: 120_000});
            }
            continue;
        }

        await expect(page.getByRole("button", {name: "验证并记录"})).toBeEnabled({timeout: 120_000});
        await page.getByRole("button", {name: "index.ts", exact: true}).click();
        await expect(page.locator(".workspace-path")).toHaveText("src/index.ts");

        const source = firstAttempt
            ? 'export const answer: number = "broken";'
            : `export const answer: number = ${completed + 42};`;
        await replaceEditor(page, source);
        await expect(page.locator(".workspace-path")).toContainText("未保存");
        await page.getByRole("button", {name: "保存", exact: true}).click();
        await expect(page.getByRole("button", {name: "保存", exact: true})).toBeDisabled();
        await page.getByRole("button", {name: "验证并记录"}).click();

        if (firstAttempt) {
            await expect(page.locator("p.form-feedback").filter({hasText: "Practice 尚未通过"}))
                .toBeVisible({timeout: 120_000});
            await expect(page.locator(".learn-heading .journey-status"))
                .toHaveText(`${completed} / ${initialProgress.total}`);
            firstAttempt = false;
            continue;
        }

        completed++;
        if (completed < initialProgress.total) {
            await expect(page.locator(".learn-heading .journey-status"))
                .toHaveText(`${completed} / ${initialProgress.total}`, {timeout: 120_000});
        }
    }

    await expect(page.getByRole("heading", {name: "学习路径已完成"}).first()).toBeVisible({
        timeout: 120_000,
    });
    await expect(page.getByText(`已完成 ${initialProgress.total} / ${initialProgress.total} 个 LearnUnit。`).first())
        .toBeVisible();
});
