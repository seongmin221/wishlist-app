"""모션 후보 보드(gen_motion.py)가 겹쳐 쓰는 상태별 화면을 2배 해상도로 찍는다.

python3 capture_motion_states.py → ./motion-img/*.png. 캔버스에는 project/motion/*.png로 올린다.
"""
import glob, os, shutil, subprocess, threading
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "handoff", "screens", "boards")
WORK = os.path.abspath("motion-work"); OUT = os.path.abspath("motion-img")
HIDE_NAV = 'nav[aria-label="하단 탭"]{visibility:hidden}'
HIDE_STACK = 'div[style*="height: 574px"]{visibility:hidden}'
JOBS = {
    "rf0": ("FHomeReviewFlowL", None, ""),
    "rf1": ("FHomeReviewFlowL", ("{ i: 0,", "{ i: 1,"), ""),
    "rf2": ("FHomeReviewFlowL", ("{ i: 0,", "{ i: 2,"), ""),
    "rfbg1": ("FHomeReviewFlowL", ("{ i: 0,", "{ i: 1,"), HIDE_STACK),
    "rfbg2": ("FHomeReviewFlowL", ("{ i: 0,", "{ i: 2,"), HIDE_STACK),
    "edit": ("FProductEditL", None, ""),
    "sheet": ("FProductPurposeSheetL", None, ""),
    "list": ("FCategoryListL", None, ""),
    "detail": ("FProductDetailL", None, ""),
    "home": ("FHomeL", None, HIDE_NAV),
    "cat": ("FCategoryHomeL", None, HIDE_NAV),
    "purpose": ("FPurposeHomeL", None, HIDE_NAV),
    "catnav": ("FCategoryHomeL", None, ""),
}

class Q(SimpleHTTPRequestHandler):
    def log_message(self, *a): pass

def main():
    shutil.rmtree(WORK, ignore_errors=True); os.makedirs(WORK); os.makedirs(OUT, exist_ok=True)
    shutil.copy(os.path.join(SRC, "support.js"), WORK)
    for key, (board, rep, css) in JOBS.items():
        s = open(os.path.join(SRC, board + ".dc.html")).read()
        if rep:
            assert rep[0] in s, (key, rep)
            s = s.replace(rep[0], rep[1], 1)
        if css:
            s = s.replace("</style>\n</helmet>", css + "\n</style>\n</helmet>", 1)
        open(os.path.join(WORK, key + ".dc.html"), "w").write(s)
    chrome = sorted(glob.glob(os.path.expanduser("~/Library/Caches/ms-playwright/chromium_headless_shell-*/*/chrome-headless-shell")))[-1]
    srv = ThreadingHTTPServer(("127.0.0.1", 0), partial(Q, directory=WORK))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    port = srv.server_address[1]
    for key in JOBS:
        out = os.path.join(OUT, key + ".png")
        subprocess.run([chrome, "--window-size=390,844", "--force-device-scale-factor=2", "--virtual-time-budget=5000",
                        "--hide-scrollbars", f"--screenshot={out}", f"http://127.0.0.1:{port}/{key}.dc.html"], check=True, capture_output=True)
        print(out)
    srv.shutdown()

main()
