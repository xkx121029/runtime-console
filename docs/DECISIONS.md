# 决策记录（DECISIONS）

任务要求：遇到分叉必须按"装不上 → 纯实现替代 → NDK 编译 → 裁功能标 EXPERIMENTAL"的优先级取值，
并把每次选择记在这里。编号连续，早于 D-011 的决策见 `docs/PORTING_AUDIT.md` 与 `docs/CUTS.md`。

---

## D-011 Python 运行时用 Termux CPython 3.14.10，不用 Chaquopy

- **背景**：Octop 要求 Python ≥3.12，且依赖里有大量 C 扩展；Chaquopy 的 ABI 与 Termux 预编译包不通用。
- **决策**：直接复用 Termux 的 `libpython3.14.so` + 标准库 zip（M1 已跑通），依赖用 `pip` 装到
  `files/python-site`，原生扩展用 NDK clang 自编或换 Termux 同名包。
- **代价**：依赖安装不是"pip install"一步到位，需要一份可重放脚本（T-09）。
- **证据**：M1 自检 `PYTHON_VERSION=3.14.10`；M2 `import octop.infra.server` 成功。

## D-012 纯 Python 依赖用 `--platform any` 批量取轮子

- **背景**：本机是 Windows，直接 `pip install` 会挑到 Windows 轮子；manylinux 轮子在 bionic 上不可用。
- **决策**：`pip download --platform any --only-binary=:all: --no-deps --python-version 3.14`，
  只接受 `py3-none-any`；失败的包即"必须原生处理"的清单。
- **效果**：51 个缺包一次压到 27 个，再压到 4 个（tiktoken / sqlite-vec / zstandard / pycryptodome）。

## D-013 `_cffi_backend` 用 NDK clang 自编，且必须链接 `libpython3.14.so`

- **背景**：Termux 仓库没有 `python-cffi` 包，而 Termux 的 `cryptography` 是 cffi 实现，没有它整条链断。
- **决策**：用 Python 3.14 头文件 + NDK clang 编 `cffi 2.0.0` 的 `_cffi_backend`。
- **关键坑**：不链接 `libpython3.14.so` 时能编过，但加载报
  `cannot locate symbol "PyExc_ImportError"`（bionic 不像 glibc 那样容忍未定义符号）。
- **证据**：重编后 `import _cffi_backend` / `import cryptography.exceptions` 均 OK。

## D-014 cryptography / lxml / Pillow / rpds-py / pycryptodomex 换 Termux 版

- **背景**：PyPI 的 manylinux 轮子依赖 glibc 符号，在 bionic 上加载即失败或行为异常。
- **决策**：从 `packages.termux.dev` 取同版本（能对上就对上）的 x86_64 包，解包后覆盖进 `python-site`。
- **代价**：这些包会携带对系统库的依赖，必须把 `libxml2/libxslt/libjpeg/libwebp/...` 一起塞进
  `assets/runtime-libs`，并且预加载顺序要两轮（见下一条）。
- **风险**：Termux 版本随仓库滚动，需要把版本号写死并校验（T-03）。

## D-015 `mcp` 锁 1.30.0

- **背景**：Octop 声明 `mcp<2`，但第一次解析到了 2.3.0；2.3.0 移除了 `McpError`，
  `octop_harness` 导入即崩（还会去 import `mcp.types.TASK_STATUS_COMPLETED`）。
- **决策**：安装 `mcp==1.30.0`，并把 2.3.0 的残留目录整个删掉后重装（只覆盖会留下 2.x 专有模块）。
- **证据**：`import octop_harness` → OK；`build_app` 69 条路由。

## D-016 扩展预加载改成两轮

- **背景**：`libexslt.so` 依赖 `libxml2.so.16`，但按文件名字母序 `libexslt` 先加载，首轮必失败；
  bionic 不会为已失败的依赖自动重试。
- **决策**：`sitecustomize.py` 里对预加载列表做最多两轮，第二轮再报错。
- **影响面**：只影响我们自己的启动垫片（`scripts/build-runtime.ps1` 生成），不碰上游代码。

## D-017 Octop 启动探针先用"进程外 adb exec"验证，再进 App

- **背景**：不要为了 M2 验收就去改 App 的进程内启动逻辑，那样会把两个问题混在一起。
- **决策**：M2 的验收标准定在"设备上 127.0.0.1 能返回 200"，用 `run-as` + 显式环境变量执行；
  进程内集成留到 M3/M4。
- **证据**：`GET /api/setup/status → 200`（`adb forward`），`netstat` 仅 `127.0.0.1`。

## D-018 tiktoken 暂不编译，标 EXPERIMENTAL

