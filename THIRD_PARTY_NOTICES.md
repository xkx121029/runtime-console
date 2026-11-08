# 第三方软件声明（THIRD_PARTY_NOTICES）

本 App（下称"本应用"）是一个**本地运行时宿主**：它在 Android 设备上启动两个第三方开源服务，
并在应用内 WebView 中展示它们的界面。本应用不包含这两个项目的完整源码副本，
但会分发它们的**已构建产物**与**依赖树**，因此在此逐项声明。

分发形态：

| 形态 | 内容 | 位置 |
|---|---|---|
| APK 内 | Node.js 与 CPython 运行时（Termux 构建）、Python 标准库、兼容原生库 | `runtime-host/src/main/jniLibs`、`runtime-host/src/main/assets` |
| 运时分包（首次导入） | Node 侧 `node_modules`、Python 侧 `python-site` | 见 `docs/DISTRIBUTION.md` |

---

## 1. 上游应用

### 1.1 deepseek-harness（dsh）0.2.2-rc.1

- 来源：https://github.com/deepseek-ai/deepseek-harness ，固定提交 `fcc0ad462172522aaf5c93a7b2e01f6b9f0b0159`
- 许可证：MIT
- 版权：Copyright (c) 2026 DeepSeek
- 本应用对它的改动：仅一个运行时适配补丁 `patches/dsh/node-addon-require-builtin+0.1.9.patch`
  （见 `docs/UPSTREAM.md` P-01），不改业务逻辑。

### 1.2 Octop 1.0.3b4

- 来源：https://github.com/TencentCloud/Octop ，固定提交 `4022e24ef6c34e9716350872b0e93bfaae7a20c3`
- 许可证：MIT
- 版权：Copyright (c) 2026 Octop
- 本应用对它的改动：无源码改动；仅通过启动脚本注入 `OCTOP_HOME` 等环境变量。

### 1.3 两个上游共同的 MIT 许可全文

