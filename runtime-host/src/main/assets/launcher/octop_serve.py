"""Octop FastAPI 启动胶水（仅读环境变量并起 uvicorn，不含任何业务逻辑）。

由 RuntimeHost.stageLaunchers() 从 APK assets 落盘到 filesDir/runtime/launcher/。
环境变量：
    OCTOP_HOME  Octop 数据目录，默认 <filesDir>/octop-home
    OCTOP_PORT  监听端口，默认 18080，永远只绑 127.0.0.1
"""

import asyncio
import os
import time
from pathlib import Path


def main() -> None:
    home = Path(os.environ.get("OCTOP_HOME", str(Path.cwd() / "octop-home")))
    home.mkdir(parents=True, exist_ok=True)
    os.environ["OCTOP_HOME"] = str(home)
    port = int(os.environ.get("OCTOP_PORT", "18080"))

    started = time.time()
    from octop.infra.server import OctopServer
    from octop.api.app import build_app
    import uvicorn

    print("OCTOP_IMPORT_MS=%d" % int((time.time() - started) * 1000), flush=True)

    async def serve() -> None:
        server = OctopServer(home=home)
        await server.start()
        print("OCTOP_SERVER_MS=%d" % int((time.time() - started) * 1000), flush=True)
        app = build_app(server)
        print("OCTOP_APP_MS=%d routes=%d" % (int((time.time() - started) * 1000), len(app.routes)), flush=True)
        config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning")
        print("OCTOP_LISTEN=127.0.0.1:%d" % port, flush=True)
        await uvicorn.Server(config).serve()

    asyncio.run(serve())


if __name__ == "__main__":
    main()
