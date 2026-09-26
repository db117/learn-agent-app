import {useState} from "react";
import {invoke} from "@tauri-apps/api/core";

type Props = { journeyId: number };
type Ide = "vscode" | "jetbrains";

const VS_CODE_KEY = "learn-agent-vscode-launcher";
const JETBRAINS_KEY = "learn-agent-jetbrains-launcher";
const BACKEND_URL = "http://127.0.0.1:10707";

function savedLauncher(key: string, fallback: string): string {
    try {
        return localStorage.getItem(key) ?? fallback;
    } catch {
        return fallback;
    }
}

async function learningWorkspacePath(journeyId: number): Promise<string> {
    const response = await fetch(`${BACKEND_URL}/api/journeys/${journeyId}/workspace/path`);
    if (!response.ok) throw new Error("无法读取学习目录路径");
    return response.text();
}

export function LearningIdeActions({journeyId}: Props) {
    const [vscodeLauncher, setVscodeLauncher] = useState(() => savedLauncher(VS_CODE_KEY, "code"));
    const [jetbrainsLauncher, setJetbrainsLauncher] = useState(() => savedLauncher(JETBRAINS_KEY, "idea"));
    const [busy, setBusy] = useState(false);
    const [feedback, setFeedback] = useState("");

    const openFolder = async () => {
        setBusy(true);
        setFeedback("");
        try {
            const workspacePath = await learningWorkspacePath(journeyId);
            await invoke("open_learning_workspace", {workspacePath});
        } catch (error) {
            setFeedback(error instanceof Error ? error.message : "无法打开学习目录");
        } finally {
            setBusy(false);
        }
    };

    const copyPath = async () => {
        setFeedback("");
        try {
            const path = await learningWorkspacePath(journeyId);
            await navigator.clipboard.writeText(path);
            setFeedback("学习目录路径已复制。");
        } catch (error) {
            setFeedback(error instanceof Error ? error.message : "无法复制学习目录路径");
        }
    };

    const launch = async (ide: Ide) => {
        const executablePath = ide === "vscode" ? vscodeLauncher : jetbrainsLauncher;
        setBusy(true);
        setFeedback("");
        try {
            const workspacePath = await learningWorkspacePath(journeyId);
            await invoke("launch_learning_ide", {workspacePath, ide, executablePath});
        } catch (error) {
            setFeedback(error instanceof Error ? error.message : "无法启动 IDE");
        } finally {
            setBusy(false);
        }
    };

    const saveLauncher = (key: string, value: string, setValue: (next: string) => void) => {
        setValue(value);
        try {
            localStorage.setItem(key, value);
        } catch {
            setFeedback("无法保存 IDE 启动路径");
        }
    };

    return (
        <div className="workspace-ide-actions">
            <div className="workspace-ide-buttons">
                <button type="button" className="secondary" onClick={() => void openFolder()} disabled={busy}>
                    打开学习目录
                </button>
                <button type="button" className="secondary" onClick={() => void copyPath()} disabled={busy}>
                    复制目录路径
                </button>
                <button type="button" className="secondary" onClick={() => void launch("vscode")} disabled={busy}>
                    VS Code
                </button>
                <button type="button" className="secondary" onClick={() => void launch("jetbrains")} disabled={busy}>
                    JetBrains IDE
                </button>
                <details className="workspace-ide-settings">
                    <summary>配置 IDE</summary>
                    <label>
                        VS Code 启动程序
                        <input
                            value={vscodeLauncher}
                            onChange={(event) => saveLauncher(VS_CODE_KEY, event.target.value, setVscodeLauncher)}
                            placeholder="code 或 Code.exe 的完整路径"
                            autoComplete="off"
                        />
                    </label>
                    <label>
                        JetBrains IDE 启动程序
                        <input
                            value={jetbrainsLauncher}
                            onChange={(event) => saveLauncher(JETBRAINS_KEY, event.target.value, setJetbrainsLauncher)}
                            placeholder="idea、webstorm 或启动程序完整路径"
                            autoComplete="off"
                        />
                    </label>
                </details>
            </div>
            {feedback && <p role="status" className="workspace-ide-feedback">{feedback}</p>}
        </div>
    );
}
