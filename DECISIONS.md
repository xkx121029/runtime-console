# 决策记录（DECISIONS）

本文件记录移植过程中所有"分叉点"的取值与理由。凡需求未覆盖或与上游代码冲突处，均在此留痕。
格式：`D-编号 | 日期 | 主题 | 决策 | 理由 | 影响面`。

## 上游身份

| 编号 | 决策 | 理由 | 证据 |
|---|---|---|---|
| D-001 | 上游 Octop 仓库实名是 `TencentCloud/Octop`（无 "us"），本地路径 `upstream/octop` | 任务书写的是 `TencentCloud/Octopus`，GitHub 上该名不存在（`git ls-remote` 返回 `repository not found`）；实际开源仓库为 `Octop`，与工作目录名 `octopinphone` 一致 | `git ls-remote https://github.com/TencentCloud/Octopus` → fatal: not found；`git ls-remote https://github.com/TencentCloud/Octop` → 4022e24e… |
| D-002 | 上游以 submodule 固定 SHA，不拷源码进 `src/` | 硬性约束；便于日后 `git diff upstream/...` 生成补丁 | `.gitmodules`、`git submodule status` |
| D-003 | 本轮不 push 任何远端 | 开源与否未确认；任务明确要求只保留本地提交 | `git remote -v` 为空 |

## 运行时路线

| 编号 | 决策 | 理由 | 影响面 |
|---|---|---|---|
| D-010 | ~~Node 运行时优先 `nodejs-mobile` 预编译包（v18.20.4）~~ **已修订**：nodejs-mobile 路线对 dsh 不成立，改用 Termux 官方仓库的预编译 Node 26.13.0（x86_64/arm64 均有包），以 `lib*.so` 形式进 APK `lib/` 后 exec | dsh 使用 `node:sqlite`（`upstream/deepseek-harness/packages/storage/storage-sqlite/src/unit.ts:11`），Node 18 无该模块；nodejs-mobile 最高只到 18.20.4；Termux 包为 bionic 原生构建，实测在 API 36 上可运行 | 见 `docs/STATE.md` 实测证据；M1 需固化构建来源 |
| D-011 | 不修改 dsh 的 `engines.node` 声明，不降级 dsh 源码；若 Node 18 无法启动 dsh，则把"完整 dsh 内核"降级为 EXPERIMENTAL 并记录 | 禁止为跑通重写内核；`engines` 是上游契约 | M1/M2 范围与验收口径 |
| D-012 | ~~Python 运行时优先 Chaquopy~~ **已修订**：采用 Termux 预编译 CPython 3.14.10（bionic），以 `libpython3.14.so` + 可执行 stub 形式进 APK `lib/`，`PYTHONHOME` 指向 `filesDir` | Chaquopy 最新只支持到 Python 3.13，而 Octop 要求 `>=3.12`（可用）但浏览器/桌面 extras 仍不可用；Termux CPython 3.14 实测可运行，且不受 Gradle 插件与 Chaquopy 私有仓库约束 | 见 `docs/STATE.md` 实测证据；pip 安装原生轮子仍需另验 |
| D-013 | M0 阶段运行时验证允许把二进制放到 `/data/local/tmp`（非 app data）执行，仅用于证明"能跑"；正式形态必须是 APK `lib/` 目录内的 `lib*.so` 并由 `Runtime.exec` 启动 | 硬性约束"不得在 app data 目录 exec 二进制"；`/data/local/tmp` 是系统临时区，不属于 app data | 验证证据的可迁移性 |
| D-014 | 两个内核共用一个 App、一个入口；`gateway` 只做本机回环反代与进程编排，不改写业务 | 硬性约束 | 模块划分 |

## 打包形态（M1）

| 编号 | 决策 | 理由 | 影响面 |
|---|---|---|---|
| D-030 | 运行时二进制（Node/CPython/依赖库/标准库）**不入 git**，由 `scripts/build-runtime.ps1` 从 Termux 包解包树生成到 `jniLibs/` 与 `assets/`（均已 gitignore） | 100MB+ 二进制进 git 会让仓库不可用；脚本可复现 | 换机需先跑脚本；APK 60.9MB |
| D-031 | 版本化 soname 的库改名进 `jniLibs`（`libcrypto.so.3`→`libxcrypto.so`），Python C 扩展需要的同名库改为 assets 解压到 `filesDir/runtime/lib` 并由 `sitecustomize.py` 绝对路径预加载 | Android 只解压 `lib*.so`；app namespace 不搜 `LD_LIBRARY_PATH`（实测） | 兼容层成为运行时前提，见 STATE「关键发现」 |
| D-032 | `packaging.jniLibs.useLegacyPackaging = true` + `android:extractNativeLibs="true"` | 否则库留在 APK 内不可 exec；实测解压后 `-rwxr-xr-x` | APK 体积换可执行性 |
| D-033 | 编译期不裁剪 ICU（`libxicudata.so` 31.6MB 全留） | Node 的 full-icu 构建需要；裁剪会导致时区/国际化异常 | APK 体积主要来源，后续可评估 `--with-intl=small-icu` 自建 Node |
| D-034 | 自检用 `--ez runtime_selftest true` 启动附加参数触发，不引入常驻调试开关 | 便于自动化取证，同时不改变正常启动行为 | 仅调试用途 |

## 产品与命名

