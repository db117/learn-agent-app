import {expect, type Page, test} from "@playwright/test";
import {DatabaseSync} from "node:sqlite";
import {mkdir, readFile, writeFile} from "node:fs/promises";
import path from "node:path";

const backend = "http://127.0.0.1:10707";

function withDatabase<T>(databasePath: string, action: (database: DatabaseSync) => T): T {
    const database = new DatabaseSync(databasePath);
    database.exec("PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");
    try {
        return action(database);
    } finally {
        database.close();
    }
}

async function databasePathForJourney(page: Page, journeyId: number) {
    const response = await page.request.get(`${backend}/api/journeys/${journeyId}/workspace/path`);
    expect(response.ok()).toBeTruthy();
    return path.resolve(await response.text(), "../../../db/learn-agent.db");
}

function seedProgressAndProject(
    database: DatabaseSync,
    learningJourneyId: number,
    name: string,
    unitCode: string,
) {
    const unit = database.prepare(
        "SELECT id FROM learn_unit WHERE journey_id = ? AND code = ?",
    ).get(learningJourneyId, unitCode) as { id: number } | undefined;
    if (!unit) throw new Error(`找不到 ${name} Journey 的 ${unitCode} LearnUnit`);

    const createdAt = new Date().toISOString();
    const digest = "a".repeat(64);
    const task = database.prepare(`
        INSERT INTO practice_task(
            journey_id, learn_unit_id, language_pack_id, type, title, description, difficulty,
            starter_template, choice_question, verification_policy, status, created_at)
        VALUES (?, ?, 'typescript', 'CODE', ?, '迁移验证练习', 1, 'export {};', NULL,
            '{"requireCompile":true,"requireTests":false,"requireLint":false,"requireRuntime":false,"requireChoice":false}',
            'OPEN', ?)
    `).run(learningJourneyId, unit.id, `${name} 练习`, createdAt);
    const practiceTaskId = Number(task.lastInsertRowid);
    const attempt = database.prepare(
        "INSERT INTO practice_attempt(practice_task_id, submitted_at) VALUES (?, ?)",
    ).run(practiceTaskId, createdAt);
    const practiceAttemptId = Number(attempt.lastInsertRowid);
    database.prepare(`
        INSERT INTO practice_evidence(
            attempt_id, compile_passed, tests_passed, test_count, lint_passed, runtime_result,
            submitted_files, verified_at, choice_correct, workspace_digest)
        VALUES (?, 1, 1, 2, 0, 'NOT_RUN', '["src/index.ts"]', ?, 0, ?)
    `).run(practiceAttemptId, createdAt, digest);
    const assessment = database.prepare(`
        INSERT INTO practice_assessment(
            journey_id, learn_unit_id, practice_task_id, practice_attempt_id, verdict,
            rationale, workspace_digest, created_at)
        VALUES (?, ?, ?, ?, 'READY', ?, ?, ?)
    `).run(learningJourneyId, unit.id, practiceTaskId, practiceAttemptId,
        `${name} 的代码评估`, digest, createdAt);

    const project = database.prepare(`
        INSERT INTO project(journey_id, title, status, created_at, completed_at)
        VALUES (?, ?, 'ACTIVE', ?, NULL)
    `).run(learningJourneyId, `${name} 本地项目`, createdAt);
    const projectId = Number(project.lastInsertRowid);
    const milestone = database.prepare(`
        INSERT INTO project_milestone(project_id, code, title, sequence, status)
        VALUES (?, 'first-step', '第一步', 0, 'IN_PROGRESS')
    `).run(projectId);
    database.prepare(`
        INSERT INTO project_evidence(
            milestone_id, artifact_reference, verification_summary, passed, verified_at)
        VALUES (?, ?, ?, 0, ?)
    `).run(Number(milestone.lastInsertRowid), `${name}/draft`, `${name} 的未通过项目证据`, createdAt);

    return {
        projectId,
        assessmentId: Number(assessment.lastInsertRowid),
        attemptId: practiceAttemptId,
    };
}

