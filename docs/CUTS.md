# 裁剪清单（CUTS）

原则：裁剪必须是"明确、可解释、可恢复"的，不允许悄悄消失。每条裁剪记录：裁了什么 / 为什么 / 用户少什么 / 以后怎么补。

| 编号 | 裁剪项 | 为什么 | 用户少什么 | 以后怎么补 |
|---|---|---|---|---|
| C-01 | dsh Desktop（Electron） | Electron 无法在 Android 运行；`electron ^44.7.0`（`apps/desktop/package.json:71`） | 没有桌面端窗口，改用 Android 原生壳 | 无需补；Android 壳即替代品 |
| C-02 | dsh Browser Use（Playwright） | 无 Chromium；Playwright 需要下载浏览器二进制（`apps/web/package.json:58`） | 不能网页自动化、不能截图 | 以后可用 Android WebView + CDP 自建替代（新功能，不属于移植） |
| C-03 | dsh 内嵌终端（node-pty） | node-pty 无 Android 预编译，重编成本高（`packages/subprocess/subprocess-local/package.json:55`） | 无内嵌交互终端 | 后续用 NDK 重编 node-pty，或改用 `sh -c` 的简易执行器 |
| C-04 | dsh 图片处理（sharp） | libvips 无 Android 预编译（`packages/attachment/attachment-local/package.json:39`） | 附件缩略图/图像变换不可用 | 改用纯 JS 解码或裁剪附件图像功能 |
| C-05 | dsh SSH / 远程桌面 | 依赖系统 ssh 与桌面协议栈 | 无 SSH 会话、无远程桌面 | 不补（与手机形态冲突） |
| C-06 | Octop Browser AI+（playwright + octop-browser） | 同 C-02 | 无浏览器自动化 | 同 C-02 |
| C-07 | Octop PostgreSQL 后端（psycopg[binary]） | 默认 SQLite 已够用，psycopg 是二进制扩展（`pyproject.toml:38`） | 不能用 PostgreSQL 作为控制面数据库 | 若 Chaquopy 能提供 psycopg 轮子，重新加回 |
| C-08 | Octop Let's Encrypt（acme/josepy） | 手机端不需要对外提供 HTTPS | 不能申请公网证书 | 不补 |
| C-09 | Octop 远程桌面（mss/pynput extras） | Android 无 X11/桌面输入模型 | 不能作为被控桌面 | 不补 |
| C-10 | Octop 本地 ONNX 向量检索（fastembed/onnxruntime） | 无 Android onnxruntime 轮子（`pyproject.toml:79-84`） | 本地 embedding/OCR 不可用 | 后续可改用云端 embedding，或寻找 Android 版 onnxruntime |
| C-11 | 局域网访问 | 硬性约束：只 bind 127.0.0.1 | 同一 Wi-Fi 下其他设备无法直接访问 | 不补（安全边界） |
| C-12 | nodejs-mobile 运行时路线 | 最高只到 Node 18.20.4，而 dsh 使用 `node:sqlite`（Node 22+）：`upstream/deepseek-harness/packages/storage/storage-sqlite/src/unit.ts:11` | 无（改用 Termux 预编译 Node 26.13.0，实测可用） | 不补；若上游未来发布 Node 22+ 预编译再评估 |
| C-13 | Chaquopy 路线 | Chaquopy 最高 Python 3.13；Termux 已有 3.14.10 预编译且实测可跑，避免私有仓库与 Gradle 插件依赖 | 无（功能等价，且版本更高） | 不补 |
| C-14 | 运行时树中的 headers/静态库（`include/`、`lib/*.a`） | 只为编译期服务，运行时不需要 | 无 | 打包时按需再补 |
| C-15 | dsh 语音输入（`sherpa-onnx-linux-x64`，31.8 MB） | 只有 linux-x64 预编译，无 Android 目标 | 不能本地语音识别 | 后续接 Android `SpeechRecognizer`（新功能） |
| C-16 | dsh 桌面相关原生包（`@img/sharp-*`、`node-pty` 二进制） | 无 Android 预编译 | 见 C-03 / C-04 | 同 C-03 / C-04 |

## 降级（非裁剪，但能力打折）

| 编号 | 降级项 | 原能力 | Android 形态 | 何时重新评估 |
|---|---|---|---|---|
| G-01 | 进程隔离 | Landlock + flock 强制隔离 | 若 `system.node`/`landlock-run` 不可用则退化为普通子进程 | M2 实测后 |
| G-02 | Octop uvicorn | `uvloop` + `httptools` | 纯 Python event loop（h11） | M2 |
| G-03 | Octop IM 通道 | 全量渠道 | 仅保留纯 Python 可用的渠道，其余标 EXPERIMENTAL | M3 |
| G-04 | dsh 内部模块绑定 | `node-addon-require-builtin` 原生句柄 | `--expose-internals` + 普通 `require`（补丁 P-01） | 上游发布 android 预编译包后恢复 |
