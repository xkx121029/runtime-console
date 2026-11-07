# M0 设备侧探针：验证 Python 运行时在 Android 上可用。
# 用法：python3 hello.py
# 行为：打印版本 → 写文件 → 读回 → 在 127.0.0.1 起 HTTP 服务 → 自请求 → 退出。
import http.server
import os
import platform
import socket
import sys
import threading
import urllib.request

base = os.environ.get("POC_DIR", os.getcwd())
path = os.path.join(base, "hello-python-%d.txt" % os.getpid())

print("PYTHON_VERSION=%s" % sys.version.replace("\n", " "))
print("PYTHON_IMPL=%s" % platform.python_implementation())
print("PYTHON_MACHINE=%s" % platform.machine())
print("PYTHON_CWD=%s" % base)

with open(path, "w", encoding="utf-8") as handle:
    handle.write("hello-from-python\n")
with open(path, "r", encoding="utf-8") as handle:
    back = handle.read().strip()
print("FILE_WRITE_READ=%s" % back)
print("FILE_SIZE=%d" % os.path.getsize(path))


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        body = b"ok-from-python"
        self.send_response(200)
        self.send_header("content-type", "text/plain")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):  # 静默访问日志
        return


server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
port = server.server_address[1]
print("HTTP_LISTEN=127.0.0.1:%d" % port)
thread = threading.Thread(target=server.handle_request, daemon=True)
thread.start()

try:
    with urllib.request.urlopen("http://127.0.0.1:%d/ping" % port, timeout=5) as response:
        print("HTTP_SELF_FETCH=%d:%s" % (response.status, response.read().decode()))
except Exception as error:  # noqa: BLE001
    print("HTTP_SELF_FETCH=FAILED:%s" % error)
    sys.exitCode = 1
finally:
    server.server_close()
