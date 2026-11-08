# Runtime Console

在 Android 应用进程内运行本地 Node.js 与 CPython 运行时，承载两个自带内核（一个 Web 控制台、一个管理接口 API），
并提供统一的状态、起停、日志与应用内 WebView 控制台。

- 所有监听地址仅 `127.0.0.1`，不提供局域网访问。
- 内核二进制以 `lib*.so` 形式随 APK 分发，`exec` 自应用自己的 `nativeLibraryDir`。
- 大型运行时数据（Node 依赖树、Python site-packages）**不随 APK 分发**，通过「运行时包」在首装时导入，见下文。

构建方式、模块划分与设备取证过程见 [docs/STATE.md](docs/STATE.md)；
上游改动与补丁见 [docs/UPSTREAM.md](docs/UPSTREAM.md) 与 `patches/`。

## 能力状态（三档标注）

标注含义：**已验证**＝有设备实测证据；**EXPERIMENTAL**＝代码已实现、只在特定条件下跑通或未覆盖异常路径；
**计划中**＝尚未实现。

### 已验证

| 能力 | 证据 |
|---|---|
| 从 APK `lib/` 启动 Node 与 CPython，HTTP 自请求 200 | `docs/STATE.md` M1 |
| 两个内核在设备上返回 HTTP 200（仅 `127.0.0.1`） | M2 |
| App 内起停、健康探活、RSS / PID / 日志尾、dsh token 解析 | M3–M4，`docs/evidence/m4-console-2of2.png` |
| 应用内 WebView 加载控制台（不跳外部浏览器） | M5，`docs/evidence/m5-dsh-console-webview.png` |
| 前台服务保活：退桌面 100 s 后内核仍在监听 | M6 |
| 管理接口首启向导 → 登录 → 主界面完整渲染 | `docs/evidence/t11-main-ui-3.png` |
| 运行时包格式：manifest + 分卷 zip + SHA-256 + 原子替换 + 防 zip-slip | `runtime-host` `RuntimePack.kt` |
| WebView 加载进度、主框架失败 / 5xx 错误页与重试 | `KernelWebViewActivity.kt` |

已验证范围仅限 **x86_64 模拟器（Android 16 / API 36）**。arm64 真机未验证。

### EXPERIMENTAL

| 能力 | 限制 |
|---|---|
| arm64 运行时产物 | 链路可出产物（14 个 `.so`、99.8 MB、ELF AArch64），但**未接入 APK**（`assets` 里的 `lib-dynload` 与架构相关，需按 ABI 拆包），**未真机验证** |
| 运行时包导入（SAF 选目录） | 格式与校验已完成；完整 dsh 树导入需约 40 分钟，导入后能否启动仍以实测为准 |
| 运行时分包 HTTP 下载与 Range 断点续传 | 无公共分发服务器，需自建静态文件服务并保证支持 `Range`；服务端忽略 `Range` 时退化为整包重下 |
| 首装导入吞吐 | 实测约 0.4–0.5 MB/s（约 20 文件/秒），完整 dsh 树约 40 分钟；瓶颈在海量小文件创建 |
| 运行时包增量更新 / 回滚 | 仅整包替换，旧目录在成功后删除 |
| 管理接口 dashboard 冷启动 | 首次 `GET /` 可能 20 s 不返回（热态 1.4 s / 200） |
| 模拟器上的启动时延 | 冷启动 95–110 s，Compose 首帧 30–45 s；不作为性能结论 |

### 计划中

| 能力 | 说明 |
|---|---|
| arm64 / 真机验证 | 产物已能生成，但未接入 APK、未上真机，T-04 未验证 |
| 一键全自动首装 | 当前必须由用户把运行时包放进存储后手动选择目录 |
| 按需单内核启动 + 空闲回收 | 两个内核同时在线的内存峰值尚未做长稳测量 |
| CI 门禁 | 目前以本地构建为准 |
| 应用内更新内核版本 | 需与运行时包版本号联动 |

## 首装（全新设备）

Node 与 Python 运行时数据约 1.5 GB，不随 APK 分发。完整步骤、包格式与实测耗时见
[docs/DISTRIBUTION.md](docs/DISTRIBUTION.md)：

1. 安装 APK；
2. 在 PC 侧用 `scripts/pack-runtime.ps1` 生成 `manifest.json` + 分卷 zip；
3. 把包放进设备存储的任意目录；
4. 打开 App →「运行时组件」→「导入运行时包」→ 选择该目录；
5. 导入完成后内核自动/手动启动，控制台可打开。

## 构建

```powershell
# 路径含 & 时 Android Gradle 插件不可直接用，先建目录联接
cmd /c mklink /J C:\rc "E:\happy_dsh&octopinphone"
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot"
C:\rc\gradlew.bat -p C:\rc :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 60 MB，含两个运行时二进制与标准库）。

arm64 运行时产物（尚未接入 APK，见上表 EXPERIMENTAL）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-runtime-arm64.ps1
# 输出：.tmp\arm64-jniLibs\arm64-v8a\（14 个 .so / 99.8 MB，ELF AArch64）
#       .tmp\arm64-assets\（python-stdlib.zip、runtime-libs）
```

## 许可

本仓库代码以 MIT 发布，见 [LICENSE](LICENSE)。随附的运行时、原生库、Python 与 Node 依赖
的许可证清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，App 内「开源许可」页可离线查看并复制全文。
