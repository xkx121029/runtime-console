# 项目状态（STATE）

最后更新：2026-11-07 23:59（Asia/Shanghai）

## 当前上游 SHA

| 仓库 | 路径 | SHA | 版本 / 日期 |
|---|---|---|---|
| deepseek-harness | `upstream/deepseek-harness` | `fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159` | 0.2.2-rc.1 / 2026-11-04 |
| Octop | `upstream/octop` | `4022e24ef6c34e9716350872b0e93bfaae7a20c3` | 1.0.3b4 / 2026-11-06 |

## 里程碑进度

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 审计 | 进行中 | submodule 已固定；审计文档已产出；运行时 hello 与工程骨架待完成 |
| M1 运行时 | 未开始 | Node/Python 运行时落地到 APK `lib/` |
| M2 内核启动 | 未开始 | 两个内核真实启动 |
| M3 桥接 | 未开始 | gateway + bridges |
| M4 UI | 未开始 | 最朴素可用界面 |

## 已完成

1. `git init`（main 分支）、`.gitignore`。
2. 两个上游以 submodule 固定 SHA（`git submodule status` 可复现）。
3. 事实基线核对：dsh 的 Node `>=24.9`、pnpm 11.28.5、284 个包、Electron/Playwright/node-pty/sharp 依赖；Octop 的 Python `>=3.12`、FastAPI/uvicorn、默认 `127.0.0.1:8088`、`~/.octop`。
4. 产出 `docs/PORTING_AUDIT.md`、`docs/CUTS.md`、`DECISIONS.md`。

## M0 结论

**conditional-go**。理由见 `docs/PORTING_AUDIT.md` 第三节；两个硬门槛（Node 24 vs nodejs-mobile 18；Python 3.12 vs Chaquopy 能力）都必须在本轮真机/模拟器上取证，取证失败则按 CUTS/降级处理，不阻塞骨架推进。

## 下一步（按顺序）

1. 最小 Android 工程骨架（Kotlin + Compose，模块 `core`/`runtime-host`/`gateway`/`bridges`/`ui`，包名 `dev.runtimeconsole.app`），`assembleDebug` 通过。
2. Node hello：nodejs-mobile v18.20.4 预编译包中取 x86_64 `libnode.so`，在模拟器 `/data/local/tmp` 验证版本、文件读写、`127.0.0.1` HTTP 监听。
3. Python hello：优先 Chaquopy（验证 3.12 可用性）；失败则用 Termux 预编译 Python 做设备侧 POC 并如实标注。
4. M1：运行时放进 APK `lib/`（`lib*.so`），由 `RuntimeHost` 启动，不再依赖 `/data/local/tmp`。

## 已知坑

1. `github.com:443` 间歇不可达；`api.github.com`/`codeload.github.com`/`ghfast.top` 可达。克隆失败先走 `https://ghfast.top/https://github.com/...`。
2. 任务书写的 `TencentCloud/Octopus` 不存在，实际是 `TencentCloud/Octop`（见 DECISIONS D-001）。
3. nodejs-mobile 最新只到 Node 18.20.4，dsh 要求 Node ≥24.9 —— M1 必须正面验证 dsh 能否在 Node 18 启动。
4. Android `homedir()` 不可写，必须注入 `HOME` / `DSH_HOME` / `OCTOP_HOME`。
5. 模拟器是 x86_64（API 36），真机 arm64 需另验；报告必须标明验证环境。

## 技术债

| 编号 | 内容 | 影响 |
|---|---|---|
| T-01 | 上游补丁尚未产生（M0 未改上游） | M1 起每处改动出 `.patch` 并写 `docs/UPSTREAM.md` |
| T-02 | 运行时二进制在 `/data/local/tmp` 仅为 POC | 正式形态必须进 APK `lib/` |
| T-03 | 未验证 Chaquopy 是否支持 Python 3.12 | 决定 Octop 可用面 |

## 验证环境

| 项 | 值 |
|---|---|
| 设备 | `emulator-5560`（sdk_gphone64_x86_64，API 36，Android 16，x86_64） |
| adb | `c:\Users\linch\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| JDK | `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`（21.0.12.1） |
| Gradle | 本机缓存 8.14.3 / 9.x |
| SDK | platforms 33–36，NDK 26.1/27.0/28.2，CMake 3.22.1 |
