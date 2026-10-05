"""선택된 모션 보드의 주요 시점을 한 줄 스트립 PNG로 찍는다.

python3 capture_motion_strips.py
design/handoff/interactions/reference/boards/의 보드를 띄워 reference/strips/*.png를 다시 만든다.
애니메이션을 멈추고 animation-delay를 음수로 줘서 원하는 시점의 프레임을 찍고, 손가락 표시 원은 숨긴다.
"""
import glob, os, shutil, subprocess, tempfile, threading
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
REF = os.path.join(HERE, "..", "handoff", "interactions", "reference")
BOARDS = os.path.join(REF, "boards")
OUT = os.path.join(REF, "strips")
CH = sorted(glob.glob(os.path.expanduser("~/Library/Caches/ms-playwright/chromium_headless_shell-*/*/chrome-headless-shell")))[-1]


class Q(SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


STRIPS = {
    "sheet-open": ("MotionSheetB", "바텀시트 열기 (480ms 스프링)", 700, [0, 80, 160, 240, 320, 400, 480]),
    "sheet-close": ("MotionSheetB", "바텀시트 닫기 (260ms 가속)", 2800, [0, 65, 130, 195, 260]),
    "push-photo": ("MotionPushB", "화면 이동 · 사진 있음 (420ms)", 700, [0, 70, 140, 210, 280, 350, 420]),
    "push-photo-back": ("MotionPushB", "화면 이동 · 뒤로 (360ms)", 3100, [0, 90, 180, 270, 360]),
    "push-surface": ("MotionPushPlaceholder", "화면 이동 · 사진 없음 (떠오름 80ms + 커짐 420ms)", 700, [0, 40, 80, 185, 290, 395, 500]),
    "push-surface-back": ("MotionPushPlaceholder", "화면 이동 · 사진 없음 뒤로 (360ms + 내려앉음 80ms)", 3100, [0, 90, 180, 270, 360, 440]),
    "tab": ("MotionTabC", "탭 전환 · 페이드 스루 (90ms → 210ms)", 900, [0, 45, 90, 160, 230, 300]),
    "review-card": ("MotionCardA", "연속 처리 카드 · 확정 (340ms, 뒤 카드 40ms 뒤 300ms)", 1100, [0, 85, 170, 255, 340]),
    "fill-next": ("MotionFillA", "정보 보완 넘김 (320ms)", 1100, [0, 40, 80, 160, 240, 320]),
    "share-card": ("MotionShareA", "공유 저장 카드 · 나타남 340ms / 사라짐 260ms", 500, [0, 85, 170, 340, 1500, 1630, 1760]),
}


def main():
    work = tempfile.mkdtemp()
    frames = os.path.join(work, "frames")
    os.makedirs(frames)
    srv = ThreadingHTTPServer(("127.0.0.1", 0), partial(Q, directory=BOARDS))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    port = srv.server_address[1]
    for key, (board, title, t0, offs) in STRIPS.items():
        src = open(os.path.join(BOARDS, board + ".dc.html"), encoding="utf-8").read()
        cells = []
        for off in offs:
            ms = t0 + off
            s = src.replace("</style>\n</helmet>", f"*{{animation-play-state:paused !important;animation-delay:-{ms}ms !important}} span[style*=\"z-index: 90\"]{{display:none !important}}\n</style>\n</helmet>")
            name = f"_strip_{key}_{off}.dc.html"
            tmp = os.path.join(BOARDS, name)
            open(tmp, "w", encoding="utf-8").write(s)
            png = os.path.join(frames, f"{key}_{off}.png")
            subprocess.run([CH, "--window-size=390,844", "--force-device-scale-factor=1", "--virtual-time-budget=3000", "--hide-scrollbars",
                            f"--screenshot={png}", f"http://127.0.0.1:{port}/{name}"], capture_output=True)
            os.remove(tmp)
            cells.append(f"<figure style='margin:0'><img src='frames/{key}_{off}.png' width=195 height=422 style='display:block;border:1px solid #DDD;border-radius:12px'><figcaption style='margin-top:6px;font:600 13px sans-serif;text-align:center'>+{off}ms</figcaption></figure>")
        html = os.path.join(work, key + ".html")
        open(html, "w", encoding="utf-8").write(f"<body style='margin:0;padding:16px;background:#fff;color:#1D1D1D'><div style='font:700 16px sans-serif;margin-bottom:12px'>{title}</div><div style='display:flex;gap:12px'>{''.join(cells)}</div></body>")
        subprocess.run([CH, f"--window-size={len(offs) * 207 + 28},500", "--hide-scrollbars", f"--screenshot={os.path.join(OUT, key + '.png')}", "file://" + html], capture_output=True)
        print(key)
    srv.shutdown()
    shutil.rmtree(work)


if __name__ == "__main__":
    main()
