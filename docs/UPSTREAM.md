# 上游改动记录（UPSTREAM）

原则：**不改写上游业务逻辑**。只允许补丁级改动——路径与 HOME 注入、平台分支、不可用特性开关、
依赖替换适配。每个补丁都必须在 `patches/` 下有可复现的载体，并在下表登记理由与影响面。

当前固定的上游版本：

| 上游 | 版本 | SHA |
|---|---|---|
| deepseek-ai/deepseek-harness（dsh） | 0.2.2-rc.1 | `fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159` |
| TencentCloud/Octop | 1.0.3b4 | `4022e24ef6c34e9716350872b0e93bfaae7a20c3` |

## 补丁清单

| 编号 | 位置 | 类型 | 理由 | 影响面 |
|---|---|---|---|---|
| P-01 | `node_modules/node-addon-require-builtin/lib/index.js` | 依赖替换适配（Android 平台分支） | 该原生绑定只发布 darwin/linux-gnu/linux-musl/win32 预编译包，**没有 android 目标**；App 启动 profile 解析时必需。绑定唯一职责是通过未公开的内部模块句柄取 `internal/modules/*`，而 Node 以 `--expose-internals` 启动时普通 `require()` 等价可用。 | 仅当原生绑定加载失败时降级；设置 `DSH_NATIVE_BINDING_STRICT=1` 可恢复抛错。启动命令行需带 `--expose-internals`（见 `runtime-host` 的 `runDsh()`）。若将来上游发布 android 预编译包，删除本补丁即可。 |

## 未打补丁但需要环境注入的项（不算改代码）

| 项 | 注入值 | 原因 |
|---|---|---|
| `HOME` | `filesDir/home` | 上游按 `~/.dsh` 存 profile/日志，Android 无可用家目录 |
| `TMPDIR` | `filesDir/tmp` | Termux Node 的 `os.tmpdir()` 默认写死 `/data/data/com.termux/files/usr/tmp`，App 无权访问；`spill-local` 插件用 `mkdtemp` 会失败 |
| `LD_LIBRARY_PATH` | `nativeLibraryDir:files/runtime/lib` | 运行时二进制与兼容库都在私有目录 |

## 变更流程

1. 改动必须落到 `patches/<上游>/<包名+版本>.patch`（或同结构的完整替换文件）。
2. 在 `scripts/stage-dsh-runtime.ps1` 之类的装机脚本里自动应用。
3. 在本文件登记理由与影响面，并在 `docs/STATE.md` 技术债里加一行。
