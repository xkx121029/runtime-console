# Runtime Console

在 Android 应用进程内运行本地 Node.js 与 CPython 运行时，承载两个自带内核（一个 Web 控制台、一个管理接口 API），
并提供统一的状态、起停、日志与应用内 WebView 控制台。

- 所有监听地址仅 `127.0.0.1`，不提供局域网访问。
- 内核二进制以 `lib*.so` 形式随 APK 分发，`exec` 自应用自己的 `nativeLibraryDir`。
- 大型运行时数据（Node 依赖树、Python site-packages）**不随 APK 分发**，通过「运行时包」在首装时导入。
- 远端推送尚未完成：本机没有 GitHub 凭据，Gitee 令牌可鉴权但创建仓库返回 403，详见 `docs/STATE.md`。

构建方式、模块划分与设备取证过程见 [docs/STATE.md](docs/STATE.md)；
上游改动与补丁见 [docs/UPSTREAM.md](docs/UPSTREAM.md) 与 `patches/`。

## 能力状态（三档标注）

标注含义：**已验证**＝有设备实测证据；**EXPERIMENTAL**＝代码已实现、只在特定条件下跑通或未覆盖异常路径；
**计划中**＝尚未实现。已验证范围仅限 **x86_64 模拟器（Android 16 / API 36）**，除非另行注明。

### 已验证

| 能力 | 证据 |
|---|---|
| 从 APK `lib/` 启动 Node 与 CPython，HTTP 自请求 200 | `docs/STATE.md` M1 |
| 两个内核在设备上返回 HTTP 200（仅 `127.0.0.1`） | M2 |
| App 内起停、健康探活、RSS / PID / 日志尾、dsh token 解析 | M3–M4，`docs/evidence/m4-console-2of2.png` |
| 应用内 WebView 加载控制台（不跳外部浏览器） | M5，`docs/evidence/m5-dsh-console-webview.png` |
| 前台服务保活：退桌面 100 s 后内核仍在监听 | M6 |
| 管理接口首启向导 → 登录 → 主界面完整渲染 | `docs/evidence/t11-main-ui-3.png` |
| 运行时包格式：manifest + SHA-256 + 原子替换 + 防路径穿越 | `runtime-host` `RuntimePack.kt` |
| **tar / tar.gz 导入路径**：整包字节直灌系统 `tar` stdin | 133 MB 树 / 8,660 文件 **28.0 s**（含校验 2.8 s）；350.7 MB tar / 16,434 条目 **93.4 s**（校验 17.8 s，约 4.4 MB/s 产出、217 文件/s） |
| **升级保留上一版本 + 回滚** | `files/runtime-pack.json` 记录 `prevPackVersion`；`--ez runtime_pack_rollback true` 实测回滚成功、目录换回 |
| WebView 加载进度、主框架失败 / 5xx 错误页与重试 | `KernelWebViewActivity.kt` |

### EXPERIMENTAL

| 能力 | 限制 |
|---|---|
| 全量 69,498 文件首次导入 | tar 路径按实测吞吐外推 **2.7–5.3 分钟**（无内核争抢 IO 时 429 文件/s，双内核在线时 217 文件/s），**没有跑过完整 69k 文件的端到端**，3 分钟目标未证实 |
| arm64 运行时产物 | 能产出 arm64 APK（`lib/arm64-v8a` 14 个 `.so`、ELF AArch64），**未上真机**；本机无 arm64 设备、模拟器不支持 arm64 镜像 |
| 运行时包导入（SAF 选目录） | 格式、校验、回滚已完成；导入期间 App 需保持前台 |
| 运行时分包 HTTP 下载与 Range 断点续传 | 无公共分发服务器；服务端忽略 `Range` 时退化为整包重下 |
| 管理接口 dashboard 冷启动 | 首次 `GET /` 可能 20 s 不返回（热态 1.4 s / 200） |
| 模拟器上的启动时延 | release 冷启动 `Displayed +14.5 s`（x86_64 API 36，内核未启动时）；早期 debug 包测到 95–110 s，不作为性能结论 |
| release 签名 | 没有 `keystore.properties` 时回退 debug 签名，产物可安装但只适合自测 |

### 计划中

| 能力 | 说明 |
|---|---|
| 一键全自动首装 | 当前必须由用户把运行时包放进存储后手动选择目录 |
| App 内回滚按钮 | 目前只有 adb 入口 |
| 增量安装（跳过已存在文件） | 现在每次都整包替换，只是保留上一版本 |
| 按需单内核启动 + 空闲回收 | 两个内核同时在线的内存峰值尚未做长稳测量 |
| GitHub 推送 | 本机无 `gh`、无 GitHub 令牌 |
| CI 门禁 | 目前以本地构建为准 |
| 应用内更新内核版本 | 需与运行时包版本号联动 |

## 首装（全新设备）

Node 与 Python 运行时数据约 1.5 GB，不随 APK 分发。完整步骤、包格式与实测耗时见
[docs/DISTRIBUTION.md](docs/DISTRIBUTION.md)：

1. 安装 APK；
2. 在 PC 侧生成运行时包（推荐 tar：`tar -czf dsh.tar.gz -C <目录> .`），并写 `manifest.json`；
3. 把包放进设备存储的任意目录；
4. 打开 App →「运行时组件」→「导入运行时包」→ 选择该目录；
5. 导入完成后内核自动/手动启动，控制台可打开。

升级时重复第 2–4 步；旧版本自动保留为 `<target>.prev`，校验失败旧目录不动。