function completeUnit(database: DatabaseSync, learningJourneyId: number, unitCode: string) {
    const unit = database.prepare(
        "SELECT id FROM learn_unit WHERE journey_id = ? AND code = ?",
    ).get(learningJourneyId, unitCode) as { id: number } | undefined;
    if (!unit) throw new Error(`找不到已完成的 ${unitCode} LearnUnit`);

    const completedAt = new Date().toISOString();
    const task = database.prepare(`
        INSERT INTO practice_task(
            journey_id, learn_unit_id, language_pack_id, type, title, description, difficulty,
            starter_template, choice_question, verification_policy, status, created_at)
        VALUES (?, ?, 'typescript', 'CODE', ?, '已完成学习项的练习', 1, 'export {};', NULL,
            '{"requireCompile":true,"requireTests":false,"requireLint":false,"requireRuntime":false,"requireChoice":false}',
            'VERIFIED', ?)
    `).run(learningJourneyId, unit.id, `${unitCode} 已完成练习`, completedAt);
    const attempt = database.prepare(
        "INSERT INTO practice_attempt(practice_task_id, submitted_at) VALUES (?, ?)",
    ).run(Number(task.lastInsertRowid), completedAt);
    database.prepare(`
        INSERT INTO practice_evidence(
            attempt_id, compile_passed, tests_passed, test_count, lint_passed, runtime_result,
            submitted_files, verified_at, choice_correct, workspace_digest)
        VALUES (?, 1, 0, 0, 0, 'NOT_RUN', '["src/index.ts"]', ?, 0, ?)
    `).run(Number(attempt.lastInsertRowid), completedAt, "b".repeat(64));
    database.prepare(`
        UPDATE learning_path_item
        SET status = 'COMPLETED', practice_verified = 1, pass_reason = 'PRACTICE_EVIDENCE',
            completed_at = ?, updated_at = ?
        WHERE journey_id = ? AND learn_unit_code = ?
    `).run(completedAt, completedAt, learningJourneyId, unitCode);
    database.prepare(`
        UPDATE learning_path_item
        SET status = 'CURRENT', started_at = ?, updated_at = ?
        WHERE journey_id = ? AND status = 'PENDING' AND sequence = (
            SELECT MIN(sequence) FROM learning_path_item WHERE journey_id = ? AND status = 'PENDING')
    `).run(completedAt, completedAt, learningJourneyId, learningJourneyId);
}

function deleteJourneyData(database: DatabaseSync, journeyId: number) {
    const target = database.prepare(
        "SELECT learning_journey_id FROM journey WHERE id = ?",
    ).get(journeyId) as { learning_journey_id: number | null } | undefined;
    if (!target) throw new Error("用于导入测试的 Journey 不存在");
    database.exec("BEGIN");
    try {
        if (target.learning_journey_id !== null) {
            const learningJourneyId = target.learning_journey_id;
            for (const [table, column] of [
                ["learning_path_item", "journey_id"],
                ["practice_assessment", "journey_id"],
                ["project", "journey_id"],
                ["practice_task", "journey_id"],
                ["learn_unit", "journey_id"],
                ["chapter", "journey_id"],
                ["learning_journey", "id"],
            ]) {
                database.prepare(`DELETE FROM ${table} WHERE ${column} = ?`).run(learningJourneyId);
            }
        }
        database.prepare("DELETE FROM journey WHERE id = ?").run(journeyId);
        database.exec("COMMIT");
    } catch (error) {
        database.exec("ROLLBACK");
        throw error;
    }
}

const plan = {
    chapters: [{
        code: "basics",
        title: "基础",
        units: [
            {
                code: "variables",
                title: "变量",
                objective: "理解变量",
                concept: "变量保存数据。",
                example: "const answer = 42;",
                practice: "定义一个变量。",
            },
            {
                code: "functions",
                title: "函数",
                objective: "理解函数",
                concept: "函数封装可复用逻辑。",
                example: "function answer() { return 42; }",
                practice: "定义一个函数。",
            },
        ],
    }],
};

