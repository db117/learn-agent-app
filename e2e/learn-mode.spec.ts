import {expect, type Page, test} from "@playwright/test";

const firstUnitCode = "variables";
const secondUnitCode = "functions";

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
    await page.getByRole("button", {name: "完成设计"}).click();

    await expect(page.getByRole("heading", {name: "代码练习"})).toBeVisible({timeout: 120_000});
    await expect(page.getByRole("heading", {name: "Concept"})).toBeVisible();
    await expect(page.getByRole("heading", {name: "Example"})).toBeVisible();
    await expect(page.getByRole("heading", {name: "Practice"})).toBeVisible();
    await expect(page.locator(".unit-label")).toHaveText(`当前 LearnUnit：${firstUnitCode}`);
    await expect(chat).toContainText(firstUnitCode);
    await page.getByRole("button", {name: "index.ts", exact: true}).click();
    await expect(page.locator(".workspace-path")).toHaveText("src/index.ts");

    await replaceEditor(page, 'export const answer: number = "broken";');
    await expect(page.locator(".workspace-path")).toContainText("未保存");
    await page.getByRole("button", {name: "保存", exact: true}).click();
    await expect(page.getByRole("button", {name: "保存", exact: true})).toBeDisabled();

    await page.getByRole("button", {name: "验证并记录"}).click();
    await expect(page.locator("p.form-feedback").filter({hasText: "Practice 尚未通过"}))
        .toBeVisible({timeout: 120_000});
    await expect(page.locator(".unit-label")).toHaveText(`当前 LearnUnit：${firstUnitCode}`);

    await replaceEditor(page, "export const answer: number = 42;");
    await page.getByRole("button", {name: "保存", exact: true}).click();
    await expect(page.getByRole("button", {name: "保存", exact: true})).toBeDisabled();
    await page.getByRole("button", {name: "验证并记录"}).click();

    await expect(page.locator(".unit-label")).toHaveText(`当前 LearnUnit：${secondUnitCode}`, {
        timeout: 120_000,
    });
    await expect(page.getByRole("heading", {name: "函数", exact: true})).toBeVisible();
    await expect(chat).toContainText(secondUnitCode);

    await expect(page.getByRole("button", {name: "验证并记录"})).toBeEnabled();
    await page.getByRole("button", {name: "验证并记录"}).click();
    await expect(page.getByRole("heading", {name: "学习路径已完成"}).first()).toBeVisible({
        timeout: 120_000,
    });
    await expect(page.getByText("已完成 2 / 2 个 LearnUnit。").first()).toBeVisible();
});
