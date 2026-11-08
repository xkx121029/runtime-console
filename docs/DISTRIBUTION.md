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
      "files": ["dsh.zip"]
    }
  ]
}
```

规则：

- `target` 是相对 App `filesDir` 的落位目录；安装器拒绝越界路径（`..` / 绝对路径）；
- `sha256` 是**所有分卷按序拼接后**的哈希，分卷不削弱完整性；
- `abi` 与设备 ABI 不符时直接拒绝（避免把 x86_64 包装到 arm64 机器上跑出一堆 `Exec format error`）；
- 安装先解到 `<target>.staging`，校验通过才原子替换；旧目录改名备份，成功后再删。
  校验失败时旧数据一个字节都不动。

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

### 3.3 传到手机（三选一）

| 方式 | 适用 | 实测 |
|---|---|---|
| USB / MTP 直接拷到 `下载/runtime-pack/` | 普通用户 | 推荐，速度取决于数据线 |
| `adb push` 到 `下载/runtime-pack/` | 开发者 | **4.1 MB/s，234 MB 用时 53 s** |
| App 内 HTTP 下载（EXPERIMENTAL） | 有自建分发时 | 代码支持 Range 续传，无公共服务器可演示 |

### 3.4 在 App 内导入

1. 打开 App → 顶部「运行时组件」卡片会显示两个组件是否缺失；
2. 点「导入运行时包」→ 系统目录选择器 → 选中 `runtime-pack` 目录；
3. 观察进度：`校验 …%` → `解压 …（N 项）…%`；
4. 成功后组件状态变为「已就绪」，内核卡片可正常启动。

## 4. 实测数据（x86_64 模拟器，Android 16 / API 36）

| 环节 | 实测 |
|---|---|
| PC 侧打包 dsh（7 万文件，Fastest 压缩） | 约 3 分钟 → 234,550,743 B |
| `adb push` 234 MB | 53 s（4.1 MB/s） |
| 设备端 SHA-256 校验 234 MB | **22.6 s**（首次）/ **59.4 s**（同机第二次，后台还有内核在跑） |
| 设备端解包 7 万文件（正确目录树） | **20.3 分钟**（1,219,300 ms，69,498 个文件，约 0.6 MB/s 产出） |
| 设备端解包（踩到分隔符 bug 的那次） | 48.0 分钟（2,879,193 ms），且产出的树不可用，见 6.1 |
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
2. **导入慢**：正确目录树下 20 分钟量级（0.6 MB/s 产出）。瓶颈在"海量小文件逐条创建"，
   同一设备顺序写实测 100 MB/s。计划中的改进：
   - 把解包放进前台服务并加唤醒锁，避免导入期间被降频；
   - 依赖整理成更少的大文件（单个不压缩 tar + 首启 `rename`），需要内核侧配合改 `NODE_PATH`；
   - 提供"跳过已存在文件"的增量导入。
3. **没有公共分发服务器**：HTTP 导入是 EXPERIMENTAL。要么自带对象存储，要么继续用
   "PC 打包 → 手机拷贝"。
4. **没有增量与回滚 UI**：导入是整包替换，不保留版本历史（旧目录在成功后被删除）。
5. **arm64 包未产出**：见 `docs/STATE.md` T-04，当前只有 x86_64 运行时。

## 7. 相关代码

| 文件 | 作用 |
|---|---|
| `runtime-host/src/main/java/dev/runtimeconsole/runtimehost/RuntimePack.kt` | 包解析、校验、解包、原子替换 |
| `runtime-host/src/main/java/dev/runtimeconsole/runtimehost/RuntimePackDownloader.kt` | HTTP Range 续传（EXPERIMENTAL） |
| `app/src/main/java/dev/runtimeconsole/app/SafPackSource.kt` | 系统目录选择器 → 包来源 |
| `app/src/main/java/dev/runtimeconsole/app/MainActivity.kt` | 首装引导入口、进度状态、`runtime_pack_dir` / `runtime_pack_url` 取证入口 |
| `ui/src/main/java/dev/runtimeconsole/ui/RuntimeConsoleApp.kt` | 「运行时组件」卡片 |
| `scripts/pack-runtime.ps1` | PC 侧打包与 manifest 生成 |
