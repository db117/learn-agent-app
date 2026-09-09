use serde::Serialize;
use std::net::{SocketAddr, TcpStream};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};
use tauri::{Emitter, Manager, State};

const BACKEND_HOST: &str = "127.0.0.1";
const BACKEND_PORT: u16 = 18080;
const BACKEND_JAR: &str = "agent-backend.jar";
const BACKEND_STARTUP_TIMEOUT: Duration = Duration::from_secs(60);
const BACKEND_POLL_INTERVAL: Duration = Duration::from_millis(200);

struct BackendState(Mutex<Option<Child>>);

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
        // Tauri may return a verbatim Windows path, which java.exe does not accept for -jar.
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
    if backend_reachable() {
        return Ok(status(&state));
    }
    let jar = normalize_jar_path(backend_jar_path(&app)?);
    let mut command = Command::new("java");
    command
        .arg("-jar")
        .arg(jar)
        .arg(format!("--server.address={BACKEND_HOST}"))
        .arg(format!("--server.port={BACKEND_PORT}"));
    if !std::env::var("OPENAI_API_KEY")
        .map(|value| !value.trim().is_empty())
        .unwrap_or(false)
    {
        command.arg("--spring.ai.model.chat=none");
    }
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
            stop_backend
        ])
        .setup(|app| {
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
            if matches!(
                event,
                tauri::RunEvent::ExitRequested { .. } | tauri::RunEvent::Exit
            ) {
                stop_managed_backend(app);
            }
        });
}
