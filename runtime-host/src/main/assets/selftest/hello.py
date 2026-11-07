# M1 自检脚本：验证从 APK lib/ 启动的 CPython 能工作。
# 额外验证 C 扩展（lib-dynload）能否从 filesDir 被 dlopen —— 这是内核依赖的关键前提。
import http.server
import os
import platform
import socket
import sys
import threading
import urllib.request

base = os.environ.get("HOME", os.getcwd())
path = os.path.join(base, "selftest-python-%d.txt" % os.getpid())

print("PYTHON_VERSION=%s" % sys.version.replace("\n", " "))
print("PYTHON_IMPL=%s" % platform.python_implementation())
print("PYTHON_MACHINE=%s" % platform.machine())
print("PYTHON_HOME=%s" % base)
print("PYTHON_PREFIX=%s" % sys.prefix)
print("PYTHON_EXECUTABLE=%s" % sys.executable)

with open(path, "w", encoding="utf-8") as handle:
    handle.write("hello-from-python\n")
with open(path, "r", encoding="utf-8") as handle:
    back = handle.read().strip()
print("FILE_WRITE_READ=%s" % back)
print("FILE_SIZE=%d" % os.path.getsize(path))

# C 扩展加载检查：失败不致命，但要如实打印，供 M1 结论取证。
for name in ("sqlite3", "ssl", "zlib", "hashlib", "_json", "select", "ctypes"):
    try:
        __import__(name)
        print("C_EXT_%s=ok" % name)
    except Exception as error:  # noqa: BLE001
        print("C_EXT_%s=FAILED:%s" % (name, error))


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
    sys.exit(1)
finally:
    server.server_close()
