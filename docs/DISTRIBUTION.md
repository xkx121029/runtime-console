# 首装分发（DISTRIBUTION）

最后更新：2026-11-08

本文回答一个问题：**一台全新设备，只有 APK，怎么走到"能用"**。

结论先说：

| 环节 | 状态 | 说明 |
|---|---|---|
| APK 自带 Node / CPython 运行时 | **已验证** | 两个运行时二进制与标准库在 APK `lib/` 与 assets 内，装完即可执行 |
| APK 自带业务依赖（node_modules / python-site） | **刻意不做** | 合计约 1.5 GB，见下节 |
| 运行时包格式 + 校验 + 分卷 | **已验证** | `manifest.json` + 分卷 zip + SHA-256；PC 侧脚本可重放 |
| App 内首装引导 + 本地导入（SAF） | **已验证** | 系统目录选择器选包目录，App 内校验解包，进度可见；导入后 dsh 正常监听 3080 |
| App 内 HTTP 断点续传 | **EXPERIMENTAL** | 代码已实现（Range 续传），但项目没有公共分发服务器，无法端到端演示 |
| 免手工一键安装 | **未达成** | 用户仍需把包拷到手机（USB / MTP / 网盘）后点一次"导入" |

## 1. 为什么业务依赖不进 APK

| 组件 | 解包后体积 | 文件数 | 说明 |
|---|---|---|---|
| `files/home/dsh`（Node 侧 node_modules） | 约 1.1 GB | 约 7 万 | dsh 0.2.2-rc.1 的完整依赖 |
| `files/python-site`（Python 侧） | 803 MB | 319 个顶层条目 | Octop 1.0.3b4 的 langchain 生态依赖 |

塞进 APK 的代价：

- APK 会从 61 MB 膨胀到 ~1.6 GB，`adb install` 与系统安装器都要整份拷贝并解压，安装耗时和失败率都不可接受；
- `assets` 解压走 `ZipFile` 随机读，7 万个小文件在首启时逐条解压，和下面的导入代价一样，只是把等待从"导入"挪到"安装"；
- 运行时依赖升级必须重发 APK，与"两个内核独立更新"的目标冲突。

所以采用**运行时分包（runtime pack）**：APK 只带运行时二进制与胶水代码，业务依赖做成一个带清单与校验的可分发归档。

## 2. 包格式

```
runtime-pack-x86_64/
  manifest.json
  dsh.zip                 # 可选：按 -SplitMb 切成 dsh.zip.000 / .001 …
  python-site.zip
```

`manifest.json`（schema=1）：

```json
{
  "schema": 1,
  "packVersion": "x86_64-20261108-080130",
  "abi": "x86_64",
  "createdAt": "2026-11-08T08:18:29+08:00",
  "parts": [
    {
      "id": "home/dsh",
      "title": "Node 运行时（Web 控制台）",
      "target": "home/dsh",
      "bytes": 234550743,
      "sha256": "d573d277…",
      "files": ["dsh.zip"],
      "format": "zip"
    }
  ]
}
```

`format` 可选 `zip`（默认，兼容旧包）、`tar`、`tar.gz`。实测 tar 路径比 zip 快一个数量级
（见第 4 节），新包建议用 `tar` 或 `tar.gz`；tar 包不再需要分卷，`files` 写一个文件名即可。

规则：

- `target` 是相对 App `filesDir` 的落位目录；安装器拒绝越界路径（`..` / 绝对路径）；
- `sha256` 是**所有分卷按序拼接后**的哈希，分卷不削弱完整性；
- `abi` 与设备 ABI 不符时直接拒绝（避免把 x86_64 包装到 arm64 机器上跑出一堆 `Exec format error`）；
- 安装先解到 `<target>.staging`，校验通过才原子替换；旧目录改名成 `<target>.prev` **保留**，
  用于回滚（见 3.5）。校验失败时旧数据一个字节都不动。

PC 侧生成：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/pack-runtime.ps1 `
  -DshDir  .tmp\dsh-npm `
  -SiteDir .tmp\octop-site `
  -SplitMb 1900
```

## 3. 全新设备安装步骤

