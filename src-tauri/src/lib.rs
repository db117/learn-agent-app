use serde::Serialize;
use std::net::{SocketAddr, TcpStream};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::Duration;
use tauri::{Emitter, Manager, State};

struct BackendState(Mutex<Option<Child>>);

#[derive(Debug, Serialize)]
struct BackendStatus {
    status: String,
    detail: Option<String>,
}

fn backend_addr() -> SocketAddr {
    "127.0.0.1:18080".parse().expect("valid backend address")
}

fn backend_reachable() -> bool {
    TcpStream::connect_timeout(&backend_addr(), Duration::from_millis(150)).is_ok()
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
        detail: Some("127.0.0.1:18080".to_string()),
    }
}

fn sidecar_path(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let suffix = if cfg!(target_os = "windows") {
        ".exe"
    } else {
        ""
    };
    let file_name = format!("agent-backend{suffix}");
    let mut candidates = Vec::new();
    if let Ok(executable) = std::env::current_exe() {
        if let Some(directory) = executable.parent() {
            candidates.push(directory.join(&file_name));
        }
    }
    if let Ok(resource_dir) = app.path().resource_dir() {
        if let Some(triple) = option_env!("TAURI_ENV_TARGET_TRIPLE") {
            candidates.push(resource_dir.join(format!("agent-backend-{triple}{suffix}")));
        }
        candidates.push(resource_dir.join(&file_name));
    }
    candidates
        .iter()
        .find(|path| path.exists())
        .cloned()
        .ok_or_else(|| {
            format!(
                "Backend sidecar not found: {}",
                candidates
                    .first()
                    .map_or(file_name, |path| path.display().to_string())
            )
        })
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
    let child = Command::new(sidecar_path(&app)?)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|error| error.to_string())?;
    *state
        .0
        .lock()
        .map_err(|_| "backend state lock poisoned".to_string())? = Some(child);
    Ok(status(&state))
}

#[tauri::command]
fn stop_backend(state: State<'_, BackendState>) -> Result<BackendStatus, String> {
    if let Some(mut child) = state
        .0
        .lock()
        .map_err(|_| "backend state lock poisoned".to_string())?
        .take()
    {
        child.kill().map_err(|error| error.to_string())?;
        let _ = child.wait();
    }
    Ok(status(&state))
}

fn stop_managed_backend(app: &tauri::AppHandle) {
    if let Some(state) = app.try_state::<BackendState>() {
        if let Ok(mut child) = state.0.lock() {
            if let Some(mut child) = child.take() {
                let _ = child.kill();
                let _ = child.wait();
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
            if !cfg!(debug_assertions) && !backend_reachable() {
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
