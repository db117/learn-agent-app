import {useEffect, useRef, useState} from "react";
import type {ModelConfig} from "../model-config/ModelSettingsDialog";

const TRANSFER_URL = "http://127.0.0.1:10707/api/transfer";

type Preview = {
    additionCount: number;
    conflicts: Array<{ goalDescription: string }>;
};
type ImportResult = { added: string[]; replaced: string[] };

async function errorMessage(response: Response, fallback: string) {
    try {
        const body = await response.json() as { message?: string };
        return body.message || fallback;
    } catch {
        return fallback;
    }
}

function downloadFile(content: BlobPart, name: string, type: string) {
    const url = URL.createObjectURL(new Blob([content], {type}));
    const link = document.createElement("a");
    link.href = url;
    link.download = name;
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
}

export function DataTransferDialog({
                                       open,
                                       onClose,
                                       onModelConfigImported,
                                       onJourneysImported,
                                   }: {
    open: boolean;
    onClose: () => void;
    onModelConfigImported: (config: ModelConfig) => void;
    onJourneysImported: () => void;
}) {
    const dialogRef = useRef<HTMLDialogElement>(null);
    const [busy, setBusy] = useState(false);
    const [status, setStatus] = useState("");
    const [error, setError] = useState("");

    useEffect(() => {
        const dialog = dialogRef.current;
        if (!dialog) return;
        if (open && !dialog.open) dialog.showModal();
        if (!open && dialog.open) dialog.close();
    }, [open]);

    useEffect(() => {
        if (open) {
            setBusy(false);
            setStatus("");
            setError("");
        }
    }, [open]);

    const exportJourneys = async () => {
        setBusy(true);
        setError("");
        try {
            const response = await fetch(`${TRANSFER_URL}/journeys`);
            if (!response.ok) throw new Error(await errorMessage(response, "导出学习数据失败"));
            downloadFile(await response.blob(), "learn-agent-journeys.zip", "application/zip");
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : "导出学习数据失败");
        } finally {
            setBusy(false);
        }
    };

    const importJourneys = async (file: File) => {
        setBusy(true);
        setStatus("正在校验学习数据文件…");
        setError("");
        try {
            const archive = await file.arrayBuffer();
            const previewResponse = await fetch(`${TRANSFER_URL}/journeys/preview`, {
                method: "POST",
                headers: {"Content-Type": "application/octet-stream"},
                body: archive,
            });
            if (!previewResponse.ok) {
                throw new Error(await errorMessage(previewResponse, "学习数据文件无效"));
            }
            const preview = await previewResponse.json() as Preview;
            const confirmed = preview.conflicts.length === 0 || window.confirm(
                `发现 ${preview.conflicts.length} 个相同标识的 Journey：\n`
                + preview.conflicts.map((item) => `• ${item.goalDescription}`).join("\n")
                + "\n确认后将覆盖这些 Journey 的学习数据和工作区。",
            );
            if (!confirmed) {
                setStatus("已取消导入");
                return;
            }
            const response = await fetch(`${TRANSFER_URL}/journeys?replaceConflicts=${preview.conflicts.length > 0}`, {
                method: "POST",
                headers: {"Content-Type": "application/octet-stream"},
                body: archive,
            });
            if (!response.ok) throw new Error(await errorMessage(response, "导入学习数据失败"));
            const result = await response.json() as ImportResult;
            onJourneysImported();
            setStatus(`导入完成：新增 ${result.added.length} 个，覆盖 ${result.replaced.length} 个 Journey`);
        } catch (cause) {
            setStatus("");
            setError(cause instanceof Error ? `导入失败：${cause.message}` : "导入失败：文件无效");
        } finally {
            setBusy(false);
        }
    };

    const importModelConfiguration = async (file: File) => {
        setBusy(true);
        setStatus("正在导入模型配置…");
        setError("");
        try {
            const configuration: unknown = JSON.parse(await file.text());
            const response = await fetch(`${TRANSFER_URL}/model-config`, {
                method: "PUT",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify(configuration),
            });
            if (!response.ok) throw new Error(await errorMessage(response, "模型配置文件无效"));
            onModelConfigImported(await response.json() as ModelConfig);
            setStatus("模型配置已导入");
        } catch (cause) {
            setStatus("");
            setError(cause instanceof Error ? `导入失败：${cause.message}` : "导入失败：文件无效");
        } finally {
            setBusy(false);
        }
    };

    const exportModelConfiguration = async () => {
        setBusy(true);
        setError("");
        try {
            const response = await fetch(`${TRANSFER_URL}/model-config`);
            if (!response.ok) throw new Error(await errorMessage(response, "导出模型配置失败"));
            downloadFile(await response.blob(), "learn-agent-model-config.json", "application/json");
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : "导出模型配置失败");
        } finally {
            setBusy(false);
        }
    };

    return (
        <dialog
            className="model-settings-dialog"
            ref={dialogRef}
            aria-labelledby="transfer-title"
            onCancel={(event) => {
                event.preventDefault();
                onClose();
            }}
            onClick={(event) => {
                if (event.target === event.currentTarget) onClose();
            }}
        >
            <section className="model-settings-content">
                <header className="model-settings-header">
                    <div>
                        <h2 id="transfer-title">数据迁移</h2>
                        <p>使用文件在设备间迁移学习进度和模型配置。</p>
                    </div>
                    <button
                        className="secondary model-settings-close"
                        type="button"
                        aria-label="关闭数据迁移"
                        onClick={onClose}
                    >×
                    </button>
                </header>
                <div className="model-settings-actions">
                    <button type="button" disabled={busy} onClick={() => void exportJourneys()}>
                        导出学习数据
                    </button>
                    <button type="button" className="secondary" disabled={busy}
                            onClick={() => document.getElementById("journey-transfer-file")?.click()}>
                        导入学习数据
                    </button>
                    <button type="button" disabled={busy} onClick={() => void exportModelConfiguration()}>
                        导出模型配置
                    </button>
                    <button type="button" className="secondary" disabled={busy}
                            onClick={() => document.getElementById("model-config-transfer-file")?.click()}>
                        导入模型配置
                    </button>
                    <input
                        id="journey-transfer-file"
                        type="file"
                        accept=".zip,application/zip"
                        hidden
                        onChange={(event) => {
                            const file = event.currentTarget.files?.[0];
                            event.currentTarget.value = "";
                            if (file) void importJourneys(file);
                        }}
                    />
                    <input
                        id="model-config-transfer-file"
                        type="file"
                        accept=".json,application/json"
                        hidden
                        onChange={(event) => {
                            const file = event.currentTarget.files?.[0];
                            event.currentTarget.value = "";
                            if (file) void importModelConfiguration(file);
                        }}
                    />
                </div>
                {busy && <p className="model-settings-feedback" role="status">{status || "处理中…"}</p>}
                {!busy && status && <p className="model-settings-feedback success" role="status">{status}</p>}
                {error && <p className="model-settings-feedback error" role="alert">{error}</p>}
            </section>
        </dialog>
    );
}