| 编号 | 决策 | 理由 |
|---|---|---|
| D-020 | App 名 `Runtime Console`（暂定，中性），包名 `dev.runtimeconsole.app`，模块 `core` / `runtime-host` / `gateway` / `bridges` / `ui` | 任务要求中性；禁止上游品牌字样 |
| D-021 | M0 只建空壳（Kotlin + Compose），不做 UI | 任务要求；M4 之前不投入外观 |
| D-022 | 网络一律 bind `127.0.0.1`，不提供局域网访问开关 | 硬性约束 |

## 内核获取方式（M2）

| 编号 | 决策 | 理由 | 证据 / 影响面 |
|---|---|---|---|
| D-040 | dsh 的产物用 **npm 已发布包** `@deepseek-ai/dsh@0.2.2-rc.1`，不在 PC 上跑 `pnpm build` | 上游 `apps/cli/package.json` 的 name/version 与固定 SHA 完全一致，npm 上同版本可查；`pnpm build` 需要 pnpm 11.28.5 + 全 workspace 依赖（含 Electron/Playwright），成本远高于取官方产物，且不改一行业务代码 | `npm view @deepseek-ai/dsh version` → `0.2.2-rc.1`；`docs/STATE.md` M2-dsh |
| D-041 | 允许对 dsh 的**依赖**打补丁（不碰 dsh 自身代码）：`node-addon-require-builtin` 降级为 `--expose-internals` | 该原生包无 android 目标，但它是启动必经之路；补丁等价替换其唯一职责 | `patches/dsh/…patch`、`docs/UPSTREAM.md` P-01 |
| D-042 | dsh 启动命令固定带 `--expose-internals`，且必须注入 `TMPDIR` 指向 `filesDir/tmp` 并提前创建目录 | 前者是 P-01 的前提；后者修复 Termux Node 的 `os.tmpdir()` 默认指向 `/data/data/com.termux/files/usr/tmp` 导致 `spill-local` 插件失败 | 设备日志：`mkdtemp '/data/data/com.termux/files/usr/tmp/dsh-spill-XXXXXX'` → 注入后 0 告警 |
| D-043 | pydantic-core 用 Rust + NDK 现场交叉编译（`x86_64-linux-android`），不用 Termux 包 | Termux 仓库无 `python-pydantic`；PyPI 无 android 轮子；pydantic 2.x 是 Octop 硬依赖 | 产物 `lib_pydantic_core.so` 6,093,592 bytes，3m01s |

## 构建链（arm64 接入）

| 编号 | 决策 | 理由 | 证据 / 影响面 |
|---|---|---|---|
| D-044 | ABI 切换用 Gradle 属性**整套替换** `src/main/assets` 与 `src/main/jniLibs`（`-PruntimeAssetsDir` / `-PruntimeJniLibsDir` / `-PruntimeAbi`），不做 flavor/splits | stdlib 里 `lib-dynload` 下是架构相关的动态库，x86_64 与 arm64 混装会得到"能装但内核起不来"的包；属性替换比 flavor 少一层变体矩阵，默认构建完全不变 | `runtime-host/build.gradle.kts`、`app/build.gradle.kts`；两向构建均 `BUILD SUCCESSFUL`，默认包仍为 `lib/x86_64` |
| D-045 | `scripts/build-runtime-arm64.ps1` 末尾把与架构无关的 `launcher` / `selftest` 静态 assets 拷进 arm64 assets 目录 | Gradle 的 assets 替换是整目录覆盖，不补这步 arm64 包里没有内核启动脚本与自检脚本 | 修复前后解包对比：修复前 `assets/` 只有 `runtime-libs` 与 `python-stdlib.zip`；修复后 `assets/launcher/octop_serve.py`、`assets/selftest/hello.js|py` 齐全 |

## 环境事实（非决策，但影响后续）

| 编号 | 事实 | 证据 |
|---|---|---|
| E-01 | 本机 `github.com:443` 间歇不可达，`api.github.com` / `codeload.github.com` / `ghfast.top` 可达 | `Test-NetConnection` 结果：github.com=False，其余=True |
| E-02 | 设备为 x86_64 模拟器，API 36 | `adb shell getprop ro.build.version.sdk` → 36；`ro.product.cpu.abi` → x86_64 |
| E-03 | Android SDK：platforms 33/34/35/36，NDK 26.1/27.0/28.2，CMake 3.22.1，build-tools 30.0.3–36.1.0 | `Get-ChildItem $env:LOCALAPPDATA\Android\Sdk` |
| E-04 | 本机 Gradle 8.14.3 / 9.x 已缓存，JDK 21 可用 | `~/.gradle/wrapper/dists`、`java -version` |

## 默认决策表（缺失需求时的兜底）

| 场景 | 默认取值 |
|---|---|
| 上游依赖在 Android 无轮子/无预编译 | 先尝试源码编译；超 25 分钟即裁剪该功能并记入 CUTS.md |
| 同一能力有多种实现 | 取依赖最少的实现；不引入 Electron/Chromium |
| 端口冲突 | 用 127.0.0.1 上随机高位端口，写入状态文件 |
| 数据目录 | `context.filesDir` 下 `home/`，通过 `HOME`/`DSH_HOME`/`OCTOP_HOME` 注入 |
| 日志 | `context.filesDir/logs/`，按进程分离 |
| 进程守护 | 单 `RuntimeHost` 持有两个子进程，崩溃指数退避重启（仅前台） |
| 无法验证的能力 | 标 EXPERIMENTAL，写进 README 已知限制，不写"已完成" |
