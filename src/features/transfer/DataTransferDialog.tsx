import {type FormEvent, useEffect, useRef, useState} from "react";
import type {ModelConfig} from "../model-config/ModelSettingsDialog";

const TRANSFER_URL = "http://127.0.0.1:10707/api/transfer";

type Preview = {
    journeyCount: number;
    additionCount: number;
    conflicts: Array<{ goalDescription: string }>;
    learnerWillBeReplaced: boolean;
    importedLearnerDisplayName: string | null;
    importedCurrentJourneyGoal: string | null;
};
type ImportResult = { added: string[]; replaced: string[] };
type PendingTransferConfirmation =
    | { operation: "upload" }
    | { operation: "download" | "import"; archive: ArrayBuffer; preview: Preview };
type ObjectStorageConfiguration = {
    configured: boolean;
    endpoint: string | null;
    region: string | null;
    bucketName: string | null;
    accessKeyId: string | null;
};
type ObjectStorageStatus = { configured: boolean; remoteLastModified: string | null };

const EMPTY_OBJECT_STORAGE_CONFIGURATION: ObjectStorageConfiguration = {
    configured: false,
    endpoint: null,
    region: "auto",
    bucketName: null,
    accessKeyId: null,
};

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

function formatLastModified(value: string | null) {
    if (!value) return "远端尚无同步数据";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function importConfirmation(preview: Preview) {
    const learnerAction = preview.learnerWillBeReplaced ? "现有 Learner 资料将被覆盖" : "将导入 Learner 资料";
    const learnerName = preview.importedLearnerDisplayName ?? "未命名 Learner";
    const currentJourney = preview.importedCurrentJourneyGoal
        ? `当前 Journey 将切换为「${preview.importedCurrentJourneyGoal}」。`
        : "数据包未指定当前 Journey。";
    const conflicts = preview.conflicts.length > 0
        ? `同 portable_id 的 Journey 将整体替换（含学习进度和 Workspace）：\n`
        + preview.conflicts.map((item) => `• ${item.goalDescription}`).join("\n")
        : "没有同 portable_id 的 Journey 需要替换。";

    return [
        `将导入 ${preview.journeyCount} 个 Journey。`,
        `${learnerAction}为「${learnerName}」。`,
        currentJourney,
        conflicts,
        "本机独有的 Journey 会保留。",
    ].join("\n\n");
}

async function previewJourneys(archive: ArrayBuffer) {
    const response = await fetch(`${TRANSFER_URL}/journeys/preview`, {
        method: "POST",
        headers: {"Content-Type": "application/octet-stream"},
        body: archive,
    });
    if (!response.ok) throw new Error(await errorMessage(response, "学习数据文件无效"));
    return await response.json() as Preview;
}

async function importJourneysArchive(archive: ArrayBuffer, replaceConflicts: boolean) {
    const response = await fetch(`${TRANSFER_URL}/journeys?replaceConflicts=${replaceConflicts}`, {
        method: "POST",
        headers: {"Content-Type": "application/octet-stream"},
        body: archive,
    });
    if (!response.ok) throw new Error(await errorMessage(response, "导入学习数据失败"));
    return await response.json() as ImportResult;
}

export function DataTransferDialog({
                                       open,
                                       onClose,
                                       onModelConfigImported,
                                       onJourneysImported,
                                       onBeforeSync,
                                   }: {
    open: boolean;
    onClose: () => void;
    onModelConfigImported: (config: ModelConfig) => void;
    onJourneysImported: () => Promise<void>;
    onBeforeSync: (operation: "upload" | "download") => Promise<boolean>;
}) {
    const dialogRef = useRef<HTMLDialogElement>(null);
    const confirmationHeadingRef = useRef<HTMLHeadingElement>(null);
    const [busy, setBusy] = useState(false);
    const [status, setStatus] = useState("");
    const [error, setError] = useState("");
    const [r2Loading, setR2Loading] = useState(false);
    const [r2Configuration, setR2Configuration] = useState(EMPTY_OBJECT_STORAGE_CONFIGURATION);
    const [remoteLastModified, setRemoteLastModified] = useState<string | null>(null);
    const [secretAccessKey, setSecretAccessKey] = useState("");
    const [pendingConfirmation, setPendingConfirmation] = useState<PendingTransferConfirmation | null>(null);

    useEffect(() => {
        const dialog = dialogRef.current;
        if (!dialog) return;
        if (open && !dialog.open) dialog.showModal();
        if (!open && dialog.open) dialog.close();
    }, [open]);

    useEffect(() => {
        if (pendingConfirmation) confirmationHeadingRef.current?.focus();
    }, [pendingConfirmation]);

    useEffect(() => {
        if (open) {
            setBusy(false);
            setStatus("");
            setError("");
            setPendingConfirmation(null);
            setSecretAccessKey("");
            setR2Configuration(EMPTY_OBJECT_STORAGE_CONFIGURATION);
            setRemoteLastModified(null);
            setR2Loading(true);
            const controller = new AbortController();
            const loadR2State = async () => {
                let loadedConfiguration: ObjectStorageConfiguration | null = null;
                try {
                    const configurationResponse = await fetch(`${TRANSFER_URL}/r2/config`, {signal: controller.signal});
                    if (!configurationResponse.ok) {
                        throw new Error(await errorMessage(configurationResponse, "无法读取对象存储配置"));
                    }
                    loadedConfiguration = await configurationResponse.json() as ObjectStorageConfiguration;
                    if (controller.signal.aborted) return;
                    setR2Configuration(loadedConfiguration);

                    const statusResponse = await fetch(`${TRANSFER_URL}/r2/status`, {signal: controller.signal});
                    if (!statusResponse.ok) {
                        throw new Error(await errorMessage(statusResponse, "无法读取远端同步状态"));
                    }
                    const remoteStatus = await statusResponse.json() as ObjectStorageStatus;
                    if (controller.signal.aborted) return;
                    setR2Configuration({
                        ...loadedConfiguration,
                        configured: loadedConfiguration.configured && remoteStatus.configured,
                    });
                    setRemoteLastModified(remoteStatus.remoteLastModified);
                } catch (cause) {
                    if (!controller.signal.aborted) {
                        if (loadedConfiguration === null) setR2Configuration(EMPTY_OBJECT_STORAGE_CONFIGURATION);
                        setRemoteLastModified(null);
                        setError(cause instanceof Error ? cause.message : "无法读取对象存储配置");
                        setR2Loading(false);
                    }
                } finally {
                    if (!controller.signal.aborted) setR2Loading(false);
                }
            };
            void loadR2State();
            return () => controller.abort();
        }
        setSecretAccessKey("");
    }, [open]);

    const close = () => {
        if (busy) return;
        setPendingConfirmation(null);
        setSecretAccessKey("");
        onClose();
    };

    const saveR2Configuration = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setBusy(true);
        setError("");
        setStatus("正在保存对象存储配置到本机…");
        let configurationSaved = false;
        try {
            const response = await fetch(`${TRANSFER_URL}/r2/config`, {
                method: "PUT",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({
                    endpoint: r2Configuration.endpoint?.trim(),
                    region: r2Configuration.region?.trim() || "auto",
                    bucketName: r2Configuration.bucketName?.trim(),
                    accessKeyId: r2Configuration.accessKeyId?.trim(),
                    secretAccessKey,
                }),
            });
            if (!response.ok) throw new Error(await errorMessage(response, "保存对象存储配置失败"));
            setR2Configuration((current) => ({...current, configured: true}));
            setSecretAccessKey("");
            configurationSaved = true;
            const statusResponse = await fetch(`${TRANSFER_URL}/r2/status`);
            if (!statusResponse.ok) {
                throw new Error(await errorMessage(statusResponse, "无法读取远端同步时间"));
            }
            const remoteStatus = await statusResponse.json() as ObjectStorageStatus;
            setRemoteLastModified(remoteStatus.remoteLastModified);
            setStatus("对象存储配置已保存在本机。");
        } catch (cause) {
            setStatus("");
            const message = cause instanceof Error ? cause.message : "保存对象存储配置失败";
            setError(configurationSaved ? `配置已保存，但${message}` : message);
        } finally {
            setBusy(false);
        }
    };

    const uploadToR2 = () => {
        setError("");
        setStatus("请确认上传；远端现有同步数据将被替换。");
        setPendingConfirmation({operation: "upload"});
    };

    const confirmPendingTransfer = async () => {
        const pending = pendingConfirmation;
        if (!pending) return;
        setBusy(true);
        setError("");
        try {
            if (pending.operation === "upload") {
                setStatus("正在保存编辑并等待写入完成…");
                if (!await onBeforeSync("upload")) {
                    setStatus("已取消上传；请先保存编辑并等待写入完成。");
                    return;
                }
                setStatus("正在同步本机数据到对象存储…");
                const response = await fetch(`${TRANSFER_URL}/r2/upload`, {method: "POST"});
                if (!response.ok) throw new Error(await errorMessage(response, "同步到对象存储失败"));
                const remoteStatus = await response.json() as ObjectStorageStatus;
                setRemoteLastModified(remoteStatus.remoteLastModified);
                setPendingConfirmation(null);
                setStatus(`同步到对象存储完成。远端最后修改：${formatLastModified(remoteStatus.remoteLastModified)}`);
            } else {
                if (pending.operation === "download") {
                    setStatus("正在保存编辑并等待写入完成…");
                    if (!await onBeforeSync("download")) {
                        setStatus("已取消同步；请先保存编辑并等待写入完成。");
                        return;
                    }
                } else {
                    setStatus("正在导入学习数据…");
                }
                setStatus(pending.operation === "download"
                    ? "正在同步 Learner、Journey、学习进度和 Workspace…"
                    : "正在导入学习数据…");
                const result = await importJourneysArchive(
                    pending.archive,
                    pending.operation === "download" || pending.preview.conflicts.length > 0,
                );
                setPendingConfirmation(null);
                try {
                    await onJourneysImported();
                } catch (cause) {
                    throw new Error(`数据已导入，但页面刷新失败：${cause instanceof Error ? cause.message : "请重新载入学习环境"}`);
                }
                setStatus(pending.operation === "download"
                    ? `从对象存储同步完成：新增 ${result.added.length} 个，覆盖 ${result.replaced.length} 个 Journey`
                    : `导入完成：新增 ${result.added.length} 个，覆盖 ${result.replaced.length} 个 Journey`);
            }
        } catch (cause) {
            setStatus("");
            const fallback = pending.operation === "upload"
                ? "同步到对象存储失败"
                : pending.operation === "download" ? "从对象存储同步失败" : "导入学习数据失败";
            setError(cause instanceof Error ? cause.message : fallback);
        } finally {
            setBusy(false);
        }
    };

    const cancelPendingTransfer = () => {
        if (!pendingConfirmation || busy) return;
        const message = pendingConfirmation.operation === "upload"
            ? "已取消上传到对象存储"
            : pendingConfirmation.operation === "download" ? "已取消从对象存储同步" : "已取消导入";
        setPendingConfirmation(null);
        setError("");
        setStatus(message);
    };

    const downloadFromR2 = async () => {
        setBusy(true);
        setStatus("正在下载并预览远端数据…");
        setError("");
        try {
            const downloadResponse = await fetch(`${TRANSFER_URL}/r2/download`);
            if (!downloadResponse.ok) {
                throw new Error(await errorMessage(downloadResponse, "下载远端同步数据失败"));
            }
            const archive = await downloadResponse.arrayBuffer();
            const preview = await previewJourneys(archive);
            setPendingConfirmation({operation: "download", archive, preview});
            setStatus("请检查远端数据预览，并确认同步到本机。");
        } catch (cause) {
            setStatus("");
            setError(cause instanceof Error ? cause.message : "从对象存储同步失败");
        } finally {
            setBusy(false);
        }
    };

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
            const preview = await previewJourneys(archive);
            setPendingConfirmation({operation: "import", archive, preview});
            setStatus("请检查学习数据预览，并确认导入。");
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
                close();
            }}
            onClick={(event) => {
                if (event.target === event.currentTarget) close();
            }}
        >
            <section className="model-settings-content">
                <header className="model-settings-header">
                    <div>
                        <h2 id="transfer-title">数据同步与迁移</h2>
                        <p>可同步到 S3 兼容对象存储，也可用本地 ZIP 在设备间迁移学习数据；模型配置单独迁移。</p>
                    </div>
                    <button
                        className="secondary model-settings-close"
                        type="button"
                        aria-label="关闭数据迁移"
                        onClick={close}
                        disabled={busy}
                    >×
                    </button>
                </header>
                {pendingConfirmation && (
                    <section className="model-settings-confirmation" aria-labelledby="transfer-confirmation-title">
                        <h3 id="transfer-confirmation-title" ref={confirmationHeadingRef} tabIndex={-1}>
                            {pendingConfirmation.operation === "upload"
                                ? "确认上传到对象存储"
                                : pendingConfirmation.operation === "download" ? "确认同步到本机" : "确认导入学习数据"}
                        </h3>
                        <p className="model-settings-hint model-settings-confirmation-message">
                            {pendingConfirmation.operation === "upload"
                                ? "上传会直接替换对象存储上的同步数据，远端只保留最新一份。"
                                : importConfirmation(pendingConfirmation.preview)}
                        </p>
                        {status && <p className="model-settings-feedback" role="status">{status}</p>}
                        {error && <p className="model-settings-feedback error" role="alert">{error}</p>}
                        <div className="model-settings-actions">
                            <button type="button" className="secondary" onClick={cancelPendingTransfer} disabled={busy}>
                                取消
                            </button>
                            <button type="button" onClick={() => void confirmPendingTransfer()} disabled={busy}>
                                {busy ? "处理中…" : pendingConfirmation.operation === "upload"
                                    ? "确认上传" : pendingConfirmation.operation === "download" ? "确认同步到本机" : "确认导入"}
                            </button>
                        </div>
                    </section>
                )}
                <div hidden={pendingConfirmation !== null}>
                <h3>S3 兼容对象存储</h3>
                <p className="model-settings-hint">
                    远端仅保留一份全量学习数据；手动上传会覆盖远端内容。配置和密钥只保存在本机，不进入同步包。
                </p>
                <p className="model-settings-hint">
                    同步前会保存 App 内未保存的编辑；在外部 IDE 修改的文件，请先在 IDE 保存。
                </p>
                <form className="model-settings-form" onSubmit={(event) => void saveR2Configuration(event)}>
                    <label htmlFor="s3-endpoint">
                        Endpoint
                        <input
                            id="s3-endpoint"
                            type="url"
                            value={r2Configuration.endpoint ?? ""}
                            onChange={(event) => setR2Configuration((current) => ({
                                ...current,
                                endpoint: event.target.value
                            }))}
                            autoComplete="off"
                            placeholder="https://s3.example.com"
                            required
                            disabled={busy || r2Loading}
                        />
                    </label>
                    <label htmlFor="s3-region">
                        签名 Region
                        <input
                            id="s3-region"
                            value={r2Configuration.region ?? "auto"}
                            onChange={(event) => setR2Configuration((current) => ({
                                ...current,
                                region: event.target.value
                            }))}
                            autoComplete="off"
                            placeholder="auto"
                            disabled={busy || r2Loading}
                            aria-describedby="s3-region-hint"
                        />
                        <span className="model-settings-hint" id="s3-region-hint">
                            Cloudflare R2 使用 auto；其他服务按其 S3 API 要求填写。
                        </span>
                    </label>
                    <label htmlFor="s3-bucket-name">
                        Bucket 名称
                        <input
                            id="s3-bucket-name"
                            value={r2Configuration.bucketName ?? ""}
                            onChange={(event) => setR2Configuration((current) => ({
                                ...current,
                                bucketName: event.target.value
                            }))}
                            autoComplete="off"
                            required
                            disabled={busy || r2Loading}
                        />
                    </label>
                    <label htmlFor="s3-access-key-id">
                        Access Key ID
                        <input
                            id="s3-access-key-id"
                            value={r2Configuration.accessKeyId ?? ""}
                            onChange={(event) => setR2Configuration((current) => ({
                                ...current,
                                accessKeyId: event.target.value
                            }))}
                            autoComplete="off"
                            required
                            disabled={busy || r2Loading}
                        />
                    </label>
                    <label htmlFor="s3-secret-access-key">
                        Secret Access Key
                        <input
                            id="s3-secret-access-key"
                            type="password"
                            value={secretAccessKey}
                            onChange={(event) => setSecretAccessKey(event.target.value)}
                            autoComplete="new-password"
                            required
                            disabled={busy || r2Loading}
                            aria-describedby="s3-secret-hint"
                        />
                        <span className="model-settings-hint" id="s3-secret-hint">
                            不会回填；每次保存都需要重新输入。此密钥需要目标 Bucket 的对象读写权限。
                        </span>
                    </label>
                    <div className="model-settings-actions">
                        <button type="submit" disabled={busy || r2Loading}>
                            {busy && status.startsWith("正在保存对象存储") ? "保存中…" : "保存对象存储配置"}
                        </button>
                    </div>
                </form>
                <p className="model-settings-hint" role="status">
                    {r2Loading
                        ? "正在读取远端状态…"
                        : r2Configuration.configured
                            ? `远端最后修改：${formatLastModified(remoteLastModified)}`
                            : "尚未配置对象存储"}
                </p>
                <div className="model-settings-actions">
                    <button type="button" disabled={busy || r2Loading || !r2Configuration.configured}
                            onClick={() => void uploadToR2()}>
                        同步到对象存储
                    </button>
                    <button type="button" className="secondary"
                            disabled={busy || r2Loading || !r2Configuration.configured}
                            onClick={() => void downloadFromR2()}>
                        从对象存储同步到本机
                    </button>
                </div>
                <h3>本地文件迁移</h3>
                <p className="model-settings-hint">ZIP 迁移 Journey 与学习进度；模型配置单独迁移。</p>
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
                </div>
            </section>
        </dialog>
    );
}