```
MIT License

Copyright (c) 2026 DeepSeek
Copyright (c) 2026 Octop

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. 语言运行时

| 组件 | 版本 | 构建来源 | 许可证 |
|---|---|---|---|
| Node.js | v26.13.0 | Termux 的 `nodejs` 包 | MIT |
| CPython | 3.14.10 | Termux 的 `python` 包 | PSF-2.0 |
| Python 标准库 | 3.14.10 | 同上 | PSF-2.0 |

Node.js 与 CPython 的完整许可证文本随其官方发行版提供；本应用未修改其源码，
仅把可执行文件改名为 `libnode_exec.so` / `libpython_exec.so` 以符合 Android 对
`nativeLibraryDir` 的装载要求（见 `docs/STATE.md` M1）。

---

## 3. 原生库（Termux 构建，随 APK assets 分发）

| 库 | 版本 | 许可证 |
|---|---|---|
| libffi | 3.8.0 | MIT |
| libiconv | 1.19.1 | LGPL-2.1-or-later |
| libxml2 | 2.15.4 | MIT |
| libxslt / libexslt | 1.1.45 | MIT |
| libtiff | 4.7.2 | libtiff（BSD 风格） |
| libjpeg-turbo | 3.2.1 | IJG / BSD-3-Clause |
| openjpeg | 2.5.4 | BSD-2-Clause |
| libimagequant | 4.4.1 | **GPL-3.0-or-later（或商业许可）**，见第 6 节 |
| libxcb | 1.17.0 | MIT |
| libwebp | 1.6.0 | BSD-3-Clause |
| libxau | 1.0.12 | MIT |
| libxdmcp | 1.1.5 | MIT |
| libsqlite3 | Termux 版 | Public Domain |

---

## 4. Python 依赖（`python-site`，共 155 个发行包）

版本与许可证取自每个包的 `*.dist-info/METADATA`（`License:` 字段可能为空，
此时以该包自带的 `LICENSE*` 文件或 PyPI 元数据为准）。

- `acme-5.9.0` — 未在 METADATA 中声明
- `aiohappyeyeballs-2.7.1` — PSF-2.0
- `aiohttp-3.14.5` — 未在 METADATA 中声明
- `aiosignal-1.4.0` — Apache 2.0
- `aiosqlite-0.22.1` — 未在 METADATA 中声明
- `annotated_doc-0.0.5` — 未在 METADATA 中声明
- `annotated_types-0.8.0` — 未在 METADATA 中声明
- `anthropic-2.6.0` — Apache-2.0
- `anyio-4.16.0` — 未在 METADATA 中声明
- `apscheduler-3.11.3` — MIT
- `argon2_cffi-25.1.0` — 未在 METADATA 中声明
- `argon2_cffi_bindings-25.1.0` — 未在 METADATA 中声明
- `attrs-26.1.0` — 未在 METADATA 中声明
- `beautifulsoup4-4.15.0` — MIT License
- `bidict-0.24.1` — 未在 METADATA 中声明
- `boto3-1.43.126` — Apache-2.0
- `botocore-1.43.126` — Apache-2.0
- `bracex-3.2.2` — 未在 METADATA 中声明
- `cbor2-5.9.0` — 未在 METADATA 中声明
- `certifi-2026.10.23` — MPL-2.0
- `cffi-2.0.0` — 未在 METADATA 中声明
- `charset_normalizer-3.5.2` — MIT
- `click-8.5.1` — 未在 METADATA 中声明
- `croniter-6.2.4` — 未在 METADATA 中声明
- `cryptography-50.0.2` — 未在 METADATA 中声明
- `deepagents-0.7.34` — MIT
- `dingtalk_stream-0.24.3` — MIT
- `discord_py-2.7.1` — The MIT License (MIT)
- `distro-1.9.0` — Apache License, Version 2.0
- `docstring_parser-0.18.0` — MIT
- `edge_tts-7.3.1` — 未在 METADATA 中声明
- `et_xmlfile-2.0.0` — MIT
- `exceptiongroup-1.3.1` — 未在 METADATA 中声明
- `fastapi-0.162.2` — 未在 METADATA 中声明
- `filetype-1.2.0` — MIT
- `firecrawl_anydoc-0.2.4` — 未在 METADATA 中声明
- `frozenlist-1.8.0` — Apache-2.0
- `google_genai-3.0.0` — 未在 METADATA 中声明
- `h11-0.16.0` — MIT
- `httpcore-1.0.9` — 未在 METADATA 中声明
- `httpcore2-2.16.0` — 未在 METADATA 中声明
- `httpx-0.28.1` — BSD-3-Clause
- `httpx2-2.16.0` — 未在 METADATA 中声明
- `httpx_sse-0.4.3` — MIT
- `idna-3.20` — 未在 METADATA 中声明
- `itsdangerous-2.2.0` — 未在 METADATA 中声明
- `jinja2-3.1.6` — 未在 METADATA 中声明
- `jiter-0.19.0` — 未在 METADATA 中声明
- `jmespath-1.1.0` — MIT
- `josepy-2.2.0` — 未在 METADATA 中声明
- `jsonpatch-1.44` — 未在 METADATA 中声明
- `jsonpointer-3.10` — Modified BSD License
- `jsonschema-4.26.0` — 未在 METADATA 中声明
- `jsonschema_specifications-2025.9.1` — 未在 METADATA 中声明
- `langchain-1.4.9` — MIT
- `langchain_anthropic-1.7.10` — MIT
- `langchain_core-1.7.6` — MIT
- `langchain_google_genai-4.4.3` — MIT
- `langchain_mcp_adapters-0.3.2` — 未在 METADATA 中声明
- `langchain_openai-1.7.4` — MIT
- `langchain_protocol-0.0.21` — MIT
- `langgraph-1.2.20` — 未在 METADATA 中声明
- `langgraph_checkpoint-4.3.1` — 未在 METADATA 中声明
- `langgraph_checkpoint_sqlite-3.1.3` — 未在 METADATA 中声明
- `langgraph_prebuilt-1.1.1` — 未在 METADATA 中声明
- `langgraph_sdk-0.4.14` — 未在 METADATA 中声明
- `langsmith-0.14.17` — MIT
- `lark_oapi-1.7.5` — MIT
- `ldap3-2.9.1` — LGPL v3
- `lxml-6.1.3` — BSD-3-Clause
- `markdown_it_py-4.2.0` — 未在 METADATA 中声明
- `markdownify-1.2.3` — 未在 METADATA 中声明
- `mcp-1.30.0` — MIT
- `mcp_types-2.3.0` — MIT
- `mdurl-0.1.2` — 未在 METADATA 中声明
- `multidict-7.2.1` — 未在 METADATA 中声明
- `octop-1.0.3b4` — MIT
- `octop_browser-1.0.0` — MIT
- `octop_gateway-1.0.3` — MIT
- `octop_harness-1.0.8` — MIT
- `octop_memory-1.0.3` — MIT
- `openai-3.46.0` — 未在 METADATA 中声明
- `openpyxl-3.1.5` — MIT
- `opentelemetry_api-1.45.2` — 未在 METADATA 中声明
- `orjson-3.13.0` — 未在 METADATA 中声明
- `ormsgpack-1.11.0` — 未在 METADATA 中声明
- `ormsgpack-1.13.0` — 未在 METADATA 中声明
- `packaging-26.3` — 未在 METADATA 中声明
- `paho_mqtt-2.2.0` — 未在 METADATA 中声明
- `pillow-13.0.0` — 未在 METADATA 中声明
- `prompt_toolkit-3.0.53` — 未在 METADATA 中声明
- `propcache-0.5.4` — Apache-2.0
- `pyasn1-0.6.5` — BSD-2-Clause
- `pyasn1_modules-0.4.2` — BSD
- `pycparser-3.12` — 未在 METADATA 中声明
- `pycryptodomex-3.24.2` — BSD, Public Domain
- `pydantic-2.13.4` — 未在 METADATA 中声明
- `pydantic-2.14.3` — 未在 METADATA 中声明
- `pydantic_core-2.46.5` — 未在 METADATA 中声明
- `pydantic_core-2.50.4` — 未在 METADATA 中声明
- `pydantic_settings-2.16.2` — 未在 METADATA 中声明
- `pygments-2.21.0` — 未在 METADATA 中声明
- `pyjwt-2.15.1` — 未在 METADATA 中声明
- `pyopenssl-26.4.0` — Apache License, Version 2.0
- `pyotp-2.10.0` — 未在 METADATA 中声明
- `pypdf-7.0.0` — 未在 METADATA 中声明
- `pypinyin-0.55.0` — MIT
- `pyrfc3339-2.1.1` — 未在 METADATA 中声明
- `python_dateutil-4.0.0` — 未在 METADATA 中声明
- `python_docx-1.2.0` — MIT
- `python_dotenv-1.2.4` — BSD-3-Clause
- `python_engineio-4.15.1` — 未在 METADATA 中声明
- `python_multipart-0.0.33` — 未在 METADATA 中声明
- `python_pptx-1.0.2` — MIT
- `python_socketio-5.18.1` — 未在 METADATA 中声明
- `python_telegram_bot-22.8` — 未在 METADATA 中声明
- `pyyaml-6.0.3` — MIT
- `questionary-2.1.1` — MIT
- `referencing-0.37.0` — 未在 METADATA 中声明
- `regex-2026.11.7` — 未在 METADATA 中声明
- `requests-2.34.2` — Apache-2.0
- `requests_toolbelt-1.0.0` — Apache 2.0
- `rich-15.0.0` — MIT
- `rpds_py-2026.9.1` — 未在 METADATA 中声明
- `s3transfer-0.19.3` — Apache License 2.0
- `scalar_fastapi-1.9.1` — MIT
- `segno-1.6.6` — 未在 METADATA 中声明
- `six-1.17.0` — MIT
- `sniffio-1.3.1` — MIT OR Apache-2.0
- `soupsieve-4.0.1` — 未在 METADATA 中声明
- `sqlalchemy-2.1.8` — 未在 METADATA 中声明
- `sse_starlette-3.7.0` — 未在 METADATA 中声明
- `starlette-1.10.0` — 未在 METADATA 中声明
- `tabulate-0.10.0` — 未在 METADATA 中声明
- `tenacity-9.2.1` — 未在 METADATA 中声明
- `tencentcloud_sdk_python-3.1.204` — Apache License 2.0
- `truststore-0.10.5` — 未在 METADATA 中声明
- `typing_extensions-4.16.0` — 未在 METADATA 中声明
- `typing_inspection-0.4.5` — 未在 METADATA 中声明
- `tzdata-2026.6` — Apache-2.0
- `tzlocal-5.4.5` — 未在 METADATA 中声明
- `urllib3-2.8.0` — 未在 METADATA 中声明
- `uuid_utils-0.19.0` — 未在 METADATA 中声明
- `uvicorn-0.54.0` — 未在 METADATA 中声明
- `wcmatch-12.0` — 未在 METADATA 中声明
- `wcwidth-0.9.4` — 未在 METADATA 中声明
- `webauthn-3.0.1` — 未在 METADATA 中声明
- `websocket_client-1.9.2` — Apache-2.0
- `websockets-17.4` — 未在 METADATA 中声明
- `wecom_aibot_sdk-1.0.8` — 未在 METADATA 中声明
- `xlrd-2.0.2` — BSD
- `xlsxwriter-3.3.1` — 未在 METADATA 中声明
- `xxhash-4.0.2` — BSD-2-Clause
- `yarl-1.25.1` — Apache-2.0
- `zstandard-0.25.0` — 未在 METADATA 中声明

---

## 5. Node 依赖（`node_modules`，共 580 个包）

版本与 `license` 字段取自每个包的 `package.json`。

- `@agentclientprotocol/sdk@1.4.0 - Apache-2.0`
- `@anthropic-ai/sdk@0.129.0 - MIT`
- `@aws/lambda-invoke-store@0.3.0 - Apache-2.0`
- `@aws-sdk/client-bedrock-runtime@3.1127.0 - Apache-2.0`
- `@aws-sdk/core@3.980.1 - Apache-2.0`
- `@aws-sdk/credential-provider-env@3.972.82 - Apache-2.0`
- `@aws-sdk/credential-provider-http@3.972.84 - Apache-2.0`
- `@aws-sdk/credential-provider-ini@3.973.27 - Apache-2.0`
- `@aws-sdk/credential-provider-login@3.972.89 - Apache-2.0`
- `@aws-sdk/credential-provider-node@3.972.94 - Apache-2.0`
- `@aws-sdk/credential-provider-process@3.972.82 - Apache-2.0`
- `@aws-sdk/credential-provider-sso@3.973.26 - Apache-2.0`
- `@aws-sdk/credential-provider-web-identity@3.972.88 - Apache-2.0`
- `@aws-sdk/eventstream-handler-node@3.972.40 - Apache-2.0`
- `@aws-sdk/middleware-eventstream@3.972.36 - Apache-2.0`
- `@aws-sdk/middleware-websocket@3.972.64 - Apache-2.0`
- `@aws-sdk/nested-clients@3.998.1 - Apache-2.0`
- `@aws-sdk/signature-v4-multi-region@3.996.51 - Apache-2.0`
- `@aws-sdk/token-providers@3.1127.0 - Apache-2.0`
- `@aws-sdk/types@3.975.0 - Apache-2.0`
- `@aws-sdk/xml-builder@3.972.42 - Apache-2.0`
- `@babel/code-frame@7.29.7 - MIT`
- `@babel/helper-validator-identifier@7.29.7 - MIT`
- `@babel/runtime@7.29.13 - MIT`
- `@deepseek-ai/cordis@4.1.0 - MIT`
- `@deepseek-ai/cordis-plugin-group@1.1.0 - MIT`
- `@deepseek-ai/cordis-plugin-include@1.1.0 - MIT`
- `@deepseek-ai/cordis-plugin-loader@1.1.0 - MIT`
- `@deepseek-ai/cordis-plugin-timer@1.2.0 - MIT`
- `@deepseek-ai/cosmokit@1.9.0 - MIT`
- `@deepseek-ai/dsh@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-acp@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-acp-app@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-action-cards@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-agent@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-agent-default-model@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-agent-instructions@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-agent-loop@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-agent-preset@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-agent-preset-registry@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-agent-tool-presentation@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-anonymous-user-id@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-api-account-controller@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-api-gateway@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-api-job-controller@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-api-remote-access@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-api-remotes@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-api-session-controller@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-api-settings-controller@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-api-terminal-controller@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-api-workspace-controller@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-api-workspace-files@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-app-boot@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-atomic-write@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-attachment@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-attachment-local@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-authorization@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-base@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-bash-local@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-bash-sandbox@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-brand@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-chunked-list@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-connection@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-file-upload@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-hmr@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-locale@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-modules@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-product-analytics@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-resources@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-shortcuts@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-action-cards@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-agent-preset@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-approval@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-attachment@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-brand-official@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-chat@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-commands@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-context-menu@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-conversation@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-cordis@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-creation-recommendations@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-deliverables@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-directory-picker-browse@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-directory-picker-native@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-entry-customization@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-file-actions@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-file-drag@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-goal@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-input-trigger@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-jobs@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-layout@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-message-feedback@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-model-selection@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-open-in-app@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-permission-presets@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-plan@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-plugin-manager@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-plugin-market@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-plugin-recommendations@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-reference@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-renderer@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-schedule@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-secret-mask@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-session@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-settings@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-settings-account@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-settings-agent-loop@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-settings-general@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-settings-models@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-settings-plugin-inventory@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-settings-plugins@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-settings-session-log@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-settings-shell@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-settings-subagent@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-settings-web-search@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-shortcuts@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-sidebar@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-sidebar-browser@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-sidebar-documentpreview@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-sidebar-files@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-sidebar-right@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-sidebar-terminal@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-skill@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-subagent@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-theme@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-tool@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-trajectory@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-user-questions@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-workflow-run@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-client-ui-workspace@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-cmdline@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-command-compact@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-command-feedback@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-command-goal@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-commands@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-compaction@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-compaction-basic@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-compaction-image-offload@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-compaction-tool-result-pruner@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-config-editor@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-cordis-client-runner@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-cordis-host-runner@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-creation-recommendations@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-credentials@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-credentials-local@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-deepseek-account@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-deepseek-account-platform@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-deepseek-llm-api-extensions@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-deliverable-file-references@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-deque@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-agent-team@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-agent-team-profile@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-api-speech-to-text@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-auto-review@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-badge-skill-bundle@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-client-ui-agent-team@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-client-ui-cot-translation@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-client-ui-forge@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-client-ui-stats@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-client-ui-voice-input@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-cot-translation-bundle@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-forge@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-forge-bundle@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-forge-github@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-forge-session@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-inspector@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-inspector-profile@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-liveshare@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-liveshare-protocol@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-plugin-market-bundle@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-ralph-bundle@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-session-inspector@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-session-search@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-session-titles-bundle@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-speech-to-text@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-speech-to-text-sensevoice@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-terminal-bundle@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-tool-agent-team@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-tool-worktree@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-translator@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-voice-input-bundle@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-experimental-worktree@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-file-reference@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-file-reference-local@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-fs@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-fs-local@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-fs-observation-policy@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-fs-patch@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-fs-sandbox@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-goal@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-goal-round-driver@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-harness-dev-context@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-headless@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-hmr@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-home-paths@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-host-directory-picker@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-host-directory-picker-auto@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-host-directory-picker-browse@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-host-directory-picker-native@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-host-frontend-static@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-host-open-in-app@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-host-plugin-inventory@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-host-product-telemetry-otel@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-host-webserver@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-http-proxy@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-jobs@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-jobs-local@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-launch-environment@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-lazy-require@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-llm@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-llm-deepseek@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-llm-deepseek-account@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-llm-deepseek-api-key@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-llm-pi-ai@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-llm-retry@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-mcp-client@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-mcp-resources@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-message-feedback@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-message-quotes@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-native-command@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-office-to-pdf@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-official-bundles@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-otel@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-output-retention@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-package-manifest@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-permission-presets@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-persona@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-personalized-recommendations@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-plan-mode@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-plugin-detail@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-plugin-manager@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-plugin-market@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-plugin-market-index@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-plugin-package-inventory-deepseek@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-plugin-recommendations@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-ptc-runtime@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-ptc-runtime-node@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-pwsh-local@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-pwsh-sandbox@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-repeat-tool-reminder@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-sandbox@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-sandbox-local@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-sandbox-policy@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-sandbox-windows-acl@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-schedule@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-scope@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-sdk-app@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-sdk-jsonrpc-server@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-sdk-minimal@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-sdk-protocol@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-checkpoint-policy@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-format@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-format-catalog@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-format-v0-to-v1@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-format-v1-to-v2@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-format-v2-to-v3@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-format-v3-to-v4@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-log-deepseek@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-log-export@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-persistence@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-persistence-jsonl@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-projection@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-projection-cache@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-query@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-query-sqlite@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-reference@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-stats@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-telemetry@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-telemetry-otel@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-title@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-title-all-prompts-llm@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-title-first-prompt-llm@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-title-llm@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-session-turn-outline@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-settings@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-shell@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-shell-env@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-skill@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-skill-badge@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-skill-filesystem@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-skill-office@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-spill@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-spill-local@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-spill-policy@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-storage@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-storage-domain@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-storage-json@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-subagent@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-subagent-fork-in-process@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-subagent-spawn-in-process@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-subprocess@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-subprocess-local@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-system-prompt@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-terminal@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-terminal-bash@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-time-context@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-timeout@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tmux-context@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-token-meter@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-ask-user@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-bash@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-bash-persistent@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-call-timeout-policy@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-cordis@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-executor-native@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-executor-ptc@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-fs@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-fs-search@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-goal@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-jobs@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-present@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-pwsh@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-pwsh-persistent@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-ralph@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tools@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-schedule@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-session-query@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-skill@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-str-replace-editor@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-subagent@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-subagent-control@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-terminal@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-todo@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-web@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-workflow@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-working-directory@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-tool-workspace-dependencies@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-typert-loader@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-typert-protocol@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-typert-registry@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-user-approval@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-user-questions@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-util-code-language@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-util-crypto@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-util-time@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-util-values@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-util-workspace-path@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-web@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-web-app@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-web-deepseek-api@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-web-fetch-http@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-web-frontend@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-web-search-deepseek@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-win32-process@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-workflow@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-workflow-ptc@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-working-directory@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-workspace@0.2.2-rc.1 - MIT`
- `@deepseek-ai/dsh-workspace-changes@0.2.2-rc.1 - MIT`
- `@deepseek-ai/libreoffice-kit@0.1.5 - MPL-2.0`
- `@deepseek-ai/libreoffice-kit-wasm@0.1.5 - MPL-2.0`
- `@deepseek-ai/node-addon-system@0.1.2 - BSD-3-Clause`
- `@deepseek-ai/node-addon-system-linux-x64@0.1.2 - BSD-3-Clause`
- `@deepseek-ai/schemastery@3.19.0 - MIT`
- `@earendil-works/pi-ai@1.5.0 - MIT`
- `@earendil-works/pi-schema@1.5.0 - MIT`
- `@earendil-works/pi-telemetry@1.5.0 - MIT`
- `@emnapi/runtime@1.11.3 - MIT`
- `@eslint-community/regexpp@4.12.2 - MIT`
- `@google/genai@2.21.0 - Apache-2.0`
- `@img/colour@1.1.0 - MIT`
- `@img/sharp-wasm32@0.35.5 - Apache-2.0 AND LGPL-3.0-or-later AND MIT`
- `@joplin/turndown-plugin-gfm@1.0.68 - MIT`
- `@js-temporal/polyfill@0.5.1 - ISC`
- `@keyv/serialize@1.1.1 - MIT`
- `@koromix/koffi-linux-x64@3.1.1 - MIT`
- `@mixmark-io/domino@2.2.0 - BSD-2-Clause`
- `@modelcontextprotocol/client@2.2.0 - MIT`
- `@modelcontextprotocol/core@2.2.0 - MIT`
- `@opentelemetry/api@1.9.1 - Apache-2.0`
- `@opentelemetry/api-logs@0.222.0 - Apache-2.0`
- `@opentelemetry/core@2.11.0 - Apache-2.0`
- `@opentelemetry/otlp-exporter-base@0.222.0 - Apache-2.0`
- `@opentelemetry/otlp-transformer@0.222.0 - Apache-2.0`
- `@opentelemetry/resources@2.11.0 - Apache-2.0`
- `@opentelemetry/sdk-logs@0.222.0 - Apache-2.0`
- `@opentelemetry/sdk-metrics@2.11.0 - Apache-2.0`
- `@opentelemetry/sdk-trace@2.11.0 - Apache-2.0`
- `@opentelemetry/semantic-conventions@1.45.0 - Apache-2.0`
- `@protobufjs/aspromise@1.1.2 - BSD-3-Clause`
- `@protobufjs/base64@1.1.2 - BSD-3-Clause`
- `@protobufjs/codegen@2.0.5 - BSD-3-Clause`
- `@protobufjs/eventemitter@1.1.1 - BSD-3-Clause`
- `@protobufjs/fetch@1.1.1 - BSD-3-Clause`
- `@protobufjs/float@1.0.2 - BSD-3-Clause`
- `@protobufjs/path@1.1.2 - BSD-3-Clause`
- `@protobufjs/pool@1.1.0 - BSD-3-Clause`
- `@protobufjs/utf8@1.1.2 - BSD-3-Clause`
- `@sec-ant/readable-stream@0.4.1 - MIT`
- `@sindresorhus/is@7.2.0 - MIT`
- `@sindresorhus/merge-streams@4.0.0 - MIT`
- `@smithy/core@3.39.0 - Apache-2.0`
- `@smithy/credential-provider-imds@4.5.2 - Apache-2.0`
- `@smithy/fetch-http-handler@5.8.1 - Apache-2.0`
- `@smithy/node-http-handler@4.12.1 - Apache-2.0`
- `@smithy/signature-v4@5.7.5 - Apache-2.0`
- `@smithy/types@4.20.0 - Apache-2.0`
- `@stablelib/base64@1.0.1 - MIT`
- `@standard-schema/spec@1.1.0 - MIT`
- `@swc/helpers@0.5.24 - Apache-2.0`
- `@types/http-cache-semantics@4.2.0 - MIT`
- `@types/node@26.6.6 - MIT`
- `@types/retry@0.12.0 - MIT`
- `@vscode/ripgrep@1.18.0 - MIT`
- `@vscode/ripgrep-linux-x64@1.18.0 - MIT`
- `@xterm/addon-serialize@0.14.0 - MIT`
- `@xterm/headless@6.0.0 - MIT`
- `acorn@8.19.0 - MIT`
- `acorn-walk@8.3.5 - MIT`
- `agent-base@9.0.0 - MIT`
- `ajv@8.20.0 - MIT`
- `argparse@2.0.1 - Python-2.0`
- `base64-js@1.5.1 - MIT`
- `bignumber.js@9.3.1 - MIT`
- `birpc@4.2.0 - MIT`
- `bowser@2.15.0 - MIT`
- `brotli@1.3.3 - MIT`
- `buffer-equal-constant-time@1.0.1 - BSD-3-Clause`
- `bundle-name@4.1.1 - MIT`
- `byte-counter@0.1.0 - MIT`
- `bytes@3.1.2 - MIT`
- `cacheable-lookup@7.0.0 - MIT`
- `cacheable-request@13.0.19 - MIT`
- `chokidar@5.0.0 - MIT`
- `clone@2.1.2 - MIT`
- `commander@15.1.0 - MIT`
- `compressible@2.0.18 - MIT`
- `compression@1.8.2 - MIT`
- `content-type@2.1.0 - MIT`
- `cross-spawn@7.0.6 - MIT`
- `data-uri-to-buffer@4.0.1 - MIT`
- `debug@2.6.9 - MIT`
- `decompress-response@10.0.0 - MIT`
- `default-browser@5.5.1 - MIT`
- `default-browser-id@5.0.1 - MIT`
- `define-lazy-prop@3.0.0 - MIT`
- `depd@2.0.0 - MIT`
- `destroy@1.2.0 - MIT`
- `detect-libc@2.1.2 - Apache-2.0`
- `dfa@1.2.0 - MIT`
- `diff@9.0.0 - BSD-3-Clause`
- `ecdsa-sig-formatter@1.0.11 - Apache-2.0`
- `ee-first@1.1.1 - MIT`
- `encodeurl@2.0.1 - MIT`
- `escape-html@1.0.3 - MIT`
- `etag@1.8.1 - MIT`
- `eventsource@3.0.7 - MIT`
- `eventsource-parser@3.1.1 - MIT`
- `execa@10.2.1 - MIT`
- `extend@3.0.2 - MIT`
- `fast-deep-equal@3.1.3 - MIT`
- `fast-sha256@1.3.0 - Unlicense`
- `fast-uri@3.1.9 - BSD-3-Clause`
- `fetch-blob@3.2.0 - MIT`
- `fflate@0.8.3 - MIT`
- `figures@6.1.0 - MIT`
- `fontkit@2.0.4 - MIT`
- `form-data-encoder@4.1.0 - MIT`
- `formdata-polyfill@4.0.10 - MIT`
- `fresh@2.0.0 - MIT`
- `gaxios@7.3.1 - Apache-2.0`
- `gcp-metadata@8.1.2 - Apache-2.0`
- `get-stream@9.0.1 - MIT`
- `google-auth-library@10.9.1 - Apache-2.0`
- `google-logging-utils@1.1.3 - Apache-2.0`
- `got@14.6.6 - MIT`
- `hosted-git-info@9.0.3 - ISC`
- `http2-wrapper@2.2.4 - MIT`
- `http-cache-semantics@4.3.0 - BSD-2-Clause`
- `http-errors@2.0.1 - MIT`
- `http-proxy-agent@9.1.0 - MIT`
- `https-proxy-agent@9.1.0 - MIT`
- `human-signals@8.0.1 - Apache-2.0`
- `inherits@2.0.4 - ISC`
- `ipaddr.js@2.5.0 - MIT`
- `is-docker@3.0.0 - MIT`
- `isexe@2.0.0 - ISC`
- `is-inside-container@1.0.0 - MIT`
- `is-in-ssh@1.0.0 - MIT`
- `is-plain-obj@4.1.0 - MIT`
- `is-stream@4.0.1 - MIT`
- `is-unicode-supported@2.1.0 - MIT`
- `is-wsl@3.1.1 - MIT`
- `jose@6.2.15 - MIT`
- `jsbi@4.3.2 - Apache-2.0`
- `json-bigint@1.0.0 - MIT`
- `json-schema-to-ts@3.1.1 - MIT`
- `json-schema-traverse@1.0.0 - MIT`
- `js-tokens@4.0.0 - MIT`
- `js-yaml@4.3.2 - MIT`
- `jwa@2.0.1 - MIT`
- `jws@4.0.1 - MIT`
- `keyv@5.6.0 - MIT`
- `koffi@3.1.1 - MIT`
- `long@5.3.2 - Apache-2.0`
- `lowercase-keys@3.0.0 - MIT`
- `lru-cache@11.5.3 - BlueOak-1.0.0`
- `mime-db@1.54.0 - MIT`
- `mime-types@3.0.2 - MIT`
- `mimic-response@4.0.0 - MIT`
- `minisearch@7.2.0 - MIT`
- `ms@2.0.0 - MIT`
- `negotiator@1.1.0 - MIT`
- `node-addon-api@7.1.1 - MIT`
- `node-addon-native-custom-loader@0.1.9 - MIT`
- `node-addon-require-builtin@0.1.9 - MIT`
- `node-domexception@1.0.0 - MIT`
- `node-fetch@3.3.2 - MIT`
- `node-pty@1.2.0-beta.15 - MIT`
- `normalize-url@8.1.1 - MIT`
- `npm-run-path@6.0.0 - MIT`
- `on-finished@2.4.2 - MIT`
- `on-headers@1.1.0 - MIT`
- `open@11.0.4 - MIT`
- `openai@7.19.0 - Apache-2.0`
- `pako@0.2.9 - MIT`
- `parse-ms@4.0.0 - MIT`
- `parseurl@1.3.3 - MIT`
- `partial-json@0.1.7 - MIT`
- `path-key@3.1.1 - MIT`
- `p-cancelable@4.0.1 - MIT`
- `picocolors@1.1.1 - ISC`
- `picomatch@4.0.7 - MIT`
- `pkce-challenge@5.0.1 - MIT`
- `p-limit@7.3.3 - MIT`
- `powershell-utils@0.2.1 - MIT`
- `p-retry@4.6.2 - MIT`
- `pretty-ms@9.3.1 - MIT`
- `protobufjs@7.6.6 - BSD-3-Clause`
- `proxy-agent-negotiate@1.1.0 - MIT`
- `quick-lru@5.1.1 - MIT`
- `range-parser@1.3.0 - MIT`
- `readdirp@5.1.1 - MIT`
- `require-from-string@2.0.2 - MIT`
- `resolve.exports@2.0.4 - MIT`
- `resolve-alpn@1.2.1 - MIT`
- `responselike@4.0.2 - MIT`
- `restructure@3.0.2 - MIT`
- `retry@0.13.1 - MIT`
- `rrule@2.8.1 - BSD-3-Clause`
- `run-applescript@7.1.0 - MIT`
- `safe-buffer@5.2.1 - MIT`
- `saxes@6.0.0 - ISC`
- `semver@7.8.6 - ISC`
- `send@1.2.1 - MIT`
- `serve-static@2.2.1 - MIT`
- `setprototypeof@1.2.0 - ISC`
- `sharp@0.35.5 - Apache-2.0`
- `shebang-command@2.0.0 - MIT`
- `shebang-regex@3.0.0 - MIT`
- `shell-quote@1.12.0 - MIT`
- `sherpa-onnx-linux-x64@1.13.8 - Apache-2.0`
- `sherpa-onnx-node@1.13.8 - Apache-2.0`
- `signal-exit@4.1.0 - ISC`
- `standardwebhooks@1.1.1 - MIT`
- `statuses@2.0.2 - MIT`
- `strip-final-newline@4.0.0 - MIT`
- `tiny-inflate@1.0.3 - MIT`
- `toidentifier@1.0.1 - MIT`
- `ts-algebra@2.0.0 - MIT`
- `tslib@2.9.1 - 0BSD`
- `turndown@7.2.5 - MIT`
- `type-fest@4.41.0 - (MIT OR CC0-1.0)`
- `undici@8.11.2 - MIT`
- `undici-types@8.9.0 - MIT`
- `unicode-properties@1.4.1 - MIT`
- `unicode-trie@2.0.0 - MIT`
- `unicorn-magic@0.3.0 - MIT`
- `vary@1.1.2 - MIT`
- `web-streams-polyfill@3.3.3 - MIT`
- `which@2.0.2 - ISC`
- `which-command@0.1.0 - MIT`
- `ws@8.22.1 - MIT`
- `wsl-utils@1.0.1 - MIT`
- `xmlchars@2.2.0 - MIT`
- `yaml@2.9.1 - ISC`
- `yoctocolors@2.2.0 - MIT`
- `yocto-queue@1.2.2 - MIT`
- `zod@4.6.7 - MIT`

---

## 6. 品牌、标识与合规注意事项

1. **保留的上游标识**：为了能真正启动上游服务，代码里必须保留其包路径与数据目录名，
   例如 `node_modules/@deepseek-ai/dsh/lib/bin.js`、`files/home/dsh`、`/api/setup/status`。
   这些是**功能性标识**，不出现在应用名称、图标或用户可见的品牌文案中。
   用户可见的内核名称使用中性描述（「Web 控制台」「管理接口」）。
2. **本应用的名称与包名**为中性命名（`dev.runtimeconsole.app`），与两个上游无隶属关系，
   也未获得其商标授权；对外分发时请勿使用上游名称作为产品名。
3. **libimagequant 是 GPL-3.0（或商业许可）**：它由 Pillow 的图像量化路径使用。
   若本应用以非 GPL 兼容方式分发，需要二选一：
   - 取得 libimagequant 商业许可；
   - 或从依赖中移除该库并禁用 Pillow 的量化功能（`Image.quantize` 会失败）。
   当前状态：**随包分发，未取得商业许可** → 对外发布前必须处理，已记入 `docs/STATE.md` 技术债。
4. 若你二次分发本应用或其运时分包，请保留本文件与 `LICENSE`。