- **背景**：`tiktoken` 是 Rust 扩展，本机 Rust 工具链只剩空壳（`~/.rustup/toolchains/stable-*/bin`
  不存在，`rustc` 不可用），重装工具链 + 拉 crates 有超过 25 分钟熔断的风险。
- **决策**：本轮不编，先记录影响面：`import langchain_openai` 失败 → OpenAI 兼容 provider 暂不可用。
- **后续选项**（按优先级）：① 重装 Rust 后 `cargo build --target x86_64-linux-android`；
  ② 用 langchain 的 `transformers`/`tokenizers` 计数替身；③ 直连 OpenAI 的兼容层绕过 langchain_openai。
- **说明**：不做假实现（不写"看起来能数 token"的桩），避免静默产生错误计费/截断。

## D-019 回环明文 HTTP 用 networkSecurityConfig 定向放行

- **背景**：targetSdk 36 默认 `cleartextTrafficPermitted=false`，`HttpURLConnection` 访问
  `http://127.0.0.1:3080` 直接抛 `CleartextNotPermitted`，UI 永远判不出"在线"。
- **决策**：新增 `app/src/main/res/xml/network_security_config.xml`，只对 `127.0.0.1`、`localhost`
  放开明文；不使用全局 `usesCleartextTraffic=true`。
- **影响面**：仅清单声明，不改任何内核代码；公网访问仍受系统默认策略约束。

## D-020 兼容库按依赖层级排序预加载

- **背景**：D-016 的两轮加载能兜底，但 App 进程内仍报 `libexslt.so: library "libxml2.so.16" not found`。
- **决策**：`sitecustomize.py` 的预加载顺序改为 `libiconv → libxml2 → libxslt → libexslt → 其余按名字`，
  仍保留两轮重试。
- **证据**：重装后 `files/logs/octop-api.log` 无 `compat-preload-warn`；旧日志（含告警）保留为
  `octop-api.log.1`。

## D-021 App 启动即自动拉起两个内核，冷启动容忍 5 分钟

- **背景**：设备侧验收要求"打开 App 就能用"；但模拟器上并发拉起时 dsh 要 4 分半才监听。
- **决策**：默认自动拉起（偏好 `runtime-console/auto-run-kernels` 可关）；`LoopbackGateway` 把
  "进程在跑但端口未通"且启动不足 5 分钟的状态显示为「启动中」，超过则判「异常」。
- **影响面**：只影响 UI 状态机，不改变内核启动参数；单内核取证仍可用 intent extras 单独拉起。

## D-022 "打开"先走系统浏览器（ACTION_VIEW），内嵌 WebView 留到下一轮

- **背景**：dsh 控制台是完整 Web 应用，内嵌 WebView 要处理 token 注入、返回键、下载/新窗口等。
- **决策**：M4 用 `Intent.ACTION_VIEW` 打开带 token 的回环地址，先把"能用"打通；
  内嵌 WebView 作为体验优化排在首装打包（T-10）之后。
- **证据**：`AndroidSystemBridges.openLocalUrl`；地址由 `KernelId.openUrl(token)` 生成。

## D-023 内核控制台改为应用内 WebView（取代 D-022 的外部浏览器）

- **背景**：外部浏览器会另起一个上下文（token 出现在地址栏、返回键回到桌面），不符合"单 App"体验。
- **决策**：新增 `KernelWebViewActivity`（`exported=false`），`bridges` 通过 `ComponentName` 拉起，
  传入 `kernel_url` / `kernel_title`；开启 JS 与 DOM Storage，禁用 file/content 访问；
  非 `127.0.0.1`/`localhost` 的外链交给系统浏览器。
- **影响面**：只新增一个 Activity 与桥接实现，不动内核；桌面入口仍只有 MainActivity。
- **证据**：dsh 控制台在 WebView 中完整渲染（`docs/evidence/m5-dsh-console-webview.png`）。

## D-024 用前台服务保活内核进程，不搬内核所有权

- **背景**：两个内核是 App 进程的子进程；系统回收 App 后端口消失，WebView 变空白（T-11 的真实成因）。
- **候选**：(a) 把内核搬进独立进程/服务并重做跨进程状态同步；(b) 前台服务仅提升进程优先级。
- **决策**：选 (b)。`KernelService` 不持有 `KernelSupervisor`，只 `startForeground()`；
  状态仍由 Activity 里的 supervisor/gateway 提供，避免跨进程序列化与生命周期错配。
- **代价**：常驻一条低优先级通知（可被用户隐藏）；进程被系统强杀后内核不会自愈重启。
- **证据**：`dumpsys activity services` → `isForeground=true`；退桌面 100 s 后内核端口与 HTTP 均正常。
