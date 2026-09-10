import type {ChangeEvent} from "react";
import {useRef, useState} from "react";
import {invoke, isTauri} from "@tauri-apps/api/core";
import {api, ApiError, type BackendHealth, type JourneyDetail, type LearnUnitResponse,} from "../lib/api";

export type BackendStatus = { status: string; detail?: string };

export type ImportedState = {
    health: BackendHealth;
    journey: JourneyDetail | null;
    learnUnit: LearnUnitResponse | null;
    view: "welcome" | "dashboard";
};

type UseDatabaseTransferOptions = {
    setBackend: (backend: BackendStatus) => void;
    setError: (error: string | null) => void;
    errorMessage: (cause: unknown, fallback: string) => string;
    resetLearningState: () => void;
    restoreImportedState: (state: ImportedState) => void;
};

function databaseErrorMessage(
    cause: unknown,
    fallback: string,
    errorMessage: (cause: unknown, fallback: string) => string,
) {
    if (!(cause instanceof ApiError)) return errorMessage(cause, fallback);
    switch (cause.payload.error) {
        case "database_agent_busy":
            return "TutorAgent 正在运行，请等待本次调用结束后重试导入。";
        case "database_transfer_busy":
            return "已有数据库导入或导出正在进行，请稍后重试。";
        case "database_import_schema_unknown":
            return "数据库 schema 未知或不兼容，当前数据库未改变。";
        case "database_import_schema_version":
            return "数据库 schema 版本不受支持，当前数据库未改变。";
        case "database_import_invalid_file":
            return "数据库文件损坏或不是有效快照，当前数据库未改变。";
        case "database_import_backup_failed":
            return "无法创建导入前备份，当前数据库未改变。";
        case "database_import_replace_failed":
            return "数据库替换失败，原数据库已恢复。";
        default:
            return cause.message || fallback;
    }
}

export function useDatabaseTransfer({
                                        setBackend,
                                        setError,
                                        errorMessage,
                                        resetLearningState,
                                        restoreImportedState,
                                    }: UseDatabaseTransferOptions) {
    const [exportStatus, setExportStatus] = useState<"idle" | "exporting" | "success" | "error">("idle");
    const [exportMessage, setExportMessage] = useState("");
    const [importStatus, setImportStatus] = useState<"idle" | "importing" | "success" | "warning" | "error">("idle");
    const [importMessage, setImportMessage] = useState("");
    const importInput = useRef<HTMLInputElement | null>(null);

    async function exportDatabase() {
        setExportStatus("exporting");
        setExportMessage("正在导出…");
        try {
            await api.exportDatabase();
            setExportStatus("success");
            setExportMessage("数据库已导出");
        } catch (cause) {
            setExportStatus("error");
            setExportMessage(databaseErrorMessage(cause, "数据库导出失败", errorMessage));
        }
    }

    async function waitForBackendHealth() {
        let lastError: unknown;
        for (let attempt = 0; attempt < 30; attempt += 1) {
            try {
                return await api.health();
            } catch (cause) {
                lastError = cause;
                await new Promise((resolve) => window.setTimeout(resolve, 200));
            }
        }
        throw lastError ?? new Error("后端健康检查超时");
    }

    async function reloadAfterImport() {
        resetLearningState();
        const health = await waitForBackendHealth();
        const journeys = await api.journeys();
        const existing = journeys[0];
        if (!existing) {
            restoreImportedState({health, journey: null, learnUnit: null, view: "welcome"});
            return;
        }
        const journey = await api.journey(existing.id);
        const current = journey.path.find((item) => item.status === "CURRENT");
        const learnUnit = current ? await api.learnUnit(existing.id, current.learnUnitCode) : null;
        restoreImportedState({health, journey, learnUnit, view: journey.path.length ? "dashboard" : "welcome"});
    }

    async function importDatabase(event: ChangeEvent<HTMLInputElement>) {
        const file = event.target.files?.[0];
        event.target.value = "";
        if (!file) return;
        setImportStatus("importing");
        setImportMessage("正在验证并导入…");
        setError(null);
        try {
            let result;
            try {
                // 第一次请求有意保持非破坏性，用于识别过期快照。
                result = await api.importDatabase(file);
            } catch (cause) {
                if (!(cause instanceof ApiError) || cause.payload.error !== "database_import_stale") throw cause;
                setImportStatus("warning");
                setImportMessage(`快照时间 ${cause.payload.snapshotCreatedAt ?? "未知"}，当前数据库时间 ${cause.payload.currentDatabaseAt ?? "未知"}。请确认是否覆盖当前进度。`);
                if (!window.confirm("这是较旧的数据库快照。确认后将覆盖当前数据库，是否继续？")) return;
                setImportStatus("importing");
                setImportMessage("正在确认并导入…");
                // 只有学习者显式确认后，才允许执行破坏性的数据库替换。
                result = await api.importDatabase(file, true);
            }
            if (result.restartRequired && !isTauri()) {
                resetLearningState();
                setImportStatus("warning");
                setImportMessage("数据库已导入。开发模式需要手动重启本地后端后，页面才会重新加载。");
                return;
            }
            setImportStatus("success");
            setImportMessage(result.restartRequired ? "导入成功，正在重新加载…" : "数据库已导入");
            if (result.restartRequired) {
                try {
                    // SQLite 由 Java 边界负责；Tauri 只重启受管的 JVM 进程。
                    await invoke("stop_backend");
                    setBackend(await invoke<BackendStatus>("start_backend"));
                } catch (cause) {
                    resetLearningState();
                    setImportStatus("error");
                    setImportMessage(`数据库已导入，但后端重启失败，请手动重启后继续：${errorMessage(cause, "重启失败")}`);
                    return;
                }
            }
            await reloadAfterImport();
        } catch (cause) {
            setImportStatus("error");
            setImportMessage(databaseErrorMessage(cause, "数据库导入失败，原数据库未改变", errorMessage));
        }
    }

    return {
        exportStatus,
        exportMessage,
        importStatus,
        importMessage,
        importInput,
        exportDatabase,
        importDatabase,
    };
}
