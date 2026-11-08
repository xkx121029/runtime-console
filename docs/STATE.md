# 项目状态（STATE）

最后更新：2026-11-08 13:30（Asia/Shanghai）

## 当前上游 SHA

| 仓库 | 路径 | SHA | 版本 / 日期 |
|---|---|---|---|
| deepseek-harness | `upstream/deepseek-harness` | `fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159` | 0.2.2-rc.1 / 2026-11-04 |
| Octop | `upstream/octop` | `4022e24ef6c34e9716350872b0e93bfaae7a20c3` | 1.0.3b4 / 2026-11-06 |

## 里程碑进度

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 审计 + 最小可运行验证 | **已完成** | 两个运行时在模拟器上跑通 |
| M1 运行时打包 | **已完成** | Node / CPython 从 APK `lib/` 启动，HTTP 自请求 200 |
| M2 内核启动 | **已完成** | dsh `web` 与 Octop FastAPI 均在设备上返回 HTTP 200 |
| M3 内核管理 | **已完成** | KernelSupervisor / LoopbackGateway / SystemBridges，App 内起停 + 健康探活 |
| M4 控制台 UI | **已完成（基础版）** | Compose 单屏：状态/内存/PID/日志/起停/打开（打开带 token） |
| M5 应用内控制台 | **通过（dsh）/ 部分通过（Octop）** | WebView 加载 dsh 控制台成功；Octop 首启向导后空白（T-11） |
| M6 进程保活 | **通过** | 前台服务 `isForeground=true`；退桌面 100 s 后内核仍监听、Octop 仍 200 |

## M1 结论：**通过（x86_64 模拟器）**

### 做了什么

1. `scripts/build-runtime.ps1`：把 Termux 预编译运行时整理成 Android 可加载形态。
   - 可执行文件/共享库统一改名 `lib*.so` 进 `runtime-host/src/main/jniLibs/<abi>/`；
   - 版本化 soname（`libcrypto.so.3` 等）按**同长度原地改写**为不冲突名字（`libxcrypto.so`），
     避免落到系统 `/system/lib64` 的同名库；
   - Python 标准库（665 文件 / 15.7MB）+ 兼容库（13 文件 / 8.3MB）作为 assets 打包。
2. `RuntimeHost`：从 `applicationInfo.nativeLibraryDir` 执行二进制，注入
   `HOME` / `TMPDIR` / `LD_LIBRARY_PATH` / `PYTHONHOME` / `RUNTIME_COMPAT_LIB_DIR`，日志落 `filesDir/logs/`。
3. APK 打包 `useLegacyPackaging = true` + `extractNativeLibs="true"`，安装后 `lib/x86_64/` 全部 `-rwxr-xr-x`。

### 实测证据（`emulator-5560`，x86_64，API 36）

`lib/x86_64/` 实际解压（`run-as ls -la`）：

```
-rwxr-xr-x system system 50715096 libnode_exec.so
-rwxr-xr-x system system  5822696 libpython3.14.so
-rwxr-xr-x system system     4536 libpython_exec.so
-rwxr-xr-x system system 33108832 libxicudata.so
-rwxr-xr-x system system  5538256 libxcrypto.so
...（共 15 个）
```

自检入口：`adb shell am start -n dev.runtimeconsole.app/.MainActivity --ez runtime_selftest true`

Node（`files/logs/node.log`）：

```
NODE_VERSION=v26.13.0
NODE_ARCH=x64
NODE_PLATFORM=android
NODE_HOME=/data/user/0/dev.runtimeconsole.app/files/home
NODE_EXEC=/data/app/~~Lhs7UT9tOeeiH446e-QK9g==/dev.runtimeconsole.app-9i7j_9X0loOCTW9OIU0QAw==/lib/x86_64/libnode_exec.so
FILE_WRITE_READ=hello-from-node
FILE_SIZE=16
HTTP_LISTEN=127.0.0.1:41199
HTTP_SELF_FETCH=200:ok-from-node
```

Python（`files/logs/python.log`）：

```
PYTHON_VERSION=3.14.10 (main, Nov  5 2026, 05:25:26) [Clang 21.0.0 ...
PYTHON_PREFIX=/data/user/0/dev.runtimeconsole.app/files/runtime/python
FILE_WRITE_READ=hello-from-python
FILE_SIZE=18
C_EXT_sqlite3=ok
C_EXT_ssl=ok
C_EXT_zlib=ok
C_EXT_hashlib=ok
C_EXT__json=ok
C_EXT_select=ok
C_EXT_ctypes=ok
HTTP_LISTEN=127.0.0.1:46241
HTTP_SELF_FETCH=200:ok-from-python
```

### 体积

| 项 | 实测 |
|---|---|
| APK（debug，含两个运行时 + stdlib） | 60.9 MB |
| 安装后 `lib/x86_64/` | 99.5 MB |
| 解压出的兼容库 `files/runtime/lib` | 8.3 MB |

## 关键发现（M1）

1. **bionic 在 app 进程里忽略 `LD_LIBRARY_PATH`**（classloader namespace 只搜 `nativeLibraryDir`）。
   → Python C 扩展的版本化 `DT_NEEDED`（`libz.so.1`）先以绝对路径 `ctypes.CDLL` 预加载进进程，
   再由 soname 命中缓存；实现见生成的 `sitecustomize.py`（`scripts/build-runtime.ps1` 内）。
2. **bionic 允许从 app 数据目录 `dlopen` 库，只是不允许 `exec` 二进制**：
   `lib-dynload/*.so` 在 `filesDir` 下可正常加载（本次 7 个 C 扩展全部 ok）。
3. 残留 `files/runtime/lib/libsqlite3.so`（旧版本安装留下的）会产生一条无害的 preload 警告；
   该库在 `nativeLibraryDir` 有同名副本，`sqlite3` 导入正常。

