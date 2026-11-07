# 上游事实基线与移植分级（PORTING_AUDIT）

审计日期：2026-11-07（Asia/Shanghai）
上游 SHA：
- `deepseek-harness` = `fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159`（master，2026-11-04，版本 `0.2.2-rc.1`）
- `Octop` = `4022e24ef6c34e9716350872b0e93bfaae7a20c3`（`release/1.0.3b4`，2026-11-06）

分级口径：
- **A** 纯解释器/纯 Python/纯 JS，可直接在 Android 跑
- **B** 可在构建期替换或降级为等价实现（不改上游业务）
- **C** 需要交叉编译、补丁或特定打包形态，风险高但可行
- **D** Android 上不可用，必须裁剪

> 本文件所有"方案"均为静态审计结论；标 `未验证` 的项在 M1/M2 之前没有运行证据。

---

## 一、deepseek-harness（dsh，TypeScript / Node）

### 1. 事实基线

| 项目 | 事实 | 证据 |
|---|---|---|
| 语言 | TypeScript（ESM，`"type": "module"`），全部插件化（Cordis） | `upstream/deepseek-harness/package.json:6`、`README.md:7` |
| 包管理器 | pnpm 11.28.5（workspaces） | `upstream/deepseek-harness/package.json:7` |
| Node 要求 | `>=24.9.0` | `upstream/deepseek-harness/package.json:8-10` |
| 入口 | `apps/cli/src/bin.ts` → `runCli()`；发布命令 `dsh` | `upstream/deepseek-harness/package.json:211`、`apps/cli/src/bin.ts:26` |
| 运行形态 | `dsh web` 起本机 Web UI，默认 `http://127.0.0.1:3080` | `upstream/deepseek-harness/README.md:27` |
| 规模 | 284 个 workspace 包，仓库约 115MB | 统计命令输出 |
| 数据目录 | `~/.dsh`，可用 `DSH_HOME` 覆盖；`~/.agents`，可用 `DSH_AGENTS_HOME` 覆盖 | `packages/util/home-paths/src/index.ts:12,18,135-153` |
| 路径假设 | 使用 `os.homedir()`，未硬编码 `/tmp`、`XDG_*`（仅 29 处路径相关命中，集中在 ssh/sandbox/desktop 辅助） | `Grep XDG_|/tmp/|homedir()` 结果 |
| 平台分支 | Linux/macOS 原生包；Windows 走既有实现 | `native/system/README.md:31` |

### 2. 依赖分级

| 名称 | 版本 | 分级 | 方案 | 证据 |
|---|---|---|---|---|
| Node 运行时 | `>=24.9` | **B（已实测）** | 用 Termux 预编译 Node 26.13.0（bionic）以 `lib*.so` 进 APK `lib/` 后 exec；nodejs-mobile（Node 18）因缺 `node:sqlite` 被裁 | `package.json:8-10`；`packages/storage/storage-sqlite/src/unit.ts:11`（`import type { DatabaseSync } from 'node:sqlite'`）；设备实测见 `docs/STATE.md` |
| pnpm workspace 安装 | 11.28.5 | **B** | 在 PC 上 `pnpm install && pnpm build`，只把构建产物打入 APK；设备上不装依赖 | `package.json:7,20-41` |
| TypeScript 运行时依赖 | 全 ESM | **A（理论上）** | Node 18 需确认是否用到 Node 20+ API（如 `node:sqlite`、import attributes） | `package.json:6` |
| Electron 桌面壳 | `^44.7.0` | **D** | 裁剪：Android 用原生 Compose 壳替代 | `apps/desktop/package.json:71` |
| Playwright | `^1.49.0` | **D** | 裁剪：无 Chromium 可用；`browser-use` 系列全部降级为不可用 | `apps/web/package.json:58`、`benchmarks/package.json:8` |
| node-pty | `1.2.0-beta.15` | **D** | 裁剪：无 Android 预编译，需 NDK 重编 fortify 版本；终端内嵌功能降级 | `packages/subprocess/subprocess-local/package.json:55` |
| sharp（libvips） | `0.35.5` | **C** | 附件缩略图功能可降级：改用不依赖 sharp 的纯 JS 缩放或裁剪 | `packages/attachment/attachment-local/package.json:39` |
| 原生插件 `system.node` | Node-API v8 | **C** | Android bionic 需重编；flock 在 Android 可用 `flock(2)`，Landlock 依赖内核；失败即降级为"不隔离" | `native/system/README.md:23-35` |
| `landlock-run`（静态 musl） | 随包 | **C** | 静态可执行文件在 Android 可能可直接运行（无 libc 依赖），但 Landlock 需内核支持；探测失败即视为不可用 | `native/system/README.md:31` |
| SQLite 存储 | 自研 `storage-sqlite` | **A/B** | 依赖 Node 内置/纯 JS 实现；需在设备上验证 | `packages/storage/storage-sqlite/package.json:35-37` |
| 浏览器打开（`open`） | — | **B** | Android 上改为 Intent 打开或直接不打开 | `README.md:27` |
| SSH / 远程桌面 | — | **D** | 裁剪（需要系统 ssh、桌面协议栈） | `Grep` 命中 `packages/ssh/*` |

