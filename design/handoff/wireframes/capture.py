"""manifest.json의 보드를 헤드리스 Chrome으로 띄워 shots/에 첫 상태 PNG를 남긴다.

사용: python3 capture.py [chrome-headless-shell 경로]
경로를 주지 않으면 PATH의 chrome-headless-shell, 없으면 Playwright 캐시를 찾는다.
"""
import glob
import json
import os
import shutil
import subprocess
import sys
import threading
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
BOARDS = os.path.join(HERE, "boards")
SHOTS = os.path.join(HERE, "shots")


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def find_chrome():
    if len(sys.argv) > 1:
        return sys.argv[1]
    found = shutil.which("chrome-headless-shell")
    if found:
        return found
    cached = glob.glob(os.path.expanduser(
        "~/Library/Caches/ms-playwright/chromium_headless_shell-*/*/chrome-headless-shell"))
    if cached:
        return sorted(cached)[-1]
    sys.exit("chrome-headless-shell을 찾지 못했다. 경로를 인자로 넘긴다.")


def main():
    chrome = find_chrome()
    manifest = json.load(open(os.path.join(HERE, "manifest.json")))
    targets = manifest["boards"] + [manifest["flowMap"]]
    os.makedirs(SHOTS, exist_ok=True)

    handler = partial(QuietHandler, directory=BOARDS)
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port = server.server_address[1]

    try:
        for b in targets:
            out = os.path.join(SHOTS, b["file"].replace(".dc.html", ".png"))
            subprocess.run([
                chrome, f"--window-size={b['width']},{b['height']}",
                "--virtual-time-budget=5000", "--hide-scrollbars",
                f"--screenshot={out}", f"http://127.0.0.1:{port}/{b['file']}",
            ], check=True, capture_output=True)
            print(out)
    finally:
        server.shutdown()


if __name__ == "__main__":
    main()