## M2-dsh 结论：**通过（x86_64 模拟器，adb exec 路径）**

### 路线（与需求默认表的差异）

任务书默认路线是 `pnpm install && pnpm build` 后把产物推设备。实测后改成**发布包路线**：

- 上游 `apps/cli/package.json` 的 `name` 是 `@deepseek-ai/dsh`、`version` 是 `0.2.2-rc.1`，
  与固定 SHA 完全同版本，且已发布到 npm（`npm view @deepseek-ai/dsh version` → `0.2.2-rc.1`）。
- 因此 `npm install @deepseek-ai/dsh@0.2.2-rc.1 --omit=dev --ignore-scripts --os=linux --cpu=x64`
  直接拿到**同一份源码的官方产物**，不需要本地编译（653 个包 / 701.7 MB，15 分钟）。
- 这不算"重写内核"，只是换了取产物的方式；证据：版本号一致、包内 `lib/*.js` 是 tsdown 构建产物。

### 唯一必需补丁：P-01

`node-addon-require-builtin` 没有 android 预编译绑定，`dsh-app-boot` 在启动时**必需**它。
降级为 `--expose-internals` + 普通 `require()`，见 `patches/dsh/node-addon-require-builtin+0.1.9.patch`
与 `docs/UPSTREAM.md` P-01。

### 实测证据

PC 侧（Windows，Node v24.11.1）：`npm install` 653 包；`dsh web --no-open --port 3099` 打印带 token 的 URL，
`GET /` → 401（无 token）、`GET /?token=…` → **200 / 40302 bytes**。

设备侧（`emulator-5560`，x86_64，API 36，Termux Node v26.13.0）：

```
# 版本
$ adb shell run-as … libnode_exec.so --expose-internals …/dsh/lib/bin.js --version
0.2.2-rc.1

# 启动
$ adb shell run-as … libnode_exec.so --expose-internals …/dsh/lib/bin.js web --no-open \
    --host 127.0.0.1 --port 3080
dsh web: http://127.0.0.1:3080/?token=<redacted>

# 经 adb forward tcp:13080 → tcp:3080
GET /                       → 401
GET /?token=<redacted>      → 200, len=40102
```

体积与耗时：

| 项 | 实测 |
|---|---|
| node_modules 文件数 / 大小 | 69,498 / 701.7 MB |
| tar.gz | 157 MB（788 s，Windows 单线程 gzip） |
| `adb push` | 24 s（6.6 MB/s） |
| 设备端解压 | 113 s |
| `dsh web` 冷启动到打印 URL | 约 25~40 s（模拟器） |

### App 内取证（同一进程树，非 adb shell）

```
$ adb shell am start -n dev.runtimeconsole.app/.MainActivity --ez runtime_dsh true
$ adb shell run-as … ps -A -o PID,ARGS | grep libnode_exec
32026 libnode_exec.so --expose-internals /data/user/0/dev.runtimeconsole.app/files/home/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js web --no-open --host 127.0.0.1 --port 3080
$ adb forward tcp:13080 tcp:3080 && curl http://127.0.0.1:13080/
HTTP 401（鉴权生效，服务在线）
```

技术债：dsh 的访问 URL（含 token）在 App 内**没有**出现在 `files/logs/dsh-web.log` 里（同一命令在 adb shell 下重定向到文件时能打印）。
原因未定位，暂以 401 作为"服务在线"的证据；待 M3 用端口探测 + 反向代理解决。

### 已知告警（功能不受影响）

- 首次启动时 `spill-local` 插件因 `os.tmpdir()` 指向 Termux 私有目录而失败。注入 `TMPDIR=filesDir/tmp`
  并确保目录存在后，**0 个插件告警**。这也是 `RuntimeHost.exec()` 里已经注入 `TMPDIR` 的原因。

## M2-octop 结论：**通过（x86_64 模拟器，adb exec 路径；尚未进 App 进程）**

### 依赖落地方式

Python 侧用 Termux CPython 3.14.10 + `files/python-site`（`PYTHONPATH`）安装 Octop 1.0.3b4
全部依赖，`du -sh` 实测 **803 MB / 319 个顶层条目**（langchain 生态，体积正常）。

| 类别 | 处理方式 | 证据 |
|---|---|---|
| 纯 Python 依赖（40+ 个） | `pip download --platform any --only-binary=:all: --python-version 3.14` 取 `py3-none-any` 轮子后解压进 `python-site` | 两批共 53 个包解压成功 |
| pydantic-core 2.46.4 | Rust + NDK 交叉编译（前一轮产物） | 导入 pydantic 成功 |
| cffi 2.0.0 | NDK clang 编 `_cffi_backend`，**必须 `-lpython3.14` 链接**，否则 bionic 报 `cannot locate symbol PyExc_ImportError` | `import cryptography.exceptions` → OK |
| argon2-cffi-bindings 25.1.0 | 先用宿主机 cffi `ffi.emit_c_code()` 生成 `_ffi.c`，再用 NDK clang 编（自带 argon2 源码） | `import argon2` → OK |
| regex 2026.1.15 | NDK clang 编 `_regex`（PyPI 的 manylinux 版在 bionic 下崩溃） | `import regex` → OK |
| cryptography 50.0.2 | **换 Termux 版**（Termux 的 cryptography 仍是 cffi 实现，正好对上上面编的 `_cffi_backend`） | 见 DECISIONS D-014 |
| lxml / Pillow / rpds-py / pycryptodomex | 换 Termux 版 + 补系统库 | `lxml.etree`、`PIL.Image`、`pptx` 均 OK |
| mcp | **锁 1.30.0**；2.3.0 与本版 Octop 不兼容（`McpError` 被改名） | 见 DECISIONS D-015 |

