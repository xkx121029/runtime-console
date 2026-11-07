# 项目状态（STATE）

最后更新：2026-11-08 00:40（Asia/Shanghai）

## 当前上游 SHA

| 仓库 | 路径 | SHA | 版本 / 日期 |
|---|---|---|---|
| deepseek-harness | `upstream/deepseek-harness` | `fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159` | 0.2.2-rc.1 / 2026-11-04 |
| Octop | `upstream/octop` | `4022e24ef6c34e9716350872b0e93bfaae7a20c3` | 1.0.3b4 / 2026-11-06 |

## 里程碑进度

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 审计 + 最小可运行验证 | **已完成** | 两个运行时在模拟器上跑通，证据见下 |
| M1 运行时打包 | 未开始 | 把 Node/Python 放进 APK `lib/` 并由 `RuntimeHost` 启动 |
| M2 内核启动 | 未开始 | dsh / Octop 真实启动 |
| M3 桥接 | 未开始 | gateway + bridges |
| M4 UI | 未开始 | 最朴素可用界面 |

## M0 结论：**conditional-go**

- 运行时层面：**go**。Node 26.13.0 与 CPython 3.14.10 均已在 x86_64 模拟器上完成"版本 + 读写文件 + 127.0.0.1 HTTP 自请求"验证。
- 内核层面：**待 M1/M2 验证**。dsh 依赖 `node:sqlite`（Node 22+），nodejs-mobile（Node 18）路线被证伪；改用 Termux 预编译 Node 26 后可满足版本要求，但把二进制搬进 APK `lib/` 的启动尚未验证。

## 已完成

1. `git init`（main）、`.gitignore`；两个上游以 submodule 固定 SHA。
2. 事实基线核对（见 `docs/PORTING_AUDIT.md`）。
3. 最小 Android 工程骨架：`app` + `core` / `runtime-host` / `gateway` / `bridges` / `ui`，Kotlin + Compose，`assembleDebug` 通过，APK 11.4MB。
4. 应用已安装并启动，界面在前台（`topResumedActivity=dev.runtimeconsole.app/.MainActivity`）。
5. 两个运行时设备侧验证通过（见下）。

## 实测证据

环境：`emulator-5560`，sdk_gphone64_x86_64，API 36（Android 16），x86_64；adb 直连。

### Node

命令：
```
adb shell "cd /data/local/tmp/t && LD_LIBRARY_PATH=/data/local/tmp/t/usr/lib POC_DIR=/data/local/tmp/t ./usr/bin/node hello.js"
```
输出：
```
NODE_VERSION=v26.13.0
NODE_ARCH=x64
NODE_PLATFORM=android
NODE_CWD=/data/local/tmp/t
FILE_WRITE_READ=hello-from-node
FILE_SIZE=16
HTTP_LISTEN=127.0.0.1:43875
HTTP_SELF_FETCH=200:ok-from-node
```

### Python

命令：
```
adb shell "cd /data/local/tmp/t && LD_LIBRARY_PATH=/data/local/tmp/t/usr/lib PYTHONHOME=/data/local/tmp/t/usr PYTHONPATH=/data/local/tmp/t/usr/lib/python3.14 POC_DIR=/data/local/tmp/t ./usr/bin/python3.14 hello.py"
```
输出：
```
PYTHON_VERSION=3.14.10 (main, Nov  5 2026, 05:25:26) [Clang 21.0.0 ...
PYTHON_IMPL=CPython
PYTHON_MACHINE=x86_64
PYTHON_CWD=/data/local/tmp/t
FILE_WRITE_READ=hello-from-python
FILE_SIZE=18
HTTP_LISTEN=127.0.0.1:42201
HTTP_SELF_FETCH=200:ok-from-python
```

### 体积与启动

| 项 | 实测值 | 备注 |
|---|---|---|
| `usr/bin/node` | 50,715,096 B（48.4MB） | Termux 预编译，动态链接 bionic |
| `usr/lib/libpython3.14.so` | 5,822,696 B（5.6MB） | CPython 主体 |
| 运行时整棵树 `/data/local/tmp/t/usr` | 154MB | 含 headers/静态库，正式打包可裁到约 60–70MB |
| 骨架 APK | 11.4MB | 空壳 |
| 冷启动 `Displayed` | 19.9s / 24.6s | 模拟器首次冷启动極慢，为空壳 Compose，不代表真机；需在 M4 用真机复测 |
| 常驻内存 PSS | 63,238 KB | 空壳界面 |

