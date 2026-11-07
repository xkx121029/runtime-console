# 项目状态（STATE）

最后更新：2026-11-08 02:20（Asia/Shanghai）

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
| M2 内核启动 | 进行中 | dsh `web` 已在设备返回 HTTP 200；Octop 待补原生依赖 |
| M3 桥接 | 未开始 | gateway + bridges |
| M4 UI | 未开始 | 最朴素可用界面 |

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

### 已知告警（功能不受影响）

- 首次启动时 `spill-local` 插件因 `os.tmpdir()` 指向 Termux 私有目录而失败。注入 `TMPDIR=filesDir/tmp`
  并确保目录存在后，**0 个插件告警**。这也是 `RuntimeHost.exec()` 里已经注入 `TMPDIR` 的原因。

## 下一步（M2-octop）

1. pydantic-core 2.46.4 没有 android 轮子，已用 Rust + NDK 交叉编译成功（`x86_64-linux-android`，
   产物 `lib_pydantic_core.so` 6,093,592 bytes，3m01s）。待落到设备验证。
2. 其余原生依赖（cffi / pyyaml / argon2-cffi-bindings 等）Termux 仓库没有现成包，需同法用 NDK clang 编。
3. `uvloop` / `httptools` / `watchfiles` / `playwright` / `psycopg[binary]` 判为不可用，裁掉（见 `docs/CUTS.md`）。

## 已知坑

1. `github.com:443` 间歇不可达；`api.github.com`/`codeload.github.com`/`ghfast.top` 可达。
2. 任务书的 `TencentCloud/Octopus` 不存在，实际是 `TencentCloud/Octop`（D-001）。
3. dsh 用 `node:sqlite`（`upstream/deepseek-harness/packages/storage/storage-sqlite/src/unit.ts:11`），
   Node 18 不可用 → 已用 Termux Node 26（D-010 修订）。
4. 项目路径含 `&`：用目录联接 `C:\rc` 构建（`C:\rc\gradlew.bat -p C:\rc`）。
5. PowerShell 5.1 读无 BOM 的 UTF-8 脚本会把中文当 ANSI 并破坏语法：`scripts/*.ps1` 必须带 BOM。
   新增脚本后执行：
   `[IO.File]::WriteAllText($p, [IO.File]::ReadAllText($p,[Text.Encoding]::UTF8), (New-Object Text.UTF8Encoding($true)))`
6. Termux 包名含冒号（`openssl_1:3.6.5`），PowerShell 的 `Split-Path -Leaf` 会误判，需特殊处理。
7. `adb push` 目录遇符号链接报错，改用 `tar` 打包传输。
8. 模拟器无 `curl`/`wget`，设备侧 HTTP 验证只能自写脚本自请求。
9. 模拟器极慢（冷启动 20s+），不可用于性能结论。
10. `assets` 里空目录会丢失，`AssetManager.list()` 对文件返回空数组，递归时按"空数组=文件"处理。
11. 每次改 assets 都要重新 `assembleDebug` 并**重装**（不是覆盖运行时目录），否则设备上仍是旧文件。

## 技术债

| 编号 | 内容 | 影响 |
|---|---|---|
| T-01 | 上游补丁尚未产生 | M2 起每处改动出 `.patch` 并写 `docs/UPSTREAM.md` |
| T-02 | 运行时二进制不入库（60.9MB APK、99.5MB lib） | 换机/清缓存后需重跑 `scripts/build-runtime.ps1` |
| T-03 | 只能依赖 Termux 预编译包（非上游官方 Android 构建） | 需固化下载源与校验和 |
| T-04 | 真机 arm64 未验 | 目前证据仅 x86_64 模拟器 |
| T-05 | `gradlew.bat` 在含 `&` 路径下不可用 | 用 `C:\rc` 联接绕过 |
| T-06 | 兼容库靠 `sitecustomize.py` 预加载 | 若某扩展用 `ctypes.CDLL("libssl.so.3")` 名字也能命中；但进程内多份同名库会双份内存 |
| T-07 | 首启解压 15.7MB stdlib 在主线程外的 IO 线程，但无进度条之外反馈 | 首次自检约 20~40s（模拟器） |

## 验证环境

| 项 | 值 |
|---|---|
| 设备 | `emulator-5560`（sdk_gphone64_x86_64，API 36，Android 16，x86_64） |
| adb | `c:\Users\linch\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| JDK | `C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot`（21.0.12.1） |
| Gradle / AGP / Kotlin | 8.14.2 / 8.13.2 / 2.2.21 |
| SDK | platforms 33–36，NDK 26.1/27.0/28.2，CMake 3.22.1 |