新增系统库（进 `runtime-host/src/main/assets/runtime-libs/`，全部来自 Termux 仓库同一 x86_64 构建）：
`libiconv` `libxml2.so.16.1.4` `libxslt` `libexslt` `libtiff` `libjpeg.so.8.3.2` `libopenjp2`
`libimagequant` `libxcb` `libXau` `libXdmcp` `libwebp` `libwebpdemux` `libwebpmux` `libsharpyuv`。

`sitecustomize.py` 的预加载改为**两轮**：`libexslt` 按字母序早于 `libxml2`，首轮必然失败，
第二轮在 `libxml2.so.16` 注册后才能成功（改动在 `scripts/build-runtime.ps1`）。

### 实测证据

```
IMPORT_OK 24.91           # import octop.infra.server（冷）
SERVER_START_OK 5.71      # OctopServer.start()：SQLite 迁移、仓库、插件、harness
APP_OK 39.8 routes 69     # build_app(server)
TOTAL 70.43               # 总计（模拟器，含解释器启动）
```

HTTP（`adb forward tcp:18080 tcp:18080`，仅监听 `127.0.0.1`）：

```
GET /api/setup/status  → 200（JSON，初始化向导未完成状态）首次 1.23s
GET /api/openapi.json  → 503（初始化向导未完成时的预期锁定）
GET /                 → 20s 超时（dashboard 静态托管待查，见 T-08）
netstat -ltn          → 仅 127.0.0.1:18080
```

进程内存：`VmRSS = 255 MB`（`/proc/<pid>/status`）。

### 尚未跑通的依赖（未导入即失败，需在用到时处理）

| 包 | 现象 | 影响 |
|---|---|---|
| `tiktoken` | 无模块 → `import langchain_openai` 直接失败 | OpenAI 兼容 provider 不可用（其他 provider 未验） |
| `sqlite-vec` | 无模块 | 向量检索退化为关键词检索 |
| `zstandard` | `backend_c` 无法加载 | 压缩/归档相关路径降级 |
| `Crypto`（pycryptodome） | 只有 `Cryptodome`（pycryptodomex） | 飞书加解密等需 `Crypto` 的路径不可用 |

另：启动日志有一条 `OpenSSL 3's legacy provider failed to load`（不影响 TLS/主流算法）。

## M3 + M4 结论：**通过（App 进程内，x86_64 模拟器）**

### 结构

| 模块 | 职责 |
|---|---|
| `core` | `KernelId`（端口/健康路径/日志名）、`KernelEndpointStatus`、`HealthProbe`、dsh token 解析 |
| `runtime-host` | `RuntimeHost`（二进制/环境/资产）、`KernelSupervisor`（长驻进程：起停/PID/RSS/日志尾/崩溃摘要） |
| `gateway` | `LoopbackGateway`：把两个内核收敛为 `snapshot()` / `start` / `stop`，**不做反向代理** |
| `bridges` | `SystemBridges` + `AndroidSystemBridges`：用 `ACTION_VIEW` 打开回环 URL |
| `ui` | Compose 控制台：状态卡、指标（RSS/运行时长/HTTP/PID）、起停/打开/日志抽屉、自检 |
| `app` | 唯一入口：装配上述模块、自动拉起、`onNewIntent` 取证入口 |

### 实测证据（2026-11-08 23:09~23:15，同一 App 进程）

自动拉起（`am start -n dev.runtimeconsole.app/.MainActivity`，无 extras）：

```
ps: 27130 libnode_exec.so   /   27133 libpython_exec.so
netstat -ltn: 127.0.0.1:3080, 127.0.0.1:18080
```

| 内核 | 单内核冷启动 → 监听 | 并发自动拉起 → 监听 | 实测 HTTP | 常驻内存 |
|---|---|---|---|---|
| dsh web | ≈110 s | ≈4 min 27 s（与 Octop 同时起，模拟器争 CPU） | `GET /` → 401；`GET /?token=…` → **200，len=40102** | 261 MB |
| Octop API | ≈97 s（`OCTOP_IMPORT_MS=43492`、`SERVER=60499`、`APP=109897 routes=69`） | ≈110 s（`APP_MS`） | `GET /api/setup/status` → **200，len=230** | 243 MB |

- 上表"内存"为 UI 读到的 `/proc/<pid>/status` VmRSS；Octop 单跑时实测 249 MB。
- 截图证据：[evidence/m4-console-2of2.png](evidence/m4-console-2of2.png)（2/2 在线，dsh HTTP 401、Octop HTTP 200）。
- APK：`app-debug.apk` 63,754,897 bytes（x86_64 debug，含两个运行时二进制与 stdlib）。

- dsh 的 token 已从 `files/logs/dsh-web.log`（82 字节：`dsh web: http://127.0.0.1:3080/?token=…`）解析出来，
  UI 的“打开”会带上 token，不再落到 401 页面。
- Octop 首启向导密码打印在日志里（一次性），同时落 `~/octop-home/octop-login.txt`。

### 本轮修掉的三个真问题

1. **`am start` 不重启 Activity**：Activity 已在前台时，intent 只走 `onNewIntent`，旧代码没实现 →
   看起来“启动没生效”。已在 `MainActivity.onNewIntent` 里复用 `handleIntent()`。
2. **targetSdk 36 默认禁止明文 HTTP**：`HttpURLConnection` 探活 `http://127.0.0.1:…` 直接失败，
   UI 永远显示“启动中/HTTP —”。已加 `res/xml/network_security_config.xml`，只放行 `127.0.0.1` 与 `localhost`。