test("从对象存储同步已有 LearnUnit 内容时不请求 Tutor 生成", async ({page}) => {
    let tutorMessageRequestCount = 0;
    page.on("request", (request) => {
        const requestPath = new URL(request.url()).pathname;
        if (request.method() === "POST" && /^\/api\/tutor\/sessions\/[^/]+\/messages$/.test(requestPath)) {
            tutorMessageRequestCount += 1;
        }
    });

    const learner = await page.request.put(`${backend}/api/learner`, {
        data: {backgroundSummary: "TypeScript 学习者"},
    });
    expect(learner.ok()).toBeTruthy();
    await page.goto("/");
    await expect(page.getByRole("button", {name: "数据迁移"})).toBeVisible();

    const created = await page.request.post(`${backend}/api/journeys`, {
        data: {goalDescription: "复用已有的 LearnUnit 内容"},
    });
    expect(created.ok()).toBeTruthy();
    const journey = await created.json() as { id: number };
    try {
        const confirmed = await page.request.post(`${backend}/api/journeys/${journey.id}/confirm-plan`, {
            data: {plan: JSON.stringify(plan)},
        });
        expect(confirmed.ok()).toBeTruthy();
        const databasePath = await databasePathForJourney(page, journey.id);
        withDatabase(databasePath, (database) => {
            database.prepare(`
                UPDATE learn_unit SET content = ?
                WHERE journey_id = (SELECT learning_journey_id FROM journey WHERE id = ?) AND code = ?
            `).run("## Concept\n已有讲解。\n\n## Example\nconst answer = 42;\n\n## Practice\n定义一个变量。", journey.id, "variables");
        });
        const archiveResponse = await page.request.get(`${backend}/api/transfer/journeys`);
        expect(archiveResponse.ok()).toBeTruthy();
        const archive = await archiveResponse.body();

        await page.route(`${backend}/api/transfer/r2/config`, (route) => route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({
                configured: true,
                endpoint: "https://objects.example.com",
                region: "auto",
                bucketName: "learn-app",
                accessKeyId: "browser-test",
            }),
        }));
        await page.route(`${backend}/api/transfer/r2/status`, (route) => route.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify({configured: true, remoteLastModified: "2026-09-29T00:00:00Z"}),
        }));
        await page.route(`${backend}/api/transfer/r2/download`, (route) => route.fulfill({
            status: 200,
            contentType: "application/zip",
            body: archive,
        }));

        await page.getByRole("button", {name: "数据迁移"}).click();
        await page.getByRole("button", {name: "从对象存储同步到本机"}).click();
        await expect(page.getByRole("heading", {name: "确认同步到本机"})).toBeVisible();
        await page.getByRole("button", {name: "确认同步到本机"}).click();
        await expect(page.getByRole("status").filter({hasText: "从对象存储同步完成"})).toBeVisible();
        await expect(page.getByText("当前 LearnUnit：variables")).toBeVisible();
        await page.waitForTimeout(250);
        expect(tutorMessageRequestCount).toBe(0);
    } finally {
        const databasePath = await databasePathForJourney(page, journey.id);
        withDatabase(databasePath, (database) => deleteJourneyData(database, journey.id));
    }
});

