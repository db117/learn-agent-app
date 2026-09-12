import {type FormEvent, useEffect, useState} from "react";
import {
    api,
    type ModelConfiguration,
    type ModelConfigurationInput,
} from "../lib/api";

type ModelSettingsViewProps = {
    onBack: () => void;
    onSaved: (configuration: ModelConfiguration) => Promise<string>;
};

type ModelForm = {
    provider: string;
    baseUrl: string;
    model: string;
    apiKey: string;
};

function inputOf(form: ModelForm): ModelConfigurationInput {
    return {
        provider: form.provider,
        baseUrl: form.baseUrl.trim(),
        model: form.model.trim(),
        apiKey: form.apiKey.trim() || null,
    };
}

function sourceLabel(source: ModelConfiguration["source"]) {
    if (source === "environment") return "环境变量优先";
    if (source === "app") return "App 配置";
    return "默认配置";
}

export function ModelSettingsView({onBack, onSaved}: ModelSettingsViewProps) {
    const [configuration, setConfiguration] = useState<ModelConfiguration | null>(null);
    const [form, setForm] = useState<ModelForm>({
        provider: "openai-compatible",
        baseUrl: "https://api.openai.com",
        model: "gpt-4.1-mini",
        apiKey: "",
    });
    const [working, setWorking] = useState<"loading" | "saving" | "testing" | null>("loading");
    const [message, setMessage] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let disposed = false;
        void api.modelConfiguration()
            .then((next) => {
                if (disposed) return;
                setConfiguration(next);
                setForm((current) => ({
                    ...current,
                    provider: next.provider,
                    baseUrl: next.baseUrl,
                    model: next.model,
                }));
            })
            .catch((cause: unknown) => {
                if (!disposed) setError(cause instanceof Error ? cause.message : "无法读取模型配置");
            })
            .finally(() => {
                if (!disposed) setWorking(null);
            });
        return () => {
            disposed = true;
        };
    }, []);

    async function save(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        setWorking("saving");
        setError(null);
        setMessage(null);
        try {
            const saved = await api.saveModelConfiguration(inputOf(form));
            setConfiguration(saved);
            setForm((current) => ({...current, apiKey: ""}));
            setMessage(await onSaved(saved));
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : "模型配置保存失败");
        } finally {
            setWorking(null);
        }
    }

    async function test() {
        setWorking("testing");
        setError(null);
        setMessage(null);
        try {
            const result = await api.testModelConfiguration(inputOf(form));
            setMessage(result.message);
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : "模型连接失败");
        } finally {
            setWorking(null);
        }
    }

    function update(field: keyof ModelForm, value: string) {
        setForm((current) => ({...current, [field]: value}));
    }

    return (
        <section className="model-settings panel">
            <div className="settings-header">
                <div>
                    <div className="section-kicker">MODEL PROVIDER</div>
                    <h2>模型设置</h2>
                    <p className="lead">配置一个 OpenAI-compatible 模型接口，供 TutorAgent 和学习内容生成共同使用。</p>
                </div>
                <button className="secondary" type="button" onClick={onBack}>返回</button>
            </div>
            {configuration && <p className="settings-source" role="status">当前来源：{sourceLabel(configuration.source)}</p>}
            <form className="settings-form" onSubmit={(event) => void save(event)}>
                <label>提供商协议
                    <select value={form.provider} onChange={(event) => update("provider", event.target.value)}>
                        <option value="openai-compatible">OpenAI-compatible</option>
                    </select>
                </label>
                <label>Base URL
                    <input value={form.baseUrl} onChange={(event) => update("baseUrl", event.target.value)}
                           placeholder="https://api.openai.com" required/>
                    <span className="field-help">可填写兼容 OpenAI API 的其他云服务地址。</span>
                </label>
                <label>模型名称
                    <input value={form.model} onChange={(event) => update("model", event.target.value)}
                           placeholder="gpt-4.1-mini" required/>
                </label>
                <label>API Key
                    <input type="password" value={form.apiKey} onChange={(event) => update("apiKey", event.target.value)}
                           placeholder={configuration?.apiKeyConfigured ? "已保存，留空保持不变" : "输入 API Key"}
                           autoComplete="off"/>
                    {configuration?.apiKeyConfigured &&
                        <span className="field-help">当前已保存：{configuration.apiKeyMasked}；输入新值可替换。</span>}
                </label>
                <p className="settings-warning">API Key 会保存到本地 SQLite 的 setting 表，并可能随数据库导出文件保存，请妥善保管导出文件。</p>
                {error && <p className="settings-message warning" role="alert">{error}</p>}
                {message && <p className="settings-message success" role="status">{message}</p>}
                <div className="button-row">
                    <button className="primary" type="submit" disabled={working !== null}>
                        {working === "saving" ? "保存中…" : "保存并应用"}
                    </button>
                    <button className="secondary" type="button" onClick={() => void test()} disabled={working !== null}>
                        {working === "testing" ? "测试中…" : "测试连接"}
                    </button>
                    {working === "loading" && <span className="muted">正在读取配置…</span>}
                </div>
            </form>
        </section>
    );
}