### 3. 关键冲突（以代码为准）

1. **Node 版本冲突（最致命）**：需求假定"Node 优先 nodejs-mobile"，但 nodejs-mobile 最新只到 Node **18.20.4**，dsh 要求 **≥24.9**。二者不可能同时成立。
   → 处理：M0 只证明"Node 能在设备上跑 hello"；dsh 内核在 Node 18 上能否启动列为 M1 的第一验证项。若失败，dsh 内核标 EXPERIMENTAL 并给出交叉编译 Node 24 的独立任务。
2. **Electron/Chromium 冲突**：dsh 的 Desktop/Web 都假定有 Chromium 生态；Android WebView 不能替代 Playwright。
3. **`~/.dsh` 冲突**：Android 上 `homedir()` 指向 `/`（无家目录写权限），必须注入 `DSH_HOME` + `HOME`。这是补丁级改动，记入 `docs/UPSTREAM.md`。

---

## 二、Octop（Python / FastAPI）

### 1. 事实基线

| 项目 | 事实 | 证据 |
|---|---|---|
| 语言 | Python `>=3.12` | `upstream/octop/pyproject.toml:11` |
| 包管理器 | uv（`uv run pytest`） | `upstream/octop/AGENTS.md`（§3） |
| 入口 | `octop = octop.cli.main:cli`；服务启动 `octop run` → `launch.run_foreground_blocking` | `upstream/octop/pyproject.toml:102-103`、`src/octop/launch.py:59-89` |
| 默认监听 | `bind_host = 127.0.0.1`，`port = 8088` | `upstream/octop/src/octop/config.py:126-127` |
| 数据目录 | `~/.octop/`，可用 `OCTOP_HOME` 覆盖 | `README.md:65,69` |
| 架构 | 单进程 FastAPI + uvicorn，SQLite(WAL)/PostgreSQL，无外部消息队列 | `README.md:106` |
| 运行时库 | `octop-harness`、`octop-gateway`、`octop-memory`、`octop-browser`（独立 PyPI 包） | `README.md:99-106` |
| 前端 | React 18 + Vite，产物在 `src/octop/dashboard/` | `AGENTS.md`（§4） |

### 2. 依赖分级

