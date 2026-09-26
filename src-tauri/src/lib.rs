use serde::Serialize;
use std::net::{SocketAddr, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};
use tauri::{Emitter, Manager, State};

const BACKEND_HOST: &str = "127.0.0.1";
const BACKEND_PORT: u16 = 10707;
const BACKEND_JAR: &str = "agent-backend.jar";
const BACKEND_STARTUP_TIMEOUT: Duration = Duration::from_secs(60);
const BACKEND_POLL_INTERVAL: Duration = Duration::from_millis(200);

// 仅保存本进程启动的 JVM；外部已运行的后端不纳入退出时的清理范围。
struct BackendState(Mutex<Option<Child>>);

// status 字段区分本桌面进程管理的后端与已占用固定地址的外部后端。
#[derive(Debug, Serialize)]
struct BackendStatus {
    status: String,
    detail: Option<String>,
}

fn backend_addr() -> SocketAddr {
    SocketAddr::from(([127, 0, 0, 1], BACKEND_PORT))
}

fn backend_reachable() -> bool {
    TcpStream::connect_timeout(&backend_addr(), Duration::from_millis(150)).is_ok()
}

fn wait_for_backend(child: &mut Child) -> Result<(), String> {
    // 进程创建成功不等于服务已可用，因此轮询固定回环地址直到监听或超时。
    let deadline = Instant::now() + BACKEND_STARTUP_TIMEOUT;
    loop {
        if backend_reachable() {
            return Ok(());
        }
        match child.try_wait() {
            Ok(Some(status)) => {
                return Err(format!("JVM backend exited before {BACKEND_HOST}:{BACKEND_PORT} became available ({status}). Check that Java 21 is installed and the backend can start."));
            }
            Ok(None) => {}
            Err(error) => {
                return Err(format!(
                    "Could not inspect the JVM backend process: {error}"
                ))
            }
        }
        if Instant::now() >= deadline {
            return Err(format!("JVM backend did not become available at {BACKEND_HOST}:{BACKEND_PORT} within {} seconds. Check that Java 21 is installed and the backend can start.", BACKEND_STARTUP_TIMEOUT.as_secs()));
        }
        thread::sleep(BACKEND_POLL_INTERVAL);
    }
}

fn terminate_child(mut child: Child) -> Result<(), String> {
    match child.try_wait() {
        Ok(Some(_)) => Ok(()),
        Ok(None) => {
            child
                .kill()
                .map_err(|error| format!("Could not stop JVM backend: {error}"))?;
            child
                .wait()
                .map(|_| ())
                .map_err(|error| format!("Could not wait for JVM backend to stop: {error}"))
        }
        Err(error) => Err(format!(
            "Could not inspect JVM backend before stopping: {error}"
        )),
    }
}

fn managed_backend_running(state: &BackendState) -> bool {
    let Ok(mut child) = state.0.lock() else {
        return false;
    };
    let Some(process) = child.as_mut() else {
        return false;
    };
    match process.try_wait() {
        Ok(None) => true,
        Ok(Some(_)) | Err(_) => {
            child.take();
            false
        }
    }
}

fn status(state: &BackendState) -> BackendStatus {
    let managed = managed_backend_running(state);
    BackendStatus {
        status: if backend_reachable() {
            if managed {
                "running"
            } else {
                "external"
            }
        } else {
            "stopped"
        }
        .to_string(),
        detail: Some(format!("{BACKEND_HOST}:{BACKEND_PORT}")),
    }
}

fn backend_jar_path(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    // 打包运行优先读取 Tauri 资源，开发运行回退到 backend/target 下的产物。
    let mut candidates = Vec::new();
    if let Ok(resource_dir) = app.path().resource_dir() {
        candidates.push(resource_dir.join(BACKEND_JAR));
    }
    candidates.push(
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("..")
            .join("backend")
            .join("target")
            .join(BACKEND_JAR),
    );
    candidates
        .iter()
        .find(|path| path.is_file())
        .cloned()
        .ok_or_else(|| {
            "JVM backend JAR not found. Build it with `pnpm backend:package` before starting the desktop app. Expected agent-backend.jar in the Tauri resources or backend/target.".to_string()
        })
}

fn normalize_jar_path(path: PathBuf) -> PathBuf {
    #[cfg(windows)]
    {
        // Tauri 可能返回 java.exe 的 -jar 不接受的 Windows verbatim 路径。
        let value = path.to_string_lossy();
        return match value.strip_prefix(r"\\?\") {
            Some(stripped) => PathBuf::from(stripped),
            None => PathBuf::from(value.as_ref()),
        };
    }
    #[cfg(not(windows))]
    path
}

#[tauri::command]
fn backend_status(state: State<'_, BackendState>) -> BackendStatus {
    status(&state)
}

#[tauri::command]
fn start_backend(
    app: tauri::AppHandle,
    state: State<'_, BackendState>,
) -> Result<BackendStatus, String> {
    // 固定端口已有服务时不重复启动，避免接管其他进程管理的后端。
    if backend_reachable() {
        return Ok(status(&state));
    }
    let jar = normalize_jar_path(backend_jar_path(&app)?);
    let mut command = Command::new("java");
    command
        .env("QUARKUS_HTTP_HOST", BACKEND_HOST)
        .env("QUARKUS_HTTP_PORT", BACKEND_PORT.to_string())
        .arg("-jar")
        .arg(jar);
    let mut child = command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|error| {
            format!("Unable to start the JVM backend with Java 21: {error}. Install Java 21 and make `java` available on PATH.")
        })?;
    if let Err(error) = wait_for_backend(&mut child) {
        let _ = terminate_child(child);
        return Err(error);
    }
    *state
        .0
        .lock()
        .map_err(|_| "backend state lock poisoned".to_string())? = Some(child);
    Ok(status(&state))
}