### 3.1 前置

- 装上 APK（debug 或 release），首次打开不需要任何权限；
- 手机预留 **≥ 3 GB** 可用空间（解包峰值 = 新目录 + 旧目录 + 包本身）。

### 3.2 打包（PC，一次）

1. 用 `scripts/stage-dsh-runtime.ps1` 准备好 Node 依赖（`.tmp/dsh-npm`）；
2. 用 `scripts/octop-runtime.ps1` 准备好 Python 依赖（`.tmp/octop-site`）；
3. `scripts/pack-runtime.ps1` 生成 `manifest.json` + 分卷 zip。

也可以直接产出一个 tar 包（推荐，导入快一个数量级）：

```powershell
# 目录内容直接作为 target 根，不要再套一层目录
tar -czf dsh.tar.gz -C .tmp\dsh-npm .
tar -cf  dsh.tar    -C .tmp\dsh-npm .
Get-FileHash dsh.tar.gz -Algorithm SHA256   # 填进 manifest 的 sha256
```

Windows 自带的 `tar.exe`（bsdtar）可用；Android 端解包用系统 `toybox tar`，
不需要额外依赖，也不需要改内核的 `NODE_PATH` 或入口路径——解出来的目录结构和 zip 完全一致。

### 3.3 传到手机（三选一）

| 方式 | 适用 | 实测 |
|---|---|---|
| USB / MTP 直接拷到 `下载/runtime-pack/` | 普通用户 | 推荐，速度取决于数据线 |
| `adb push` 到 `下载/runtime-pack/` | 开发者 | **4.1 MB/s，234 MB 用时 53 s** |
| App 内 HTTP 下载（EXPERIMENTAL） | 有自建分发时 | 代码支持 Range 续传，无公共服务器可演示 |

### 3.4 在 App 内导入

1. 打开 App → 顶部「运行时组件」卡片会显示两个组件是否缺失；
2. 点「导入运行时包」→ 系统目录选择器 → 选中 `runtime-pack` 目录；
3. 观察进度：`校验 …%` → `解包 …%`；
4. 成功后组件状态变为「已就绪」，内核卡片可正常启动。

### 3.5 升级与回滚

- 再次导入新包时，旧目录不会删除，而是改名成 `<target>.prev`；`files/runtime-pack.json`
  记录 `packVersion` 与 `prevPackVersion`。
- 回滚（无需重新导入 1.5 GB）：

```bash
adb shell am start -n dev.runtimeconsole.app/.MainActivity --ez runtime_pack_rollback true
```

  回滚把当前目录挪开、`.prev` 换回来，成功后删除被替换下来的目录；任意一步失败都会
  逆序还原，不会留下"一半新一半旧"。
- 保留了上一版就要求磁盘能同时容纳「当前 + 新版 staging + 上一版」；空间不足时安装器
  会在预检阶段直接报"存储空间不足"，不会装到一半失败。

## 4. 实测数据（x86_64 模拟器，Android 16 / API 36）

| 环节 | 实测 |
|---|---|
| PC 侧打包 dsh（7 万文件，Fastest 压缩） | 约 3 分钟 → 234,550,743 B |
| `adb push` 234 MB | 53 s（4.1 MB/s） |
| 设备端 SHA-256 校验 234 MB | **22.6 s**（首次）/ **59.4 s**（同机第二次，后台还有内核在跑） |
| 设备端解包 7 万文件（zip 路径，正确目录树） | **20.3 分钟**（1,219,300 ms，69,498 个文件，57 文件/s，约 0.4–0.5 MB/s） |
| 设备端解包（踩到分隔符 bug 的那次） | 48.0 分钟（2,879,193 ms），且产出的树不可用，见 6.1 |
| 设备端 `tar -xf`（133 MB 树 / 8,660 文件） | **20.2 s** → 6.6 MB/s 产出、429 文件/s |
| 同上经 App 导入（tar.gz 14.1 MB，走 stdin 管道） | **28.0 s**（校验 2.8 s + 解包 25.1 s）→ 5.3 MB/s 产出、346 文件/s |
| 全量 69,498 文件推算（以文件数线性外推） | tar ≈ **2.7 分钟**；tar.gz ≈ **3.4 分钟**（基线 20.3 分钟） |
| 导入后启动 `dsh web` | 约 3 分钟到监听 3080（`GET /` 返回 401，日志打印带 token 的地址） |