3. `sitecustomize.py` 兼容库预加载改为**按依赖层级排序**（`libiconv → libxml2 → libxslt → libexslt`），
   修复 App 进程内 `compat-preload-warn: libexslt.so … libxml2.so.16 not found`。
   证据：新日志 `files/logs/octop-api.log` 无该告警，旧日志在 `octop-api.log.1`（1110 B）。

### 使用方式

- 安装后直接打开即自动拉起两个内核（开关可关，偏好存 `runtime-console/auto-run-kernels`）。
- 单内核取证：`am start -n dev.runtimeconsole.app/.MainActivity --ez runtime_dsh true`
  （或 `runtime_octop`）；Activity 在前台时同样生效。
- 「打开」在应用内 WebView 加载内核控制台（dsh 自动带 token）；「日志」抽屉读 `files/logs/*.log` 尾部 200 行。

## M5 结论：**通过（应用内 WebView，单 App 入口）**

「打开」不再跳外部浏览器：`AndroidSystemBridges` 用 `ComponentName` 拉起应用内 `KernelWebViewActivity`
（`exported=false`，不占桌面入口），由它加载带 token 的回环地址。

| 页面 | 结果 | 证据 |
|---|---|---|
| dsh 控制台 | **通过**：Preview Notice 对话框完整渲染，token 鉴权生效 | [evidence/m5-dsh-console-webview.png](evidence/m5-dsh-console-webview.png) |
| Octop 面板 | **部分通过**：首启向导完整渲染（Password/Database/Admin）；提交引导密码后页面变空白，未定位 | [evidence/m5-octop-setup-webview.png](evidence/m5-octop-setup-webview.png) |

- WebView 页面冷加载（内核同时启动、模拟器争 CPU）：Activity 首帧 +24.8 s，dsh 控制台约 2 分钟后出内容。
- `GET /`（Octop dashboard）本次实测 **200，len=10310，1.4 s**（热态）；T-08 的 20 s 超时只在冷启动阶段出现。
- 外链（非 127.0.0.1/localhost）交给系统浏览器，WebView 不变通用浏览器。

## M6 结论：**通过（前台保活）**

- 新增 `KernelService`（`foregroundServiceType=specialUse`，`exported=false`），不承载业务，
  只把进程提到前台优先级；有内核在跑时自动拉起，全部停止后自动关掉。
- 清单新增 `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` / `POST_NOTIFICATIONS`。
- 实测：App 启动约 60 s 后 `dumpsys activity services` 出现 `isForeground=true foregroundId=1001`；
  按 Home 退到桌面再等 100 s，`libnode_exec.so` / `libpython_exec.so` 仍在，Octop 端口保持
  LISTEN 且 `GET /api/setup/status` 仍 200。
- 价值：修复了 T-11 的真实成因 —— 内核是 App 子进程，进程被回收后 WebView 只剩空白页。

## 下一步（按优先级）

1. **首装体验**：`python-site`（803 MB）与 dsh `node_modules`（701 MB）目前靠脚本侧载，未进 APK（T-10）。
   需要定方案：首次启动下载（分区/断点续传）或随包分卷 —— 这是唯一阻碍“全新设备开箱可用”的项。
2. 复测 T-11：保活到位后重走 Octop 首启向导（输入 `octop-login.txt` 的密码），确认能进主界面。
3. 真机 arm64 验证（T-04）；当前全部证据为 x86_64 模拟器。
4. WebView 细节：下拉刷新、加载进度、错误页（内核未启动时给出“去启动”引导）。


## 已知坑

1. `github.com:443` 间歇不可达；`api.github.com`/`codeload.github.com`/`ghfast.top` 可达。
2. 任务书的 `TencentCloud/Octopus` 不存在，实际是 `TencentCloud/Octop`（D-001）。
3. dsh 用 `node:sqlite`（`upstream/deepseek-harness/packages/storage/storage-sqlite/src/unit.ts:11`），
   Node 18 不可用 → 已用 Termux Node 26（D-010 修订）。
4. 项目路径含 `&`：用目录联接 `C:\rc` 构建（`C:\rc\gradlew.bat -p C:\rc`）。
5. PowerShell 5.1 读无 BOM 的 UTF-8 脚本会把中文当 ANSI 并破坏语法：`scripts/*.ps1` 必须带 BOM。
   新增脚本后执行：
   `[IO.File]::WriteAllText($p, [IO.File]::ReadAllText($p,[Text.Encoding]::UTF8), (New-Object Text.UTF8Encoding($true)))`
6. Termux 包名含冒号（`openssl_1:3.6.5`）：`Test-Path`/`Split-Path` 会误判，直接落盘还会写成
   NTFS 备用数据流；`scripts/fetch-termux.ps1` 已统一改为本地文件名用 `_`、URL 用 `%3A`。
7. `adb push` 目录遇符号链接报错，改用 `tar` 打包传输。
8. 模拟器无 `curl`/`wget`，设备侧 HTTP 验证只能自写脚本自请求。
9. 模拟器极慢（冷启动 20s+），不可用于性能结论。
10. `assets` 里空目录会丢失，`AssetManager.list()` 对文件返回空数组，递归时按"空数组=文件"处理。
11. 每次改 assets 都要重新 `assembleDebug` 并**重装**（不是覆盖运行时目录），否则设备上仍是旧文件。
12. 本机 `pip` 走 Windows 系统代理，HTTPS 经代理会 `ProxyError`；运行前设 `NO_PROXY=*` 即可。
    `pip download --platform any --only-binary=:all: --python-version 3.14` 是"只取纯 Python 轮子"的可靠写法。
13. `adb shell "run-as pkg sh -c '…'"` 在 PowerShell 下会被提前展开/吃掉引号。
    可靠做法：本地写脚本 → base64 → 设备端 `base64 -d` 落盘再执行（注意用 LF，不能带 CRLF）。