## 下一步（M1 具体动作）

1. 把裁剪后的运行时树放入 `runtime-host/src/main/jniLibs/<abi>/lib*.so`（Node 二进制改名 `libnode_exec.so`，依赖库全部 `lib*.so`），在 `applicationInfo.nativeLibraryDir` 定位并 `exec`。
2. `RuntimeHost` 负责：注入 `HOME`/`TMPDIR`/`DSH_HOME`/`OCTOP_HOME`、`LD_LIBRARY_PATH`、日志重定向到 `filesDir/logs`。
3. 先在模拟器验证"从 APK lib/ 启动 node -v 与 python -V"，再进入 M2 启动内核。
4. 同步产出上游补丁：`HOME` 注入、`node:sqlite` 兼容确认、Octop 的 `~/.octop` 处理。

## 已知坑

1. `github.com:443` 间歇不可达；`api.github.com`/`codeload.github.com`/`ghfast.top` 可达。克隆失败改走 `https://ghfast.top/https://github.com/...`。
2. 任务书的 `TencentCloud/Octopus` 不存在，实际是 `TencentCloud/Octop`（DECISIONS D-001）。
3. **nodejs-mobile 只到 Node 18.20.4，而 dsh 使用 `node:sqlite`（`upstream/deepseek-harness/packages/storage/storage-sqlite/src/unit.ts:11`），Node 18 无此模块 → nodejs-mobile 路线对 dsh 不成立。**
4. 项目绝对路径含 `&`（`e:\happy_dsh&octopinphone`），cmd 批处理会把 `&` 当分隔符：
   - `gradlew.bat` 直接在原路径不可用；已建目录联接 `C:\rc` → 项目根，用 `C:\rc\gradlew.bat` 构建。
   - PowerShell 里直接调 `gradle.bat` 传含 `&` 的 `-p` 参数也会被截断；改用 `java -classpath gradle-launcher-*.jar org.gradle.launcher.GradleMain -p "原路径"`。
5. Termux 包文件名含冒号（`openssl_1:3.6.5_x86_64.deb`），PowerShell 的 `Split-Path -Leaf` 会把冒号当路径分隔符，下载脚本需特殊处理。
6. `adb push` 目录时遇到符号链接（`usr/lib/terminfo`、`usr/lib/icu/current`）会报 Permission denied；改为本地 `tar -czf` + 设备端 `toybox tar -xzf`。
7. 模拟器无 `curl`/`wget`，设备侧只能靠自写脚本自请求验证 HTTP。
8. 模拟器极慢（冷启动 20s+），不要用它判断性能。
9. 下载走 `packages.termux.dev` 约 30KB/s，中科大镜像 `mirrors.ustc.edu.cn/termux` 约 500KB/s，后续一律用镜像。

## 技术债

| 编号 | 内容 | 影响 |
|---|---|---|
| T-01 | 上游补丁尚未产生 | M1 起每处改动出 `.patch` 并写 `docs/UPSTREAM.md` |
| T-02 | 运行时目前在 `/data/local/tmp` | 正式形态必须进 APK `lib/` |
| T-03 | 只能用 Termux 预编译包（非官方构建） | 需在 M1 评估官方 Node 交叉编译或固化 Termux 构建来源 |
| T-04 | 真机 arm64 未验 | 报告已标注验证环境为 x86_64 模拟器 |
| T-05 | `gradlew.bat` 在含 `&` 路径下不可用 | 用 `C:\rc` 联接绕过；文档需说明 |

## 验证环境

| 项 | 值 |
|---|---|
| 设备 | `emulator-5560`（sdk_gphone64_x86_64，API 36，Android 16，x86_64） |
| adb | `c:\Users\linch\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| JDK | `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`（21.0.12.1） |
| Gradle | 8.14.2（缓存，`~/.gradle/wrapper/dists/gradle-8.14.2-bin`） |
| AGP / Kotlin | 8.13.2 / 2.2.21 |
| SDK | platforms 33–36，NDK 26.1/27.0/28.2，CMake 3.22.1 |