test("Journey 数据和模型配置可以从文件导出并导入", async ({page}) => {
    const learner = await page.request.put(`${backend}/api/learner`, {
        data: {backgroundSummary: "TypeScript 学习者"},
    });
    expect(learner.ok()).toBeTruthy();

    const created = await page.request.post(`${backend}/api/journeys`, {
        data: {goalDescription: "从文件恢复学习进度"},
    });
    expect(created.ok()).toBeTruthy();
    const sourceJourney = await created.json() as { id: number };
    const confirmed = await page.request.post(`${backend}/api/journeys/${sourceJourney.id}/confirm-plan`, {
        data: {plan: JSON.stringify(plan)},
    });
    expect(confirmed.ok()).toBeTruthy();

    const sourceProgressResponse = await page.request.get(`${backend}/api/journeys/${sourceJourney.id}/learning`);
    const sourceProgress = await sourceProgressResponse.json() as { learningJourneyId: number };
    const databasePath = await databasePathForJourney(page, sourceJourney.id);
    withDatabase(databasePath, (database) => {
        completeUnit(database, sourceProgress.learningJourneyId, "variables");
    });
    const sourceProgressAfterCompletion = await page.request.get(
        `${backend}/api/journeys/${sourceJourney.id}/learning`,
    );
    expect(await sourceProgressAfterCompletion.json()).toMatchObject({
        currentLearnUnitCode: "functions",
        completedCount: 1,
    });
    const sourceIds = withDatabase(databasePath, (database) =>
        seedProgressAndProject(database, sourceProgress.learningJourneyId, "来源", "functions"));

    await page.request.put(`${backend}/api/journeys/${sourceJourney.id}/workspace/files/notes.txt`, {
        data: {content: "导出时的内容"},
    });
    const sourceWorkspacePath = await page.request.get(
        `${backend}/api/journeys/${sourceJourney.id}/workspace/path`,
    ).then((response) => response.text());
    withDatabase(databasePath, (database) => {
        database.prepare("UPDATE journey SET portable_id = upper(portable_id) WHERE id = ?")
            .run(sourceJourney.id);
    });
    await mkdir(path.join(sourceWorkspacePath, ".cache"), {recursive: true});
    await writeFile(path.join(sourceWorkspacePath, ".cache/generated.json"), "{}", "utf8");
    await page.request.put(`${backend}/api/projects/${sourceIds.projectId}/workspace/files/project.txt`, {
        data: {content: "导出时的项目文件"},
    });

    const archivedJourneyResponse = await page.request.post(`${backend}/api/journeys`, {
        data: {goalDescription: "已归档的学习记录"},
    });
    expect(archivedJourneyResponse.ok()).toBeTruthy();
    const archivedJourney = await archivedJourneyResponse.json() as { id: number };
    withDatabase(databasePath, (database) => {
        database.prepare(`
            UPDATE journey SET status = 'ARCHIVED', archived_at = ?, is_current = 0 WHERE id = ?
        `).run(new Date(Date.now() + 1000).toISOString(), archivedJourney.id);
    });

    const sourceAssessmentResponse = await page.request.get(
        `${backend}/api/journeys/${sourceJourney.id}/practice/assessment`,
    );
    expect(sourceAssessmentResponse.ok()).toBeTruthy();
    const sourceAssessment = await sourceAssessmentResponse.json() as {
        assessmentId: number;
        attemptId: number;
        available: boolean;
    };
    expect(sourceAssessment).toMatchObject({
        assessmentId: sourceIds.assessmentId,
        attemptId: sourceIds.attemptId,
        available: true,
    });

    await page.goto("/");
    await page.getByRole("button", {name: "数据迁移"}).click();
    const [journeyDownload] = await Promise.all([
        page.waitForEvent("download"),
        page.getByRole("button", {name: "导出学习数据"}).click(),
    ]);
    const journeyArchive = await readFile(await journeyDownload.path());

    const targetOnlyResponse = await page.request.post(`${backend}/api/journeys`, {
        data: {goalDescription: "只存在于目标设备"},
    });
    expect(targetOnlyResponse.ok()).toBeTruthy();
    const targetOnlyJourney = await targetOnlyResponse.json() as { id: number };
    const targetSelected = await page.request.post(
        `${backend}/api/journeys/${targetOnlyJourney.id}/select`,
    );
    expect(targetSelected.ok()).toBeTruthy();
    const targetPlan = await page.request.post(`${backend}/api/journeys/${targetOnlyJourney.id}/confirm-plan`, {
        data: {plan: JSON.stringify(plan)},
    });
    expect(targetPlan.ok()).toBeTruthy();
    const targetProgressResponse = await page.request.get(
        `${backend}/api/journeys/${targetOnlyJourney.id}/learning`,
    );
    const targetProgress = await targetProgressResponse.json() as { learningJourneyId: number };
    const targetIds = withDatabase(databasePath, (database) =>
        seedProgressAndProject(database, targetProgress.learningJourneyId, "目标", "variables"));
    await page.request.put(`${backend}/api/journeys/${targetOnlyJourney.id}/workspace/files/target.txt`, {
        data: {content: "目标设备自己的文件"},
    });
    await page.request.put(`${backend}/api/projects/${targetIds.projectId}/workspace/files/project.txt`, {
        data: {content: "目标设备自己的项目文件"},
    });

    withDatabase(databasePath, (database) => {
        deleteJourneyData(database, sourceJourney.id);
        deleteJourneyData(database, archivedJourney.id);
    });
    await page.locator("#journey-transfer-file").setInputFiles({
        name: "journeys.zip",
        mimeType: "application/zip",
        buffer: journeyArchive,
    });
    await page.getByRole("button", {name: "确认导入"}).click();
    await expect(page.getByRole("status").filter({hasText: "导入完成"})).toBeVisible();

    const bootstrap = await page.request.get(`${backend}/api/bootstrap`);
    const bootstrapBody = await bootstrap.json() as {
        journeys: Array<{ id: number; goalDescription: string; status: string }>;
    };
    const importedJourney = bootstrapBody.journeys.find(
        (item) => item.goalDescription === "从文件恢复学习进度",
    );
    expect(importedJourney).toBeDefined();
    expect(importedJourney!.id).not.toBe(sourceJourney.id);
    const importedArchivedJourney = bootstrapBody.journeys.find(
        (item) => item.goalDescription === "已归档的学习记录",
    );
    expect(importedArchivedJourney).toMatchObject({status: "ARCHIVED"});
    expect(importedArchivedJourney!.id).not.toBe(archivedJourney.id);
    expect(bootstrapBody.journeys.map((item) => item.goalDescription))
        .toContain("只存在于目标设备");

    const importedProgressResponse = await page.request.get(
        `${backend}/api/journeys/${importedJourney!.id}/learning`,
    );
    const importedProgress = await importedProgressResponse.json() as {
        currentLearnUnitCode: string;
        learningJourneyId: number;
        completedCount: number;
    };
    expect(importedProgress).toMatchObject({currentLearnUnitCode: "functions", completedCount: 1});
    expect(importedProgress.learningJourneyId).not.toBe(sourceProgress.learningJourneyId);
    const importedWorkspace = await page.request.get(
        `${backend}/api/journeys/${importedJourney!.id}/workspace/files/notes.txt`,
    );
    expect(await importedWorkspace.json()).toMatchObject({content: "导出时的内容"});
    const excludedCache = await page.request.get(
        `${backend}/api/journeys/${importedJourney!.id}/workspace/files/.cache/generated.json`,
    );
    expect(excludedCache.status()).toBe(404);

    const importedAssessmentResponse = await page.request.get(
        `${backend}/api/journeys/${importedJourney!.id}/practice/assessment`,
    );
    const importedAssessment = await importedAssessmentResponse.json() as {
        assessmentId: number;
        attemptId: number;
        available: boolean;
        compilePassed: boolean;
        testsPassed: boolean;
    };
    expect(importedAssessment).toMatchObject({available: true, compilePassed: true, testsPassed: true});
    expect(importedAssessment.assessmentId).not.toBe(sourceAssessment.assessmentId);
    expect(importedAssessment.attemptId).not.toBe(sourceAssessment.attemptId);

    const importedProject = withDatabase(databasePath, (database) => {
        const project = database.prepare(
            "SELECT id, title FROM project WHERE journey_id = ?",
        ).get(importedProgress.learningJourneyId) as { id: number; title: string } | undefined;
        const evidence = project && database.prepare(`
            SELECT m.code, m.status, e.artifact_reference, e.passed
            FROM project_milestone m JOIN project_evidence e ON e.milestone_id = m.id
            WHERE m.project_id = ?
        `).get(project.id) as {
            code: string;
            status: string;
            artifact_reference: string;
            passed: number;
        } | undefined;
        return {project, evidence};
    });
    expect(importedProject.project).toMatchObject({title: "来源 本地项目"});
    expect(importedProject.project!.id).not.toBe(sourceIds.projectId);
    expect(importedProject.project!.id).not.toBe(targetIds.projectId);
    expect(importedProject.evidence).toMatchObject({
        code: "first-step",
        status: "IN_PROGRESS",
        artifact_reference: "来源/draft",
        passed: 0,
    });
    const importedProjectFile = await page.request.get(
        `${backend}/api/projects/${importedProject.project!.id}/workspace/files/project.txt`,
    );
    expect(await importedProjectFile.json()).toMatchObject({content: "导出时的项目文件"});

    const retainedTargetProgress = await page.request.get(
        `${backend}/api/journeys/${targetOnlyJourney.id}/learning`,
    );
    expect((await retainedTargetProgress.json()).currentLearnUnitCode).toBe("variables");
    const retainedTargetFile = await page.request.get(
        `${backend}/api/projects/${targetIds.projectId}/workspace/files/project.txt`,
    );
    expect(await retainedTargetFile.json()).toMatchObject({content: "目标设备自己的项目文件"});

    await page.request.put(`${backend}/api/journeys/${importedJourney!.id}/workspace/files/notes.txt`, {
        data: {content: "本地修改后的文件"},
    });
    await page.request.put(
        `${backend}/api/projects/${importedProject.project!.id}/workspace/files/project.txt`,
        {data: {content: "本地修改后的项目文件"}},
    );
    withDatabase(databasePath, (database) => {
        database.prepare("UPDATE practice_evidence SET compile_passed = 0 WHERE attempt_id = ?")
            .run(importedAssessment.attemptId);
        database.prepare("UPDATE project SET title = '目标设备上的项目改动' WHERE id = ?")
            .run(importedProject.project!.id);
    });
    await page.locator("#journey-transfer-file").setInputFiles({
        name: "journeys.zip",
        mimeType: "application/zip",
        buffer: journeyArchive,
    });
    await page.getByRole("button", {name: "确认导入"}).click();
    await expect(page.getByRole("status").filter({hasText: "导入完成"})).toBeVisible();

    const restoredProgress = await page.request.get(
        `${backend}/api/journeys/${importedJourney!.id}/learning`,
    );
    const restoredLearning = await restoredProgress.json() as {
        currentLearnUnitCode: string;
        completedCount: number;
        learningJourneyId: number;
    };
    expect(restoredLearning).toMatchObject({currentLearnUnitCode: "functions", completedCount: 1});
    const restoredWorkspace = await page.request.get(
        `${backend}/api/journeys/${importedJourney!.id}/workspace/files/notes.txt`,
    );
    expect(await restoredWorkspace.json()).toMatchObject({content: "导出时的内容"});
    const restoredAssessment = await page.request.get(
        `${backend}/api/journeys/${importedJourney!.id}/practice/assessment`,
    );
    const restoredAssessmentBody = await restoredAssessment.json() as {
        available: boolean;
        attemptId: number;
        compilePassed: boolean;
    };
    expect(restoredAssessmentBody).toMatchObject({available: true, compilePassed: true});
    const replacedProject = withDatabase(databasePath, (database) =>
        database.prepare("SELECT id, title FROM project WHERE journey_id = ?")
            .get(restoredLearning.learningJourneyId) as { id: number; title: string } | undefined);
    expect(replacedProject).toMatchObject({title: "来源 本地项目"});
    const restoredProjectFile = await page.request.get(
        `${backend}/api/projects/${replacedProject!.id}/workspace/files/project.txt`,
    );
    expect(await restoredProjectFile.json()).toMatchObject({content: "导出时的项目文件"});

    await page.request.put(`${backend}/api/journeys/${importedJourney!.id}/workspace/files/notes.txt`, {
        data: {content: "取消覆盖后应保留"},
    });
    await page.request.put(`${backend}/api/projects/${replacedProject!.id}/workspace/files/project.txt`, {
        data: {content: "取消后保留的项目文件"},
    });
    withDatabase(databasePath, (database) => {
        database.prepare("UPDATE practice_evidence SET compile_passed = 0 WHERE attempt_id = ?")
            .run(restoredAssessmentBody.attemptId);
        database.prepare("UPDATE project SET title = '取消覆盖时的本地项目' WHERE id = ?")
            .run(replacedProject!.id);
    });
    await page.locator("#journey-transfer-file").setInputFiles({
        name: "journeys.zip",
        mimeType: "application/zip",
        buffer: journeyArchive,
    });
    await page.getByRole("button", {name: "取消", exact: true}).click();
    await expect(page.getByRole("status").filter({hasText: "已取消导入"})).toBeVisible();
    const canceled = await page.request.get(
        `${backend}/api/journeys/${importedJourney!.id}/workspace/files/notes.txt`,
    );
    expect(await canceled.json()).toMatchObject({content: "取消覆盖后应保留"});
    const canceledProjectFile = await page.request.get(
        `${backend}/api/projects/${replacedProject!.id}/workspace/files/project.txt`,
    );
    expect(await canceledProjectFile.json()).toMatchObject({content: "取消后保留的项目文件"});
    const canceledAssessment = await page.request.get(
        `${backend}/api/journeys/${importedJourney!.id}/practice/assessment`,
    );
    expect(await canceledAssessment.json()).toMatchObject({available: true, compilePassed: false});
    const canceledProject = withDatabase(databasePath, (database) =>
        database.prepare("SELECT title FROM project WHERE id = ?")
            .get(replacedProject!.id) as { title: string });
    expect(canceledProject.title).toBe("取消覆盖时的本地项目");

    await page.route(`${backend}/api/transfer/r2/config`, (route) => route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
            configured: true,
            endpoint: "https://objects.example.com",
            region: "auto",
            bucketName: "learn-app",
            accessKeyId: "browser-test",
        }),
    }));
    await page.route(`${backend}/api/transfer/r2/status`, (route) => route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({configured: true, remoteLastModified: "2026-09-29T00:00:00Z"}),
    }));
    await page.route(`${backend}/api/transfer/r2/download`, (route) => route.fulfill({
        status: 200,
        contentType: "application/zip",
        body: journeyArchive,
    }));
    await page.getByRole("button", {name: "关闭数据迁移"}).click();
    await page.getByRole("button", {name: "数据迁移"}).click();
    await page.getByRole("button", {name: "从对象存储同步到本机"}).click();
    await expect(page.getByRole("heading", {name: "确认同步到本机"})).toBeVisible();
    await page.getByRole("button", {name: "取消", exact: true}).click();
    await expect(page.getByRole("status").filter({hasText: "已取消从对象存储同步"})).toBeVisible();
    await page.getByRole("button", {name: "从对象存储同步到本机"}).click();
    await expect(page.getByRole("button", {name: "确认同步到本机"})).toBeVisible();
    await page.getByRole("button", {name: "确认同步到本机"}).click();
    await expect(page.getByRole("status").filter({hasText: "从对象存储同步完成"})).toBeVisible();

    const configWrite = await page.request.put(`${backend}/api/model-config`, {
        data: {
            modelName: "transfer-source-model",
            protocol: "CHAT_COMPLETIONS",
            baseUrl: "http://127.0.0.1:19090/v1",
            apiKey: "transfer-test-secret",
            clearApiKey: true,
        },
    });
    expect(configWrite.ok()).toBeTruthy();
    const [configDownload] = await Promise.all([
        page.waitForEvent("download"),
        page.getByRole("button", {name: "导出模型配置"}).click(),
    ]);
    const configFile = await readFile(await configDownload.path());
    expect(configFile.toString("utf8")).toContain("transfer-test-secret");

    await page.request.put(`${backend}/api/model-config`, {
        data: {
            modelName: "transfer-target-model",
            protocol: "CHAT_COMPLETIONS",
            baseUrl: "http://127.0.0.1:19090/v1",
            apiKey: "target-test-secret",
            clearApiKey: true,
        },
    });
    await page.locator("#model-config-transfer-file").setInputFiles({
        name: "model-config.json",
        mimeType: "application/json",
        buffer: configFile,
    });
    await expect(page.getByRole("status").filter({hasText: "模型配置已导入"})).toBeVisible();
    const config = await page.request.get(`${backend}/api/model-config`);
    expect(await config.json()).toMatchObject({modelName: "transfer-source-model", apiKeyConfigured: true});

    await page.locator("#journey-transfer-file").setInputFiles({
        name: "invalid.zip",
        mimeType: "application/zip",
        buffer: Buffer.from("invalid archive"),
    });
    await expect(page.getByRole("alert").filter({hasText: "导入失败"})).toBeVisible();
    const afterInvalid = await page.request.get(
        `${backend}/api/journeys/${importedJourney!.id}/workspace/files/notes.txt`,
    );
    expect(await afterInvalid.json()).toMatchObject({content: "取消覆盖后应保留"});
});