14. 超过约 100KB 的二进制不能塞进命令行 base64；改 `adb push /data/local/tmp/x` +
    `adb shell "cat /data/local/tmp/x | run-as pkg sh -c 'cat > files/x'"`（App 自己读不到 `/data/local/tmp`）。
15. Termux 的 `cryptography` 仍是 **cffi 实现**（不是 Rust）；PyPI 的 manylinux 原生轮子（regex、zstandard）
    在 bionic 上不能直接用，要么自编要么换 Termux 版。
16. Activity 已在前台时 `am start` **不会重跑 onCreate**，intent 只走 `onNewIntent`；
    验证内核唤起前先 `am force-stop`，或确认代码里实现了 `onNewIntent`。
17. targetSdk ≥ 28 默认禁明文 HTTP（`CleartextNotPermitted`），回环 `http://127.0.0.1` 也逃不掉；
    必须用 `network_security_config.xml` 显式放行，否则探活永远返回 null。
18. 模拟器冷启动极慢：Compose 首帧约 30~45 s，内核冷启动 95~110 s；
    探活/UI 轮询不要以"几秒内必须响应"为前提。
19. Kotlin **支持嵌套块注释**：`build.gradle.kts` 的 KDoc 里写 `lib-dynload/*.so` 会打开一个嵌套
    注释，把后面的 `dependencies` 与任务注册全吃掉。症状极具误导性——`Unresolved reference: core`、
    `tasks.register` 的任务"不存在"、AGP 报 `does not specify compileSdk`。排查时直接看脚本尾部是否还有效。

## 技术债

| 编号 | 内容 | 影响 |
|---|---|---|
| T-01 | 上游补丁尚未产生 | M2 起每处改动出 `.patch` 并写 `docs/UPSTREAM.md` |
| T-02 | 运行时二进制不入库（60.9MB APK、99.5MB lib） | 换机/清缓存后需重跑 `scripts/build-runtime.ps1` |
| T-03 | 只能依赖 Termux 预编译包（非上游官方 Android 构建） | 需固化下载源与校验和 |
| T-04 | 真机 arm64 未验 | **arm64 APK 已能产出**：`scripts/build-runtime-arm64.ps1` 生成产物，Gradle 用 `-PruntimeAbi/-PruntimeAssetsDir/-PruntimeJniLibsDir` 整套替换；解包核对 `lib/arm64-v8a` 14 个 `.so`（99.8 MB，`e_machine=0xB7`）、`assets/runtime-libs` 28 个、`launcher/selftest` 齐全，APK 63.7 MB。无 arm64 真机，运行期未验证 |
| T-05 | `gradlew.bat` 在含 `&` 路径下不可用 | 用 `C:\rc` 联接绕过 |
| T-06 | 兼容库靠 `sitecustomize.py` 预加载 | 若某扩展用 `ctypes.CDLL("libssl.so.3")` 名字也能命中；但进程内多份同名库会双份内存 |
| T-07 | 首启解压 15.7MB stdlib 在主线程外的 IO 线程，但无进度条之外反馈 | 首次自检约 20~40s（模拟器） |
| T-08 | Octop `GET /` 冷启动时 20s 不返回（热态 1.4s/200） | dashboard 首次访问需预热，已降级为“等一会儿” |
| T-09 | Octop 依赖安装目前只有手工 adb 步骤 | 换机不可复现，待写 `scripts/octop-runtime.ps1` |
| T-10 | `python-site` 803MB 与 dsh `node_modules` 701MB 未进 APK | 运行时分包方案已落地并**端到端验证**：zip 路径 20.3 分钟；新增 tar 路径实测 350.7 MB / 16,434 条目 93.4 s（217 文件/s），导入后 dsh 正常监听 3080。详见 `docs/DISTRIBUTION.md` 第四轮 |
| T-11 | Octop 首启向导提交后 WebView 空白 | **已复测通过**：向导 → 登录 → 主界面完整渲染（`docs/evidence/t11-main-ui-3.png`）；空白成因是内核随进程被回收 + 首帧慢 |
| T-12 | 导入路径曾把目录树压平（zip 反斜杠分隔符） | 已修复（打包侧强制 `/`，解包侧归一化）；教训：导入成功不等于能用 |
| T-13 | 无公共分发服务器，HTTP 导入 EXPERIMENTAL | 只能本机静态服务或"PC 打包 → 手机拷贝" |

## 验证环境

