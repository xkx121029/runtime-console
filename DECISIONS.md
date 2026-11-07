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
| D-010 | Node 运行时优先 `nodejs-mobile` 预编译包（v18.20.4），其后再评估官方 Node 交叉编译 | 需求指定优先级；nodejs-mobile 发布页存在 Android 预编译产物（54.6MB zip），可当日验证 | 若 dsh 实际依赖 Node ≥24 API，则此路线不成立，见 D-011 |
| D-011 | 不修改 dsh 的 `engines.node` 声明，不降级 dsh 源码；若 Node 18 无法启动 dsh，则把"完整 dsh 内核"降级为 EXPERIMENTAL 并记录 | 禁止为跑通重写内核；`engines` 是上游契约 | M1/M2 范围与验收口径 |
| D-012 | Python 运行时优先 Chaquopy；若 Chaquopy 无 Python 3.12 或关键二进制轮子缺失，则降级为"交叉编译 CPython + pip 离线轮子"或只跑纯 Python 子集 | 需求指定优先级；Octop 要求 `>=3.12` | 决定 Octop 可用面 |
| D-013 | M0 阶段运行时验证允许把二进制放到 `/data/local/tmp`（非 app data）执行，仅用于证明"能跑"；正式形态必须是 APK `lib/` 目录内的 `lib*.so` 并由 `Runtime.exec` 启动 | 硬性约束"不得在 app data 目录 exec 二进制"；`/data/local/tmp` 是系统临时区，不属于 app data | 验证证据的可迁移性 |
| D-014 | 两个内核共用一个 App、一个入口；`gateway` 只做本机回环反代与进程编排，不改写业务 | 硬性约束 | 模块划分 |

## 产品与命名

| 编号 | 决策 | 理由 |
|---|---|---|
| D-020 | App 名 `Runtime Console`（暂定，中性），包名 `dev.runtimeconsole.app`，模块 `core` / `runtime-host` / `gateway` / `bridges` / `ui` | 任务要求中性；禁止上游品牌字样 |
| D-021 | M0 只建空壳（Kotlin + Compose），不做 UI | 任务要求；M4 之前不投入外观 |
| D-022 | 网络一律 bind `127.0.0.1`，不提供局域网访问开关 | 硬性约束 |

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
