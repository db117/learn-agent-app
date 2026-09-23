import {type FormEvent, useEffect, useRef, useState} from "react";
import "./ModelSettingsDialog.css";

const MODEL_CONFIG_URL = "http://127.0.0.1:18080/api/model-config";

export type ModelConfig = {
    configured: boolean;
    modelName: string | null;
    baseUrl: string;
    apiKeyConfigured: boolean;
};

type ModelConfigRequest = {
    modelName: string;
    baseUrl: string;
    apiKey: string;
    clearApiKey: boolean;
};

type Feedback = { kind: "success" | "error"; message: string };

type ModelSettingsDialogProps = {
    open: boolean;
    config: ModelConfig | null;
    onClose: () => void;
    onSaved: (config: ModelConfig) => void;
};

async function responseError(response: Response, fallback: string) {
    try {
        const payload = await response.json() as { message?: unknown };
        return typeof payload.message === "string" && payload.message !== "" ? payload.message : fallback;
    } catch {
        return fallback;
    }
}

export function ModelSettingsDialog({open, config, onClose, onSaved}: ModelSettingsDialogProps) {
    const dialogRef = useRef<HTMLDialogElement>(null);
    const formRef = useRef<HTMLFormElement>(null);
    const [modelName, setModelName] = useState("");
    const [baseUrl, setBaseUrl] = useState("");
    const [apiKey, setApiKey] = useState("");
    const [clearApiKey, setClearApiKey] = useState(false);
    const [busy, setBusy] = useState<"saving" | "testing" | null>(null);
    const [feedback, setFeedback] = useState<Feedback | null>(null);

    useEffect(() => {
        const dialog = dialogRef.current;
        if (!dialog) return;
        if (open && !dialog.open) dialog.showModal();
        if (!open && dialog.open) dialog.close();
    }, [open, config]);

    useEffect(() => {
        if (!open) return;
        setModelName(config?.modelName ?? "");
        setBaseUrl(config?.baseUrl ?? "");
        setApiKey("");
        setClearApiKey(false);
        setBusy(null);
        setFeedback(null);
    }, [open]);

    const requestBody = (): ModelConfigRequest => ({
        modelName: modelName.trim(),
        baseUrl: baseUrl.trim(),
        apiKey,
        clearApiKey: apiKey.trim() === "" && clearApiKey,
    });

    const testConnection = async () => {
        if (!formRef.current?.reportValidity()) return;
        setBusy("testing");
        setFeedback(null);
        try {
            const response = await fetch(`${MODEL_CONFIG_URL}/test`, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify(requestBody()),
            });
            if (!response.ok) {
                throw new Error(await responseError(response, `测试连接失败（${response.status}）`));
            }
            const payload = await response.json() as { success?: boolean; message?: string };
            if (!payload.success) throw new Error(payload.message ?? "测试连接失败");
            setFeedback({kind: "success", message: payload.message ?? "连接成功"});
        } catch (requestError) {
            setFeedback({
                kind: "error",
                message: requestError instanceof Error ? requestError.message : "测试连接失败"
            });
        } finally {
            setBusy(null);
        }
    };

    const saveConfig = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (busy !== null) return;
        setBusy("saving");
        setFeedback(null);
        try {
            const response = await fetch(MODEL_CONFIG_URL, {
                method: "PUT",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify(requestBody()),
            });
            if (!response.ok) {
                throw new Error(await responseError(response, `保存失败（${response.status}）`));
            }
            const savedConfig = await response.json() as ModelConfig;
            onSaved(savedConfig);
            onClose();
        } catch (requestError) {
            setFeedback({kind: "error", message: requestError instanceof Error ? requestError.message : "保存失败"});
        } finally {
            setBusy(null);
        }
    };

    return (
        <dialog
            className="model-settings-dialog"
            ref={dialogRef}
            aria-labelledby="model-settings-title"
            aria-describedby="model-settings-description"
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
                        <h2 id="model-settings-title">大模型配置</h2>
                        <p id="model-settings-description">设置用于 Tutor 的 OpenAI 兼容模型。</p>
                    </div>
                    <button
                        className="secondary model-settings-close"
                        type="button"
                        aria-label="关闭大模型配置"
                        onClick={onClose}
                    >
                        ×
                    </button>
                </header>

                <form ref={formRef} className="model-settings-form" onSubmit={(event) => void saveConfig(event)}>
                    <label htmlFor="model-settings-name">
                        模型名称 <span aria-hidden="true">（必填）</span>
                        <input
                            id="model-settings-name"
                            name="modelName"
                            value={modelName}
                            onChange={(event) => setModelName(event.target.value)}
                            placeholder="例如：gpt-4o-mini"
                            autoComplete="off"
                            required
                            disabled={busy !== null}
                        />
                    </label>

                    <label htmlFor="model-settings-base-url">
                        API 地址 <span aria-hidden="true">（可选）</span>
                        <input
                            id="model-settings-base-url"
                            name="baseUrl"
                            type="url"
                            value={baseUrl}
                            onChange={(event) => setBaseUrl(event.target.value)}
                            placeholder="https://api.openai.com/v1"
                            autoComplete="url"
                            disabled={busy !== null}
                            aria-describedby="model-settings-base-url-hint"
                        />
                        <span className="model-settings-hint" id="model-settings-base-url-hint">
                            留空时使用 OpenAI 默认地址。
                        </span>
                    </label>

                    <label htmlFor="model-settings-api-key">
                        API Key <span aria-hidden="true">（可选）</span>
                        <input
                            id="model-settings-api-key"
                            name="apiKey"
                            type="password"
                            value={apiKey}
                            onChange={(event) => {
                                setApiKey(event.target.value);
                                if (event.target.value !== "") setClearApiKey(false);
                            }}
                            placeholder={config?.apiKeyConfigured ? "留空以保留已保存的 Key" : "无需认证时可留空"}
                            autoComplete="new-password"
                            disabled={busy !== null}
                            aria-describedby="model-settings-key-hint"
                        />
                        <span className="model-settings-hint" id="model-settings-key-hint">
                            {config?.apiKeyConfigured
                                ? "已保存的 Key 不会回显；更换 API 地址且不输入新 Key 时，旧 Key 会清除。"
                                : "无需认证的兼容服务可留空。"}
                        </span>
                    </label>

                    {config?.apiKeyConfigured && (
                        <label className="model-settings-clear-key" htmlFor="model-settings-clear-key">
                            <input
                                id="model-settings-clear-key"
                                type="checkbox"
                                checked={clearApiKey}
                                onChange={(event) => setClearApiKey(event.target.checked)}
                                disabled={busy !== null || apiKey.trim() !== ""}
                            />
                            清除已保存的 API Key
                        </label>
                    )}

                    {feedback && (
                        <p className={`model-settings-feedback ${feedback.kind}`}
                           role={feedback.kind === "error" ? "alert" : "status"}>
                            {feedback.message}
                        </p>
                    )}

                    <div className="model-settings-actions">
                        <button type="button" className="secondary" onClick={() => void testConnection()}
                                disabled={busy !== null}>
                            {busy === "testing" ? "测试中…" : "测试连接"}
                        </button>
                        <button type="submit" disabled={busy !== null}>
                            {busy === "saving" ? "保存中…" : "保存"}
                        </button>
                    </div>
                </form>
            </section>
        </dialog>
    );
}