| 项 | 值 |
|---|---|
| 设备 | `emulator-5560`（sdk_gphone64_x86_64，API 36，Android 16，x86_64） |
| adb | `c:\Users\linch\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| JDK | `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`（21.0.12.1） |
| Gradle / AGP / Kotlin | 8.14.2 / 8.13.2 / 2.2.21 |
| SDK | platforms 33–36，NDK 26.1/27.0/28.2，CMake 3.22.1 |

## 2026-11-08 第二轮：首装分发 / T-11 复测 / WebView 健壮性

### T-10 首装分发：方案已落地，全自动未达成

- 新增「运行时包（runtime pack）」：manifest.json（schema=1）+ 分卷 zip + 整体 SHA-256。
  安装器把内容解到 `<target>.staging` 再原子替换，校验失败时旧目录不动；拒绝 `..`/绝对路径。
  代码：`runtime-host/.../RuntimePack.kt`、`RuntimePackDownloader.kt`（HTTP Range 续传，EXPERIMENTAL）。
- App 内首装引导：「运行时组件」卡片显示两个组件缺失/就绪，点「导入运行时包」走 SAF 选目录，
  进度显示 `校验 x%` / `解压（N 项）x%`。取证入口：`--es runtime_pack_dir <路径>`、
  `--es runtime_pack_url <URL前缀>`。
- PC 侧 `scripts/pack-runtime.ps1`：dsh 树 7 万文件用 Fastest 压缩 → **234,550,743 B**，约 3 分钟。
  `adb push` 234 MB 实测 **53 s（4.1 MB/s）**。
- 设备端解包吞吐实测 **0.4–0.5 MB/s**（约 20 文件/秒），完整 dsh 树（约 1.1 GB）40 分钟量级。
  同设备顺序写 100 MB/s，瓶颈在"海量小文件逐条创建"，不在解压算法。
  已尝试的优化：复用 128KB 拷贝缓冲（避免每条目新分配，属于真实缺陷，已修）。
- 完整步骤、包格式、限制见 `docs/DISTRIBUTION.md`。
- **未完成**：一键全自动安装（无公共分发服务器）、增量/回滚、arm64 包。

### T-11 复测：通过

同一 App 进程、前台保活开启，路径实测：
Octop 向导（密码页）→ 提交错误密码时页面内联报错（不再空白）→
用 API 完成初始化（`/api/setup/database` → `/api/setup/initial-admin` → `/api/setup/finish`）→
重新打开 WebView → 登录页完整渲染 → 滑块验证 + 登录 → **主界面渲染（"还没有专家"空态）**。
证据：`docs/evidence/t11-after-setup.png`、`t11-slider.png`、`t11-main-ui-3.png`。

结论：此前的空白不是后端 500，也不是 WebView 配置问题，而是
①App 进程被回收导致内核消失（M6 已修）；
②冷启动首帧 + `/api/auth/status` 往返在模拟器上要几十秒，期间页面就是空白。
本次补的顶部进度条让"在加载"可见。

### WebView 健壮性

`KernelWebViewActivity` 现在有：顶部加载进度、主框架失败/5xx 错误页（说明内核可能未就绪 + 重试）、
console 转 logcat（`WebViewConsole`）、返回键先退网页历史再关页面。

### 下一步（接手点）

1. 等导入跑完后，用导入出的树重启 dsh，确认导入产物可执行（当前设备上 dsh 树已被移走做验证）。
2. 长稳/内存：两个内核 + WebView 同时在线的峰值与 LMK 记录。
3. arm64 产物链路。
4. 合规发布面（LICENSE / THIRD_PARTY_NOTICES / 许可页 / 去品牌化 / README 三档标注）。

以上 4 项已在第三轮完成，见下。

## 2026-11-08 第三轮：T-10 端到端 / 长稳内存 / arm64 / 合规 / CI

### 1. T-10：导入的树确实能跑起来（从"方案落地"到"端到端可用"）

- 真实包：`dsh.zip` 234,550,743 B，69,498 个文件。
- 第一次导入 48.0 分钟（2,879,193 ms），"成功"但树不可用：
  PC 侧用 .NET Framework 的 `ZipFile.CreateFromDirectory` 打包，条目名是
  `node_modules\.bin\acorn` 这种反斜杠形式；Android 侧按原样落盘，Linux 把 `\` 当普通字符，
  **69k 个文件全部平铺在根目录**，dsh 报"未找到 dsh 安装"。
- 修复两处：`scripts/pack-runtime.ps1` 手工建条目强制 `/`；`RuntimePack.kt` 解包时归一化
  `\` → `/`。用**同一份旧包**重新导入：**20.3 分钟**（1,219,300 ms，校验 59.4 s），
  `files/home/dsh/node_modules/` 结构正确。
- 导入后启动：约 3 分钟监听 `127.0.0.1:3080`，`GET /` → 401（需要 token），
  日志打印带 token 的地址，WebView 可正常进入控制台。
- 结论：T-10 的"全新设备从装 APK 到能用"链路闭环，剩余 EXPERIMENTAL 只限
  "无公共分发服务器""无增量/回滚"，已写进 `docs/DISTRIBUTION.md`。

### 2. 长稳与内存：两内核 + WebView 同时在线 16 分钟

采样方式：`dumpsys meminfo <pid>` 的 TOTAL PSS + `/proc/<pid>/status` VmRSS + `/proc/meminfo`，
每 60 s 一次，共 16 个点（脚本 `.tmp/memwatch.ps1`，未入库）。

| 进程 | PSS 区间 | 备注 |
|---|---|---|
| App 主进程 | 167–175 MB | 波动来自 Compose 刷新 |
| node（Web 控制台） | 265,191 → 268,255 KB（峰值 270,114） | RSS 268,328 → 270,904 KB，16 分钟 +2.6 MB |
| python（管理接口） | 250,070 → 250,080 KB | 基本不变 |
| WebView sandboxed | 约 151 MB（手工采样一次） | `pidof` 全名匹配不到，未进 CSV |

- 合计 PSS 峰值约 **840 MB**；`MemAvailable` 稳定在 **1.78–1.81 GB**（设备总内存 3.82 GB）。
- LMK：`dumpsys activity lmk` 计数 **0**，logcat 无 `lowmemorykiller` 事件；
  仅有的两条 `am_kill` 是 Google 自家应用因 CPU 超标被系统杀，与本 App 无关。
- 决策：**不实现**"按需单内核启动 + 空闲回收"。理由：占用约为设备内存的 22%，
  离 4 GB 预算很远；为省这点内存引入状态检查点/恢复会显著增加丢任务风险。
  记录在此，等真机（4/6/8 GB 机型）实测再定。

### 3. WebView 健壮性复测（真实失败路径）

- 内核未启动时打开 WebView → 错误页："页面打不开 / 加载失败（-6）：
  `net::ERR_CONNECTION_REFUSED`"+ 处理步骤 +「重试」「返回控制台启动」。
  证据：`docs/evidence/webview-error-page.png`。
- 内核起来后点「重试」→ 页面恢复加载，顶部进度条可见（约 75%）。
  证据：`docs/evidence/webview-retry-progress.png`。
- 新增取证入口 `--ez runtime_webview true`：不依赖内核状态直接打开 WebView，
  用于复现错误页（此前只能等"进程刚好被回收"这种偶发条件）。

### 4. arm64：APK 已能产出，真机仍未验证

- 链路：`scripts/build-runtime-arm64.ps1` 取 Termux `aarch64` 的 nodejs 26.13.0 / python 3.14.10
  依赖闭包 → 解包 → 复用 `build-runtime.ps1 -Abi arm64-v8a` → 拷 `launcher`/`selftest`
  两个与架构无关的静态 assets。
- 接入方式：`runtime-host/build.gradle.kts` 读 `-PruntimeAssetsDir` / `-PruntimeJniLibsDir`，
  命中时**整套替换** `src/main/assets` 与 `src/main/jniLibs`；`app` 的 `abiFilters` 读
  `-PruntimeAbi`（默认 `x86_64`）。两套产物不能混：stdlib 里 `lib-dynload` 下的动态库是架构相关的。
- 命令：
  ```
  gradlew :app:assembleDebug -PruntimeAbi=arm64-v8a \
      -PruntimeAssetsDir=<repo>\.tmp\arm64-assets \
      -PruntimeJniLibsDir=<repo>\.tmp\arm64-jniLibs
  ```
- 解包核对（arm64 APK 63,734,464 B）：`lib/arm64-v8a` 14 个 `.so` / 99.8 MB，
  逐个读 ELF 头全部 `e_machine=0xB7`（AArch64）；`assets/runtime-libs` 28 个（与 x86_64 同清单，
  补了 lxml/Pillow/tiktoken 需要的 libxml2、libxslt、libtiff、libjpeg、libwebp、libxcb 等）；
  `assets/launcher`、`assets/selftest` 齐全；`extractNativeLibs=true`。
- 未改默认构建：不传属性时仍产出 x86_64 包（`lib/x86_64` 15 项 / 99.5 MB），已回归验证。
- 未验证的部分（不伪造）：没有 arm64 真机，内核能否在这套产物上起来、Octop 的原生扩展能否加载，
  **均未测**。T-04 保持"未验证"。
- 两个真实故障（已修，见 `DECISIONS.md` D-044/D-045 与「已知坑」6/19）：
  ①`build.gradle.kts` 的 KDoc 里写了 `lib-dynload/*.so`，Kotlin 支持嵌套块注释，
     `/*` 让后面的 `dependencies`/任务注册全部变成注释 → 编译报 `Unresolved reference: core`，
     且 `tasks.register` 不生效（表现为"任务不存在"）；
  ②Termux deb 文件名带 epoch 冒号，`Test-Path` 直接报"不支持给定路径的格式"，
     还会把文件写成 NTFS 备用数据流 → fetch 脚本改为本地 `_`、URL `%3A`。

### 5. 合规与发布面

- `LICENSE`（MIT）、`THIRD_PARTY_NOTICES.md`（两个上游完整 MIT 文本 + Node/CPython 运行时 +
  13 个原生库 + 155 个 Python 包 + 580 个 Node 包 + 品牌说明 + libimagequant GPL-3.0 风险标注）。
- Gradle `copyLicenseAssets` 把两份文件复制进 `assets/licenses/`，App 内「开源许可」页
  离线可读、全文可复制；实测：本应用 1,187 字符、第三方组件 36,604 字符。
  证据：`docs/evidence/licenses-page.png`。
- 去品牌化：**用户可见文案零命中**（内核卡片「Web 控制台」「管理接口」、许可页、应用名
  `Runtime Console`）。仍保留上游名的只有三类，均非用户界面：
  ①代码标识符与日志文件名（`KernelId.DSH_WEB`、`dsh-web.log`）；
  ②运行时路径（`@deepseek-ai/dsh-*`、`octop` 包名，改了内核就起不来）；
  ③`docs/` 与 `upstream/` 中的溯源记录。这三类属于功能性标识，已在 NOTICES 第 6 节说明。
- README 三档标注：已验证 / EXPERIMENTAL / 计划中，逐条对应证据或限制。

### 6. CI：本地门禁可用，远端未验证

- `scripts/ci-local.ps1` = 构建 + 单元测试 + 补丁可应用性；`.github/workflows/ci.yml` 跑同一组命令。
- 新增单元测试 `RuntimePackNamingTest`（zip 分隔符归一化，覆盖上面那个真实故障）。
- 修了两个"门禁自己坏了"的问题：
  ①`patches/dsh/node-addon-require-builtin+0.1.9.patch` 行数头与实际 hunk 不符，
     且曾被 ASCII 编码写坏中文注释，`git apply` 报 corrupt patch → 用 npm 原始 tarball
     与仓库内参考文件重新生成（UTF-8 / LF），实测前向、反向均可应用；
  ②`git -C .tmp/dsh-npm apply --check` 会因 `.gitignore` 静默 "Skipped patch" 并返回 0，
     检查形同虚设 → 改为复制目标文件到仓库外临时目录再检查。
- 本地门禁结果：`BUILD SUCCESSFUL` + 单测通过 + `APPLY-OK`（已应用，反向可还原）。
- **没有远端，CI 从未真正跑过**；以本地脚本结果作为提交门禁，这是事实，不写成"CI 已通过"。

### 本轮遗留 / 下一轮接手点

1. arm64 真机验证：包能产出、内容已核对，但没有任何运行期证据（T-04）。
2. 导入吞吐：`tar + rename` 方案（需内核侧配合改 `NODE_PATH`），目标 20 分钟 → 3 分钟内。
3. 运行时包增量更新与回滚（保留上一版本目录 + 版本号切换）。
4. 分发服务器：对象存储 + 分卷 + 校验清单；否则 HTTP 导入只能留在 EXPERIMENTAL。
5. 真机验证（4 GB 内存机型），届时再评估是否需要空闲回收。

## 2026-11-08 第四轮：tar 导入 / 增量回滚 / release / README 校准 / 推送

### 1. 导入吞吐：tar 路径落地，zip 保留

- 实现：manifest 新增可选 `"format": "tar" | "tar.gz"`（默认 `zip`）。安装器把整包字节直灌
  `/system/bin/tar -xzf - -C <staging> -o` 的 stdin，不再在 Java 里逐条目建对象。
- **不需要内核侧补丁**：解出的目录结构与 zip 完全一致，`NODE_PATH` / 入口相对路径都不用动，
  `patches/` 与 `docs/UPSTREAM.md` 无新增。
- 实测（同一台 `emulator-5560`，x86_64）：
  | 场景 | 结果 |
  |---|---|
  | 133 MB 树 / 8,660 文件 / 14.1 MB tar.gz | **28.0 s**（校验 2.8 s + 解包 25.1 s），5.3 MB/s 产出、346 文件/s |
  | 350.7 MB tar / 16,434 条目（双内核在线） | **93.4 s**（校验 17.8 s + 解包 75.6 s），4.4 MB/s 产出、217 文件/s |
  | 基线 zip（69,498 文件） | 20.3 分钟，57 文件/s，0.4–0.5 MB/s |
  | 全量 69,498 文件外推 | tar **2.7–5.3 分钟**（闲置 429 文件/s ↔ 双内核在线 217 文件/s） |
- 结论：**有明显提升（4–7.5 倍），不回滚**；但"3 分钟内"只在无内核争抢 IO 时成立，
  完整 69k 文件端到端仍未跑过，README 标 EXPERIMENTAL。
- 途中修掉的真问题：`--restrict` 会把锚点设成归档第一个目录，多根 tar 大面积失败；
  toybox 默认就拒绝解到 `-C` 之外（`../evil.txt` 实测被拦），去掉 `--restrict`。
- 磁盘压力：350 MB 包测试时 `/data` 一度 88%，测完立即清理；大包导入的空间预检依赖 manifest
  的 `bytes`，未声明时跳过。

### 2. 增量更新与回滚

- 旧目录不再删除，改名 `<target>.prev`；marker 增加 `prevPackVersion`、`targets`。
- `--ez runtime_pack_rollback true` 实测：tartest-2 → 回滚 → marker 回到 tartest-1，`.prev` 被消费，
  目录内容换回；换到一半失败会逆序还原。
- 仍是整包替换，没有"跳过已存在文件"的增量安装；App 内也没有回滚按钮。

### 3. release 打包

- 根目录无 `keystore.properties` 时回退 debug 签名（文件已 gitignore），`assembleRelease` 可产出可安装 APK：
  - `app-release.apk`：**56,045,023 B（53.45 MB）**，SHA-256 `CFAE2914F476CEEBCE3C9F3A042B39A46B324FB130301151B446F93A6B4BA1AE`
  - 工具链：JDK 21.0.12（Microsoft Build）、Gradle 8.14.2、AGP 8.13.2、Kotlin 2.2.21、compileSdk 36、minSdk 26
  - 构建命令与 `C:\rc` 目录联接法已写进 README
- 首次 `assembleRelease` 因 **C: 盘 0 字节可用** 失败（D8 报磁盘空间不足）；清理
  `~/.gradle/caches/8.14.3/transforms` 后重跑成功，属于本机环境问题，不是工程问题。
- 启动耗时：release 包**已安装并实测冷启动**：`ActivityTaskManager: Displayed … +14s498ms`
  （x86_64 API 36，`am force-stop` 后启动、内核尚未拉起）。`am start -W` 在 12.2 s 处
  返回 `Status: timeout`，因此以 logcat 的 Displayed 为准。

### 4. README 中英双语 + 三档校准

- 逐条核对证据：把"导入 40 分钟""增量未做""arm64 已接入 APK"等过期表述改掉；
  全量导入 3 分钟目标、release 签名、arm64 运行期如实标 EXPERIMENTAL。
- 已验证新增：tar/tar.gz 导入实测、保留上一版本 + 回滚实测。

### 5. 推送（开源已确认）

- 取证：`gh` 未安装；`GH_TOKEN` / `GITHUB_TOKEN` 未设置；`git remote -v` 为空，无 credential helper。
  → **GitHub 无法推送**，原因如实记录，未尝试绕过。
- Gitee：`GITEE_TOKEN` 存在且 `GET /api/v5/user` 可用（账号 `xkx1029`）；目标仓库名
  `runtime-console`，但 `POST /api/v5/user/repos` 返回 **403 已禁止**，令牌没有建仓权限。
  仓库不存在 → 无法 push；按"没有可用凭据就停下如实记录、不绕过"处理。
- **结论：本轮没有推送任何远端。** 本地提交范围：`df30078` 及其后一条 README 修正提交。
  用户在 Gitee 网页建好空仓库（或换一个带 `projects` 权限的令牌、或安装并登录 `gh`）后，
  下一轮可直接 `git remote add gitee <url> && git push -u gitee main`。

### 本轮遗留 / 下一轮接手点

1. 完整 69,498 文件 tar 端到端实测（当前只有 350 MB / 16k 条目的数据与外推）。
2. arm64 真机仍然没有环境（T-04）；release 冷启动已测 14.5 s，但没有真机数据。
3. App 内回滚按钮 + 增量安装（跳过已存在文件）。
4. GitHub 推送需要用户在机器上安装 `gh` 并登录，或提供 GitHub 令牌。
5. 分发服务器仍无（HTTP 导入 EXPERIMENTAL）。

