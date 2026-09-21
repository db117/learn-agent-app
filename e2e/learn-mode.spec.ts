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

    const sendTutorMessage = page.getByRole("button", {name: "发送", exact: true});
    await page.getByLabel("发送给 TutorAgent").fill(
        "请基于当前 LearnUnit 的课程内容生成并保存一道四选一选择题，先不要提交答案。",
    );
    await expect(sendTutorMessage).toBeEnabled({timeout: 120_000});
    await sendTutorMessage.click();
    await expect(chat.locator(".message.assistant").last()).toContainText(/[Aa][.、]/, {timeout: 180_000});

    await page.getByLabel("新建文件路径").fill("ts-runtime-practice/src/created.ts");
    await page.getByRole("button", {name: "新建文件", exact: true}).click();
    await expect(page.getByText("已创建 ts-runtime-practice/src/created.ts")).toBeVisible();
    await expect(page.locator(".workspace-files")).toContainText("ts-runtime-practice");

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
    while (completed < initialProgress.total) {
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
        if (completed === 1) {
            await expect(page.getByRole("heading", {name: "选择题检查"})).toBeVisible({timeout: 120_000});
            const choiceOptions = page.locator(".stored-choice-option");
            await expect(choiceOptions).toHaveCount(4);

            let choicePassed = false;
            for (let optionIndex = 0; optionIndex < 4 && !choicePassed; optionIndex++) {
                await choiceOptions.nth(optionIndex).click();
                const [choiceResponse] = await Promise.all([
                    page.waitForResponse((response) =>
                        response.request().method() === "POST"
                        && response.url().includes("/practice/tasks/")
                        && response.url().endsWith("/choice/verify")),
                    page.getByRole("button", {name: "提交答案", exact: true}).click(),
                ]);
                expect(choiceResponse.ok()).toBe(true);
                const choiceResult = await choiceResponse.json() as {
                    verified: boolean;
                    choiceCorrect: boolean;
                    advanced: boolean;
                };
                if (!choiceResult.verified) {
                    await expect(page.locator(".choice-feedback")).toContainText("还不对");
                    continue;
                }
                expect(choiceResult.choiceCorrect).toBe(true);
                expect(choiceResult.advanced).toBe(true);
                choicePassed = true;
                await expect(page.getByRole("heading", {name: "选择题检查"})).toHaveCount(0);
                await expect(page.locator(".learn-heading .journey-status"))
                    .toHaveText(`${completed} / ${initialProgress.total}`, {timeout: 120_000});
            }
            expect(choicePassed).toBe(true);
        }

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