> 日志里每次导入都会打印每个 part 的 `校验 <ms>ms，总计 <ms>ms`
> （`adb logcat -s RuntimePack:I`）。换机请以设备实测为准，不要复用本文数字。

## 5. 校验与安全

- 所有包内容**只解包、不执行**；执行的是 APK 自带、由 APK 签名保护的运行时二进制；
- 安装器不申请存储权限：本地导入走 SAF，只拿到用户选中的那个目录；
- 包内路径做 canonical 检查，防 zip-slip；
- `manifest.json` 的 `sha256` 与实际不符时整体中止，不落半份数据；
- 包不携带任何密钥；内核 token 与向导密码仍由内核在设备上首次生成。

## 6. 已知问题

1. **zip 条目分隔符（已修复，代价惨重）**：PC 侧最初用 .NET Framework 的
   `ZipFile.CreateFromDirectory` 打包，条目名是 `node_modules\.bin\acorn` 这种反斜杠形式。
   Android 侧按原样落盘，Linux 把 `\` 当普通字符，69k 个文件全部平铺在根目录，
   导入"成功"但 dsh 启动报 `未找到 dsh 安装`。修复分两处：
   - `scripts/pack-runtime.ps1` 手工建条目并强制 `/`；
   - `RuntimePack.kt` 解包时把 `\` 归一化成 `/`（旧包也能装对）。
   复测：同一份旧包重新导入后 `files/home/dsh/node_modules` 结构正确，dsh 正常监听 3080。
   教训：**导入成功 ≠ 能用**，验收必须包含"导入后内核能起来"。
2. **导入慢（已用 tar 路径缓解，未完全消灭）**：zip 路径 20.3 分钟，瓶颈在 Java 侧
   逐条目创建对象。改成把整包字节灌给系统 `toybox tar` 的 stdin 后，同一棵 133 MB 树
   从 ~152 s（按 57 文件/s 推算）降到 28 s，全量推算 2.7–3.4 分钟。仍然受"海量小文件"
   限制，未做：
   - 把解包放进前台服务并加唤醒锁（当前导入时 App 必须在前台）；
   - 真正的增量导入（现在每次都整包替换，只是保留了上一版本）。
3. **没有公共分发服务器**：HTTP 导入是 EXPERIMENTAL。要么自带对象存储，要么继续用
   "PC 打包 → 手机拷贝"。
4. **回滚只有 adb 入口**：`--ez runtime_pack_rollback true` 可用且已实测；App 内还没有
   "回滚到上一版"按钮，也没有"跳过已存在文件"的增量安装。
5. **arm64 包能构建但未在真机验证**：`scripts/build-runtime-arm64.ps1` 能产出
   `lib/arm64-v8a` 一整套 .so（ELF AArch64）并打进 APK，但没有 arm64 真机、
   模拟器也跑不了 arm64 镜像，见 `docs/STATE.md` T-04。

## 7. 相关代码

| 文件 | 作用 |
|---|---|
| `runtime-host/src/main/java/dev/runtimeconsole/runtimehost/RuntimePack.kt` | 包解析、校验、解包、原子替换 |
| `runtime-host/src/main/java/dev/runtimeconsole/runtimehost/RuntimePackDownloader.kt` | HTTP Range 续传（EXPERIMENTAL） |
| `app/src/main/java/dev/runtimeconsole/app/SafPackSource.kt` | 系统目录选择器 → 包来源 |
| `app/src/main/java/dev/runtimeconsole/app/MainActivity.kt` | 首装引导入口、进度状态、`runtime_pack_dir` / `runtime_pack_url` 取证入口 |
| `ui/src/main/java/dev/runtimeconsole/ui/RuntimeConsoleApp.kt` | 「运行时组件」卡片 |
| `scripts/pack-runtime.ps1` | PC 侧打包与 manifest 生成 |