| 名称 | 版本 | 分级 | 方案 | 证据 |
|---|---|---|---|---|
| CPython | `>=3.12` | **B（已实测）** | 采用 Termux 预编译 CPython 3.14.10（bionic），`PYTHONHOME` 注入 | `pyproject.toml:11`；设备实测见 `docs/STATE.md` |
| fastapi | `>=0.110` | **A** | 纯 Python | `pyproject.toml:14` |
| uvicorn[standard] | `>=0.27` | **A/C** | `[standard]` 含 `uvloop`/`httptools`（C 扩展）；降级为纯 Python 模式（`--http h11`） | `pyproject.toml:15` |
| pydantic | `>=2.6,<2.14` | **C** | `pydantic-core` 为 Rust 扩展，需 Android 轮子 | `pyproject.toml:16` |
| langchain-core | `>=1.4.8` | **A** | 纯 Python | `pyproject.toml:17` |
| apscheduler | `>=3.10,<4` | **A** | 纯 Python；后台调度在 Android 需配合前台服务 | `pyproject.toml:21` |
| argon2-cffi | `>=23.1` | **C** | CFFI 扩展，需编译 | `pyproject.toml:22` |
| cryptography | `>=41` | **C** | Rust 扩展，需 Android 轮子 | `pyproject.toml:28` |
| psycopg[binary] | `>=3.2` | **D（可裁）** | 默认 SQLite，PostgreSQL 可选 → 裁剪 | `pyproject.toml:38`、`config.py(默认 sqlite)` |
| playwright | `>=1.40` | **D** | Browser AI+ 裁剪 | `pyproject.toml:37` |
| octop-browser | `>=1.0.0` | **D** | 依赖 CDP/Chromium，裁剪 | `pyproject.toml:36` |
| octop-gateway | `>=1.0.3` | **C** | IM 长连接（飞书/钉钉/微信等）：纯 Python 部分可用，但通知/媒体/二维码绑定需实测 | `pyproject.toml:27` |
| octop-harness[all] | `>=1.0.8` | **C** | `all` 含浏览器/桌面 extras；Android 上应只装核心 extra | `pyproject.toml:24` |
| octop-memory | `>=1.0.3` | **B** | SQLite FTS 全文检索，需确认 Android SQLite 的 FTS5 支持 | `pyproject.toml:25` |
| lark-oapi / tencentcloud-sdk-python | `>=1.7.3` / `>=3.1.166` | **A** | 纯 Python（网络可用性另计） | `pyproject.toml:26,50` |
| boto3 | `>=1.40.61` | **A** | 纯 Python；仅 COS/S3 后端使用 | `pyproject.toml:35` |
| pillow | `>=13.0.0` | **C** | 需 Android 轮子或裁剪图片处理 | `pyproject.toml:41` |
| pypdf / python-docx / python-pptx / openpyxl / xlrd | 见清单 | **A** | 纯 Python | `pyproject.toml:42-47` |
| mcp | `>=1.9,<2` | **A** | 纯 Python | `pyproject.toml:40` |
| edge-tts / segno / websockets | 见清单 | **A** | 纯 Python | `pyproject.toml:30-32` |
| acme / josepy | `>=5.6` / `>=2.2` | **D（可裁）** | Let's Encrypt 证书申请，Android 无意义 → 裁剪 | `pyproject.toml:33-34` |
| webauthn / pyotp / ldap3 | 见清单 | **B** | 纯 Python，可作为可选功能保留或裁剪 | `pyproject.toml:48-52` |
| 远程桌面（mss/pynput） | extras | **D** | 裁剪 | `pyproject.toml:77` |
| 本地 ONNX embedding | extras | **D（可裁）** | 无 Android onnxruntime 轮子 → 裁剪 | `pyproject.toml:79-84` |

### 3. 关键冲突

1. **Python 3.12 与 Chaquopy**：Chaquopy 版本需支持 3.12；未验证。
2. **Rust/C 扩展轮子**：`pydantic-core`、`cryptography`、`argon2-cffi` 是三条硬门槛；全部失败则 Octop 无法以原样运行，只能降级为"纯 Python 子集"（例如只保留 CLI 配置读取），这属于重大降级。
3. **`~/.octop` 冲突**：Android 无家目录写权限，必须注入 `OCTOP_HOME` + `HOME`。
4. **Playwright / Chromium**：结构性不可用，直接裁剪。

---

## 三、结论

详见 `docs/STATE.md` 的 M0 结论：**conditional-go**。
理由：两侧都存在"运行时版本"这一硬门槛（Node 24 vs nodejs-mobile 18；Python 3.12 vs Chaquopy 未知），但它们都是可验证的工程问题，而不是架构性不可行；同时两侧都有明确的裁剪清单可以把不可用能力隔离。

## 四、M0 实测补充（2026-11-08）

运行时路线已用设备实测取代静态推断：

| 运行时 | 结论 | 证据 |
|---|---|---|
| Node | Termux 预编译 `node 26.13.0` 在 API 36 x86_64 上运行成功；`node:sqlite` 路线得以保留 | `docs/STATE.md`「实测证据 · Node」 |
| Python | Termux 预编译 CPython `3.14.10` 运行成功，满足 `>=3.12` | `docs/STATE.md`「实测证据 · Python」 |

仍未验证、不得写成"通过"的项：

1. 运行时从 APK `lib/` 目录启动（M1）。
2. dsh / Octop 内核在设备上的启动与端口监听（M2）。
3. `pydantic-core`、`cryptography`、`argon2-cffi` 等二进制轮子在 bionic 上的可安装性。
4. arm64 真机。