#[tauri::command]
fn stop_backend(state: State<'_, BackendState>) -> Result<BackendStatus, String> {
    // 只终止本进程登记的 Child，不触碰 status 为 external 的后端。
    if let Some(child) = state
        .0
        .lock()
        .map_err(|_| "backend state lock poisoned".to_string())?
        .take()
    {
        terminate_child(child)?;
    }
    Ok(status(&state))
}

fn learning_workspace_path(workspace_path: &str) -> Result<PathBuf, String> {
    let workspace = PathBuf::from(workspace_path.trim());
    if !workspace.is_absolute() || !workspace.is_dir() {
        return Err("The learning workspace does not exist yet".to_string());
    }
    Ok(workspace)
}

#[tauri::command]
fn open_learning_workspace(workspace_path: String) -> Result<(), String> {
    let workspace = learning_workspace_path(&workspace_path)?;
    #[cfg(target_os = "windows")]
    let mut command = Command::new("explorer.exe");
    #[cfg(target_os = "macos")]
    let mut command = Command::new("open");
    #[cfg(all(unix, not(target_os = "macos")))]
    let mut command = Command::new("xdg-open");
    command
        .arg(workspace)
        .spawn()
        .map_err(|error| format!("Unable to open the learning workspace: {error}"))?;
    Ok(())
}

fn allowed_ide_executable(ide: &str, executable: &Path) -> bool {
    let Some(name) = executable.file_name().and_then(|value| value.to_str()) else {
        return false;
    };
    let name = name.to_ascii_lowercase();
    match ide {
        "vscode" => matches!(name.as_str(), "code" | "code.exe"),
        "jetbrains" => matches!(
            name.as_str(),
            "idea"
                | "idea.exe"
                | "idea64.exe"
                | "webstorm"
                | "webstorm.exe"
                | "webstorm64.exe"
                | "pycharm"
                | "pycharm.exe"
                | "pycharm64.exe"
                | "goland"
                | "goland.exe"
                | "goland64.exe"
                | "rider"
                | "rider.exe"
                | "rider64.exe"
                | "clion"
                | "clion.exe"
                | "clion64.exe"
                | "rustrover"
                | "rustrover.exe"
                | "rustrover64.exe"
                | "datagrip"
                | "datagrip.exe"
                | "datagrip64.exe"
        ),
        _ => false,
    }
}

#[tauri::command]
fn launch_learning_ide(
    workspace_path: String,
    ide: String,
    executable_path: String,
) -> Result<(), String> {
    let workspace = learning_workspace_path(&workspace_path)?;
    let executable_path = PathBuf::from(executable_path.trim());
    if !allowed_ide_executable(&ide, &executable_path)
        || (!executable_path.is_absolute() && executable_path.components().count() != 1)
    {
        return Err("Choose a VS Code or JetBrains IDE launcher".to_string());
    }
    if executable_path.is_absolute() && !executable_path.is_file() {
        return Err("The configured IDE launcher does not exist".to_string());
    }
    Command::new(executable_path)
        .arg(workspace)
        .spawn()
        .map(|_| ())
        .map_err(|error| format!("Unable to start the IDE: {error}"))
}

#[cfg(test)]
mod tests {
    use super::{allowed_ide_executable, learning_workspace_path};
    use std::path::Path;

    #[test]
    fn allows_only_known_ide_launchers() {
        assert!(allowed_ide_executable("vscode", Path::new("Code.exe")));
        assert!(allowed_ide_executable(
            "jetbrains",
            Path::new("webstorm64.exe")
        ));
        assert!(!allowed_ide_executable(
            "vscode",
            Path::new("powershell.exe")
        ));
        assert!(!allowed_ide_executable(
            "jetbrains",
            Path::new("powershell.exe")
        ));
        assert!(!allowed_ide_executable("unknown", Path::new("idea64.exe")));
    }

    #[test]
    fn opens_only_existing_absolute_workspace_paths() {
        let workspace = std::env::temp_dir();
        assert_eq!(
            learning_workspace_path(workspace.to_str().unwrap()).unwrap(),
            workspace
        );
        assert!(learning_workspace_path("relative/workspace").is_err());
        assert!(learning_workspace_path(
            &workspace.join("missing-learning-workspace").to_string_lossy()
        )
        .is_err());
    }
}

fn stop_managed_backend(app: &tauri::AppHandle) {
    if let Some(state) = app.try_state::<BackendState>() {
        if let Ok(mut child) = state.0.lock() {
            if let Some(child) = child.take() {
                let _ = terminate_child(child);
            }
        }
    }
}

pub fn run() {
    tauri::Builder::default()
        .manage(BackendState(Mutex::new(None)))
        .invoke_handler(tauri::generate_handler![
            backend_status,
            start_backend,
            stop_backend,
            open_learning_workspace,
            launch_learning_ide
        ])
        .setup(|app| {
            // 启动桌面壳时自动拉起后端；失败通过事件交给前端处理，不阻断 Tauri 初始化。
            if !backend_reachable() {
                let state = app.state::<BackendState>();
                if let Err(error) = start_backend(app.handle().clone(), state) {
                    let _ = app.emit("backend-required", error);
                }
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building Tauri application")
        .run(|app, event| {
            // 退出时仅清理本进程管理的 JVM；外部后端由其拥有者负责生命周期。
            if matches!(
                event,
                tauri::RunEvent::ExitRequested { .. } | tauri::RunEvent::Exit
            ) {
                stop_managed_backend(app);
            }
        });
}
