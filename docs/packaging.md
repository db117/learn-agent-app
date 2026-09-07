# 打包

打包后的应用包含 Tauri 前端和一个 Native 后端 sidecar。不包含 JRE，也不依赖系统
Java 安装。

## 构建

在每个目标操作系统/架构上执行：

```bash
pnpm native:build
pnpm native:check
pnpm build
pnpm exec tauri build
```

类 Unix 系统上的 Native 可执行文件是 `backend/target/agent-backend`，Windows 上
是 `backend/target/agent-backend.exe`。执行 `tauri build` 前，必须将 Tauri 外部
sidecar 复制到 `src-tauri/binaries/agent-backend-<target-triple>`；后缀是本次构建的
Rust 目标三元组，例如 `aarch64-apple-darwin`、`x86_64-pc-windows-msvc` 或
`x86_64-unknown-linux-gnu`。

`src-tauri/tauri.conf.json` 声明不带后缀的逻辑 sidecar 名称，Tauri 会在打包时解析
平台后缀。`src-tauri/binaries/` 被忽略，因为二进制文件是目标产物而不是源文件。

macOS arm64 第一阶段包已通过 `pnpm exec tauri build --debug` 在本地验证，同时生成
`.app` 和 `.dmg`；包内的 Tauri 可执行文件旁边包含 `agent-backend`。Tauri 初始化时
由 Rust 启动该 sidecar，并在应用正常退出时停止受管理的子进程。

## 运行时生命周期

Rust 壳负责管理 sidecar 子进程，并暴露 `backend_status`、`start_backend` 和
`stop_backend` 命令。Tauri 进程退出时会停止子进程。开发环境可以将 JVM 后端作为
独立进程运行；生产环境使用 Native sidecar，并复用同一个固定回环地址。

第一阶段不包含更新器、安装器专用服务、远程后端或 JRE 打包。
