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

test("Tutor assessment waits for learner confirmation before advancing Learn Mode", async ({page}) => {
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

    await expect(page.getByRole("button", {name: "提交检查", exact: true}))
        .toBeVisible({timeout: 120_000});
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
    const editorToggle = page.getByRole("button", {name: "展开内置编辑器"});
    await expect(editorToggle).toHaveAttribute("aria-expanded", "false");
    await expect(page.locator(".workspace-editor .monaco-editor").first()).toBeHidden();
    await page.getByLabel("发送给 TutorAgent").fill(
        "我理解变量类型标注能限制变量可保存的值；这次编译错误是我故意写错，用来观察编译器反馈。",
    );
    await page.getByRole("button", {name: "发送", exact: true}).click();
    await expect(chat.locator(".message.assistant").last()).toContainText("已进入", {timeout: 30_000});
    await editorToggle.click();
    await page.getByRole("button", {name: "index.ts", exact: true}).click();
    await expect(page.locator(".workspace-path")).toHaveText("src/index.ts");
    await replaceEditor(page, 'export const answer: number = "broken";');
    await page.getByRole("button", {name: "保存", exact: true}).click();
    await expect(page.getByRole("button", {name: "保存", exact: true})).toBeDisabled();
    await page.locator(".practice-workspace-controls")
        .getByRole("button", {name: "提交检查", exact: true}).click();

    const assessment = page.locator(".practice-assessment.ready");
    await expect(assessment).toBeVisible({timeout: 120_000});
    await expect(assessment).toContainText("编译未通过");
    await expect(assessment).toContainText("你说明了变量类型标注的作用");
    await expect(page.locator(".learn-heading .journey-status"))
        .toHaveText(`0 / ${initialProgress.total}`);

    await page.getByRole("button", {name: "确认通过并进入下一课"}).click();
    await expect(page.locator(".learn-heading .journey-status"))
        .toHaveText(`1 / ${initialProgress.total}`, {timeout: 120_000});
    await expect(page.locator(".unit-label")).toHaveText("当前 LearnUnit：functions", {timeout: 120_000});
});