## 构建

```powershell
# 路径含 & 时 Android Gradle 插件不可直接用，先建目录联接
cmd /c mklink /J C:\rc "E:\happy_dsh&octopinphone"
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"

C:\rc\gradlew.bat -p C:\rc :app:assembleDebug
C:\rc\gradlew.bat -p C:\rc :app:assembleRelease
```

工具链（实测可重现）：JDK 21.0.12（Microsoft Build）、Gradle 8.14.2、AGP 8.13.2、Kotlin 2.2.21、
compileSdk 36、minSdk 26、NDK 不需要（原生库来自 Termux 预编译产物）。
release 签名：在仓库根放 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`），
该文件已被 `.gitignore` 排除；缺失时回退 debug 签名以保证产物可安装。

arm64 运行时产物（能出 APK，未上真机）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-runtime-arm64.ps1
# 输出：.tmp\arm64-jniLibs\arm64-v8a\（14 个 .so，ELF AArch64）
#       .tmp\arm64-assets\（python-stdlib.zip、runtime-libs、launcher、selftest）

.\gradlew.bat :app:assembleDebug -PruntimeAbi=arm64-v8a `
    -PruntimeAssetsDir="<repo>\.tmp\arm64-assets" `
    -PruntimeJniLibsDir="<repo>\.tmp\arm64-jniLibs"
# 之后不传属性即为默认 x86_64 包，两套产物不共用
```

## 许可

本仓库代码以 MIT 发布，见 [LICENSE](LICENSE)。随附的运行时、原生库、Python 与 Node 依赖
的许可证清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，App 内「开源许可」页可离线查看并复制全文。

---

# Runtime Console (English)

Runtime Console runs a local Node.js and CPython inside an Android app process, hosting two
bundled agent kernels (a web console and a management API) with a single UI for status,
start/stop, logs, and an in-app WebView.

- Kernels bind to `127.0.0.1` only.
- Runtime binaries ship as `lib*.so` inside the APK and are executed from `nativeLibraryDir`.
- Large runtime data (Node dependency tree, Python site-packages, ~1.5 GB) is **not** bundled;
  it is imported once from a "runtime pack".

## Status (three tiers)

**Verified** means device evidence exists. Verified scope is the **x86_64 emulator (Android 16 / API 36)**
unless stated otherwise.

### Verified

| Capability | Evidence |
|---|---|
| Node and CPython launched from APK `lib/`, self HTTP request returns 200 | `docs/STATE.md` M1 |
| Both kernels return HTTP 200 on device (`127.0.0.1` only) | M2 |
| In-app lifecycle control, health probing, RSS / PID / log tail, token parsing | M3–M4 |
| In-app WebView console (no external browser) | M5 |
| Foreground-service keep-alive: kernels still listening 100 s after backgrounding | M6 |
| Management API first-run wizard → login → full main UI | `docs/evidence/t11-main-ui-3.png` |
| Runtime pack: manifest + SHA-256 + atomic swap + path-traversal guard | `RuntimePack.kt` |
| **tar / tar.gz import** (stream straight into system `tar` stdin) | 133 MB tree / 8,660 files in **28.0 s** (incl. 2.8 s verify); 350.7 MB tar / 16,434 entries in **93.4 s** (~4.4 MB/s, 217 files/s) |
| **Upgrade keeps previous version + rollback** | `prevPackVersion` in `files/runtime-pack.json`; `--ez runtime_pack_rollback true` verified |
| WebView progress, main-frame / 5xx error page and retry | `KernelWebViewActivity.kt` |

### Experimental

| Capability | Limitation |
|---|---|
| Full 69,498-file first import | Extrapolated **2.7–5.3 min** from measured throughput (429 files/s idle, 217 files/s with both kernels running); the full 69k end-to-end run was **not** performed, so the 3-minute goal is unproven |
| arm64 artifacts | arm64 APK builds (`lib/arm64-v8a`, 14 `.so`, ELF AArch64) but has **never run on an arm64 device** |
| Runtime pack import (SAF folder picker) | Format, verification and rollback done; app must stay in foreground |
| HTTP pack download with Range resume | No public distribution server; falls back to full re-download when `Range` is ignored |
| Management API dashboard cold start | First `GET /` may take ~20 s (warm 1.4 s / 200) |
| Emulator startup latency | Cold start 95–110 s, Compose first frame 30–45 s; not a performance claim |
| Release signing | Falls back to the debug key when `keystore.properties` is absent; installable but self-test only |

### Planned

Fully automatic first install; in-app rollback button; incremental install (skip existing files);
on-demand single-kernel start + idle reclaim; GitHub push (no `gh`/token on this machine); CI gate;
in-app kernel version updates.

## Build

```powershell
cmd /c mklink /J C:\rc "<repo>"
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
C:\rc\gradlew.bat -p C:\rc :app:assembleDebug
C:\rc\gradlew.bat -p C:\rc :app:assembleRelease
```

Toolchain: JDK 21.0.12 (Microsoft Build), Gradle 8.14.2, AGP 8.13.2, Kotlin 2.2.21, compileSdk 36,
minSdk 26. No NDK needed (native libraries come from prebuilt Termux packages).
Release signing reads `keystore.properties` from the repo root (git-ignored); without it the release
build is signed with the debug key so the APK remains installable.

## License

MIT for this repository (see [LICENSE](LICENSE)). Third-party licenses are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and are readable/copyable offline in the app.
