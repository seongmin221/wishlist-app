"""`검토 · 화면 전환 모션` 페이지의 후보 보드를 만든다.

python3 gen_motion.py <out_root>
미정이던 모션 다섯 가지(시트 열고 닫기, 화면 이동, 탭 전환, 연속 처리 카드 넘김, 공유 저장 카드)를
후보마다 390×844 보드 하나로 그린다. 보드는 실제 화면 캡처(motion/*.png, 2배)를 겹쳐 CSS 애니메이션으로 반복 재생한다.
손가락 자리는 회색 원으로 보인다.
"""
import os
import sys

W, H = 390, 844
BG = "#F8F8F8"
IOS = "cubic-bezier(.32,.72,0,1)"
OUT_EASE = "cubic-bezier(.4,0,1,1)"
DECEL = "cubic-bezier(.2,0,0,1)"


def kf(name, T, stops):
    """stops: [(ms, css, easing-or-None)] → @keyframes. easing은 그 지점에서 다음 지점까지의 곡선."""
    out = [f"@keyframes {name}{{"]
    for ms, css, ease in stops:
        pct = round(ms / T * 100, 3)
        e = f"animation-timing-function:{ease};" if ease else ""
        out.append(f"{pct}%{{{css.rstrip(';')};{e}}}")
    out.append("}")
    return "".join(out)


def anim(name, T):
    return f"animation: {name} {T}ms linear infinite"


def img(src, style="", extra=""):
    return f'<img src="motion/{src}.png" alt="" style="position: absolute; left: 0; top: 0; width: {W}px; height: {H}px; {style}"{extra}>'


def tap(name, T, ms, x, y):
    css = kf(name, T, [(0, "opacity:0;transform:scale(.6)", None), (ms - 1, "opacity:0;transform:scale(.6)", "ease-out"),
                       (ms + 120, "opacity:1;transform:scale(1)", None), (ms + 320, "opacity:0;transform:scale(1.15)", None),
                       (T, "opacity:0;transform:scale(1.15)", None)])
    el = f'<span aria-hidden="true" style="position: absolute; z-index: 90; left: {x - 22}px; top: {y - 22}px; width: 44px; height: 44px; border-radius: 22px; background: rgba(29,29,29,0.22); border: 2px solid rgba(255,255,255,0.9); box-sizing: border-box; pointer-events: none; {anim(name, T)}"></span>'
    return css, el


def board(title, T, css, body):
    return f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Do+Hyeon&amp;family=IBM+Plex+Sans+KR:wght@400;500;700&amp;display=swap">
<style>
body{{margin:0}}
{css}
</style>
</helmet>
<div style="width: {W}px; height: {H}px; position: relative; overflow: hidden; background: {BG}; color: #1D1D1D; font-family: 'IBM Plex Sans KR', sans-serif">
{body}
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":{W},"height":{H}}}}}'>
class Component extends DCLogic {{
renderVals() {{ return {{}}; }}
}}
</script>
</body>
</html>
'''


# ---------- 1. 바텀시트 열고 닫기 ----------
SHEET_TOP = 215
SHEET_H = H - SHEET_TOP


def sheet_board(key):
    T = 4400
    o, c = 700, 2800  # 열기 시작, 닫기 시작
    if key == "A":
        od, oe, cd, ce = 400, IOS, 300, IOS
    elif key == "B":
        od, oe, cd, ce = 480, "cubic-bezier(.2,1.25,.4,1)", 260, OUT_EASE
    else:
        od, oe, cd, ce = 420, IOS, 320, IOS
    down = f"transform:translateY({SHEET_H}px)"
    css = [kf("sh", T, [(0, down, None), (o, down, oe), (o + od, "transform:none", None), (c, "transform:none", ce),
                        (c + cd, down, None), (T, down, None)]),
           kf("scrim", T, [(0, "opacity:0", None), (o, "opacity:0", "ease-out"), (o + min(od, 400), "opacity:1", None),
                           (c, "opacity:1", "ease-in"), (c + cd, "opacity:0", None), (T, "opacity:0", None)])]
    base_anim = ""
    if key == "C":
        css.append(kf("base", T, [(0, "transform:none;border-radius:0", None), (o, "transform:none;border-radius:0", oe),
                                  (o + od, "transform:scale(.93) translateY(10px);border-radius:28px", None),
                                  (c, "transform:scale(.93) translateY(10px);border-radius:28px", ce),
                                  (c + cd, "transform:none;border-radius:0", None), (T, "transform:none;border-radius:0", None)]))
        base_anim = anim("base", T) + "; transform-origin: 50% 0"
    t1, e1 = tap("t1", T, o - 120, 300, 703)
    t2, e2 = tap("t2", T, c - 120, 195, 110)
    css += [t1, t2]
    body = f'''<div style="position: absolute; inset: 0; background: #1D1D1D"></div>
<div style="position: absolute; inset: 0; overflow: hidden; background: {BG}; {base_anim}">{img("edit")}</div>
<div style="position: absolute; inset: 0; background: rgba(0,0,0,0.24); backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px); {anim("scrim", T)}"></div>
<div style="position: absolute; left: 0; top: {SHEET_TOP}px; width: {W}px; height: {SHEET_H + 80}px; {anim("sh", T)}">
<div style="position: absolute; left: 0; right: 0; top: 0; bottom: 0; border-radius: 36px 36px 0 0; background: #FFFFFF"></div>
<div style="position: absolute; left: 0; top: 0; width: {W}px; height: {SHEET_H}px; overflow: hidden; border-radius: 36px 36px 0 0">{img("sheet", f"top: -{SHEET_TOP}px")}</div>
</div>
{e1}{e2}'''
    return T, "".join(css), body


# ---------- 2. 화면 이동 (목록 → 상품 상세 → 뒤로) ----------
LP = (16, 134, 173)   # 목록 첫 카드 사진 x, y, 크기
DP = (20, 108, 350)   # 상세 사진 x, y, 크기


def push_board(key):
    T = 5200
    p, b = 700, 3100
    t1, e1 = tap("t1", T, p - 120, 102, 220)
    t2, e2 = tap("t2", T, b - 120, 38, 74)
    css = [t1, t2]
    if key == "A":
        d, bd = 380, 320
        css += [kf("det", T, [(0, f"transform:translateX({W}px)", None), (p, f"transform:translateX({W}px)", IOS), (p + d, "transform:none", None),
                              (b, "transform:none", IOS), (b + bd, f"transform:translateX({W}px)", None), (T, f"transform:translateX({W}px)", None)]),
                kf("lst", T, [(0, "transform:none", None), (p, "transform:none", IOS), (p + d, "transform:translateX(-117px)", None),
                              (b, "transform:translateX(-117px)", IOS), (b + bd, "transform:none", None), (T, "transform:none", None)]),
                kf("dim", T, [(0, "opacity:0", None), (p, "opacity:0", IOS), (p + d, "opacity:1", None),
                              (b, "opacity:1", IOS), (b + bd, "opacity:0", None), (T, "opacity:0", None)])]
        body = f'''<div style="position: absolute; inset: 0; {anim("lst", T)}">{img("list")}<div style="position: absolute; inset: 0; background: rgba(0,0,0,0.12); {anim("dim", T)}"></div></div>
<div style="position: absolute; inset: 0; background: {BG}; box-shadow: -8px 0 24px rgba(0,0,0,0.12); {anim("det", T)}">{img("detail")}</div>'''
    elif key == "B":
        d, bd = 420, 360
        lx, ly, ls = LP
        dx, dy, ds = DP
        s = ls / ds
        tx = (lx + ls / 2) - (dx + ds / 2)
        ty = (ly + ls / 2) - (dy + ds / 2)
        small = f"transform:translate({tx:.1f}px,{ty:.1f}px) scale({s:.4f})"
        css += [kf("ph", T, [(0, "opacity:0;" + small, None), (p - 1, "opacity:0;" + small, None), (p, "opacity:1;" + small, DECEL),
                             (p + d, "opacity:1;transform:none", None), (p + d + 1, "opacity:0;transform:none", None),
                             (b - 1, "opacity:0;transform:none", None), (b, "opacity:1;transform:none", DECEL),
                             (b + bd, "opacity:1;" + small, None), (b + bd + 1, "opacity:0;" + small, None), (T, "opacity:0;" + small, None)]),
                kf("det", T, [(0, "opacity:0", None), (p, "opacity:0", "ease-out"), (p + d, "opacity:1", None),
                              (b, "opacity:1", "ease-in"), (b + bd * 0.7, "opacity:0", None), (T, "opacity:0", None)]),
                kf("hole", T, [(0, "opacity:0", None), (p - 1, "opacity:0", None), (p, "opacity:1", None), (p + d, "opacity:1", None),
                               (p + d + 1, "opacity:0", None), (b - 1, "opacity:0", None), (b, "opacity:1", None),
                               (b + bd, "opacity:1", None), (b + bd + 1, "opacity:0", None), (T, "opacity:0", None)])]
        body = f'''{img("list")}
<div style="position: absolute; left: {lx}px; top: {ly}px; width: {ls}px; height: {ls}px; border-radius: 20px; background: {BG}; {anim("hole", T)}"></div>
<div style="position: absolute; inset: 0; {anim("det", T)}">{img("detail")}<div style="position: absolute; left: {dx}px; top: {dy}px; width: {ds}px; height: {ds}px; background: {BG}"></div></div>
<div style="position: absolute; left: {dx}px; top: {dy}px; width: {ds}px; height: {ds}px; overflow: hidden; border-radius: 28px; {anim("ph", T)}">{img("detail", f"left: -{dx}px; top: -{dy}px")}</div>'''
    else:
        d, bd = 260, 200
        css += [kf("det", T, [(0, "opacity:0;transform:translateY(24px)", None), (p, "opacity:0;transform:translateY(24px)", "cubic-bezier(0,0,.2,1)"),
                              (p + d, "opacity:1;transform:none", None), (b, "opacity:1;transform:none", "cubic-bezier(.4,0,1,1)"),
                              (b + bd, "opacity:0;transform:translateY(24px)", None), (T, "opacity:0;transform:translateY(24px)", None)])]
        body = f'''{img("list")}
<div style="position: absolute; inset: 0; background: {BG}; {anim("det", T)}">{img("detail")}</div>'''
    return T, "".join(css), body + e1 + e2


# ---------- 3. 탭 전환 (홈 → 카테고리 → 목적 → 홈) ----------
TABS = [("home", "홈", '<path d="M4 11l8-7 8 7v9h-5v-6H9v6H4z"></path>'),
        ("cat", "카테고리", '<rect x="4" y="4" width="7" height="7" rx="2"></rect><rect x="13" y="4" width="7" height="7" rx="2"></rect><rect x="4" y="13" width="7" height="7" rx="2"></rect><rect x="13" y="13" width="7" height="7" rx="2"></rect>'),
        ("purpose", "목적", '<rect x="4" y="8" width="16" height="12" rx="3"></rect><path d="M7 5h10"></path>')]
SEG = 1700
PILL = 250


def tab_board(key):
    T = SEG * 3
    sw = [SEG * k + 900 for k in range(3)]  # 0→1, 1→2, 2→0 전환 시작
    css, taps = [], []
    seq = [(0, 1), (1, 2), (2, 0)]
    # 알약 위치: nav 안쪽 8px 패딩, 칸 폭 114px
    pos = [0, 114, 228]
    stops = [(0, "transform:translateX(0)", None)]
    for (a, b_), t in zip(seq, sw):
        stops += [(t, f"transform:translateX({pos[a]}px)", IOS), (t + PILL, f"transform:translateX({pos[b_]}px)", None)]
    stops.append((T, "transform:translateX(0)", None))
    css.append(kf("pill", T, stops))
    for i, (_, _, _) in enumerate(TABS):
        st = []
        for (a, b_), t in zip(seq, sw):
            if a == i:
                st += [(t, "color:#1D1D1D;font-weight:700", None), (t + PILL * 0.5, "color:#FFFFFF;font-weight:400", None)]
            if b_ == i:
                st += [(t, "color:#FFFFFF;font-weight:400", None), (t + PILL * 0.5, "color:#1D1D1D;font-weight:700", None)]
        st.sort()
        first = "color:#1D1D1D;font-weight:700" if i == 0 else "color:#FFFFFF;font-weight:400"
        css.append(kf(f"lb{i}", T, [(0, first, None)] + st + [(T, first, None)]))
    for (a, b_), t in zip(seq, sw):
        c, e = tap(f"tp{b_}", T, t - 120, 16 + 8 + pos[b_] + 57, 844 - 24 - 32)
        css.append(c)
        taps.append(e)
    layers = []
    for i, (nm, _, _) in enumerate(TABS):
        # i번 화면이 보이는 구간: 들어오는 전환 시작 ~ 나가는 전환 끝
        tin = [t for (a, b_), t in zip(seq, sw) if b_ == i][0]
        tout = [t for (a, b_), t in zip(seq, sw) if a == i][0]
        if key == "A":
            d = 180
            show, hide = "opacity:1", "opacity:0"
            if i == 0:
                st = [(0, show, None), (tout, show, "ease-in"), (tout + d, hide, None), (tin, hide, "ease-out"), (tin + d, show, None), (T, show, None)]
            else:
                st = [(0, hide, None), (tin, hide, "ease-out"), (tin + d, show, None), (tout, show, "ease-in"), (tout + d, hide, None), (T, hide, None)]
        elif key == "B":
            d = 320
            # 오른쪽 탭으로 가면 내용이 왼쪽으로, 왼쪽 탭으로 가면 오른쪽으로 민다
            def dirx(t, entering):
                a, b_ = [s for s, tt in zip(seq, sw) if tt == t][0]
                sgn = 1 if b_ > a else -1
                return (sgn * W) if entering else (-sgn * W)
            ent = lambda t: f"transform:translateX({dirx(t, True)}px)"
            ext = lambda t: f"transform:translateX({dirx(t, False)}px)"
            on = "transform:none"
            if i == 0:
                st = [(0, on, None), (tout, on, IOS), (tout + d, ext(tout), None), (tin - 1, ent(tin), None), (tin, ent(tin), IOS), (tin + d, on, None), (T, on, None)]
            else:
                st = [(0, ent(tin), None), (tin, ent(tin), IOS), (tin + d, on, None), (tout, on, IOS), (tout + d, ext(tout), None), (T, ext(tout), None)]
        else:
            o_, i_ = 90, 210
            show, hide_out, hide_in = "opacity:1;transform:none", "opacity:0;transform:none", "opacity:0;transform:scale(.97)"
            if i == 0:
                st = [(0, show, None), (tout, show, "ease-in"), (tout + o_, hide_out, None), (tin + o_, hide_in, "cubic-bezier(0,0,.2,1)"), (tin + o_ + i_, show, None), (T, show, None)]
            else:
                st = [(0, hide_in, None), (tin + o_, hide_in, "cubic-bezier(0,0,.2,1)"), (tin + o_ + i_, show, None), (tout, show, "ease-in"), (tout + o_, hide_out, None), (T, hide_out, None)]
        css.append(kf(f"sc{i}", T, st))
        layers.append(f'<div style="position: absolute; inset: 0; background: {BG}; {anim(f"sc{i}", T)}">{img(nm)}</div>')
    labels = "".join(
        f'<span style="width: 114px; height: 48px; display: flex; align-items: center; justify-content: center; gap: 6px; font-size: 15px; {anim(f"lb{i}", T)}"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">{p}</svg>{n}</span>'
        for i, (_, n, p) in enumerate(TABS))
    nav = f'''<nav aria-label="하단 탭" style="position: absolute; left: 16px; right: 16px; bottom: 24px; z-index: 5; height: 64px; box-sizing: border-box; padding: 8px; border-radius: 32px; background: #1D1D1D">
<span style="position: absolute; left: 8px; top: 8px; width: 114px; height: 48px; border-radius: 24px; background: #FFFFFF; {anim("pill", T)}"></span>
<span style="position: relative; display: flex">{labels}</span></nav>'''
    return T, "".join(css), "".join(layers) + nav + "".join(taps)


# ---------- 4. 연속 처리 카드 넘김 (확정 → 오른쪽, 보류 → 왼쪽) ----------
CARD = "inset(116px 20px 154px 20px round 28px)"
STACK = "inset(100px 0 128px 0)"


def card_board(key):
    T = 6000
    a1, a2, reset = 1100, 3300, 5300
    t1, e1 = tap("t1", T, a1 - 140, 285, 780)
    t2, e2 = tap("t2", T, a2 - 140, 105, 780)
    css = [t1, t2]
    if key == "A":
        d, rd = 340, 300
        fly = lambda sgn: f"transform:translateX({sgn * 480}px) rotate({sgn * 16}deg)"
        fly_ease = "cubic-bezier(.5,0,.9,.5)"
    elif key == "B":
        d, rd = 280, 280
        fly = lambda sgn: f"transform:translateX({sgn * 420}px)"
        fly_ease = "cubic-bezier(.5,0,.9,.5)"
    else:
        d, rd = 200, 220
        fly = lambda sgn: f"transform:translateX({sgn * 56}px);opacity:0"
        fly_ease = "cubic-bezier(.4,0,1,1)"
    rest = "transform:none;opacity:1"
    if key == "C":
        start = "opacity:0;transform:none"
        rise_from = start
        rise_delay = 120
    else:
        rise_from = "transform:scale(.94) translateY(14px);opacity:1"
        rise_delay = 40

    def card_kf(name, at, sgn):
        return kf(name, T, [(0, "opacity:0;transform:none", None), (at - 1, "opacity:0;transform:none", None),
                            (at, "opacity:1;transform:none", fly_ease), (at + d, "opacity:1;" + fly(sgn) if key != "C" else fly(sgn), None),
                            (at + d + 1, "opacity:0", None), (T, "opacity:0", None)])

    def stack_kf(name, at, until):
        st = [(0, "opacity:0", None), (at - 1, "opacity:0", None), (at, rise_from, None), (at + rise_delay, rise_from, DECEL),
              (at + rise_delay + rd, rest, None)]
        if until:
            st += [(until, rest, None), (until + 1, "opacity:0", None)]
        st.append((T, rest if not until else "opacity:0", None))
        return kf(name, T, st)

    def vis_kf(name, at, until):
        st = [(0, "opacity:0", None), (at - 1, "opacity:0", None), (at, "opacity:1", None)]
        if until:
            st += [(until, "opacity:1", None), (until + 1, "opacity:0", None)]
        st.append((T, "opacity:1" if not until else "opacity:0", None))
        return kf(name, T, st)

    css += [card_kf("c0", a1, 1), card_kf("c1", a2, -1),
            stack_kf("s1", a1, a2), stack_kf("s2", a2, None),
            vis_kf("b1", a1, a2), vis_kf("b2", a2, None),
            kf("r0", T, [(0, "opacity:1", None), (a1 - 1, "opacity:1", None), (a1, "opacity:0", None), (reset, "opacity:0", "ease-out"),
                         (reset + 400, "opacity:1", None), (T, "opacity:1", None)])]
    body = f'''{img("rfbg1", anim("b1", T))}{img("rfbg2", anim("b2", T))}
{img("rf1", f"clip-path: {STACK}; transform-origin: 50% 70%; {anim('s1', T)}")}
{img("rf2", f"clip-path: {STACK}; transform-origin: 50% 70%; {anim('s2', T)}")}
{img("rf0", f"clip-path: {CARD}; transform-origin: 50% 90%; {anim('c0', T)}")}
{img("rf1", f"clip-path: {CARD}; transform-origin: 50% 90%; {anim('c1', T)}")}
{img("rf0", anim("r0", T))}
{e1}{e2}'''
    return T, "".join(css), body


# ---------- 5. 공유 저장 카드 ----------
def share_board(key):
    T = 4200
    a, h = 500, 1500  # 나타남 시작, 보이는 시간
    z = a + h
    if key in ("A", "C"):
        off = "transform:translateY(140px)"
        css = [kf("card", T, [(0, off, None), (a, off, IOS), (a + 340, "transform:none", None), (z, "transform:none", OUT_EASE),
                              (z + 260, off, None), (T, off, None)])]
    else:
        off = "opacity:0;transform:scale(.96)"
        css = [kf("card", T, [(0, off, None), (a, off, "cubic-bezier(0,0,.2,1)"), (a + 200, "opacity:1;transform:none", None),
                              (z, "opacity:1;transform:none", "ease-in"), (z + 160, "opacity:0;transform:none", None), (T, "opacity:0", None)])]
    check_style, tile_style = "", ""
    if key == "C":
        css.append(kf("draw", T, [(0, "stroke-dashoffset:24", None), (a + 200, "stroke-dashoffset:24", "cubic-bezier(.4,0,.2,1)"),
                                  (a + 520, "stroke-dashoffset:0", None), (T, "stroke-dashoffset:0", None)]))
        css.append(kf("tile", T, [(0, "transform:scale(.7)", None), (a + 120, "transform:scale(.7)", "cubic-bezier(.2,1.4,.4,1)"),
                                  (a + 440, "transform:none", None), (T, "transform:none", None)]))
        check_style = f' style="stroke-dasharray: 24; {anim("draw", T)}"'
        tile_style = f"; {anim('tile', T)}"
    body = f'''<div style="position: absolute; inset: 0; background: #E9E9E9">
<div style="height: 100px; background: #DADADA; display: flex; align-items: flex-end; padding: 0 16px 12px; font-size: 13px; color: #5E5E5E">다른 앱 · 쇼핑몰 상품 페이지</div>
<div style="padding: 16px; display: flex; flex-direction: column; gap: 12px"><div style="height: 300px; border-radius: 12px; background: #D2D2D2"></div><div style="width: 70%; height: 18px; border-radius: 6px; background: #D2D2D2"></div><div style="width: 40%; height: 22px; border-radius: 6px; background: #C8C8C8"></div></div>
</div>
<div role="status" style="position: absolute; left: 16px; right: 16px; bottom: 40px; z-index: 5; box-sizing: border-box; padding: 16px; border-radius: 28px; background: #FFFFFF; display: flex; align-items: center; gap: 14px; {anim("card", T)}">
<span style="width: 48px; height: 48px; flex-shrink: 0; border-radius: 14px; background: #DCF1E4; color: #1F7A4D; display: flex; align-items: center; justify-content: center{tile_style}"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12l5 5L20 7"{check_style}></path></svg></span>
<span style="flex-grow: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px"><span style="font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 20px; line-height: 1.0">위시리스트에 저장했어요</span><span style="font-size: 13px; line-height: 1.45; word-break: keep-all; color: #5E5E5E">정보를 가져오는 중이에요</span></span>
</div>'''
    return T, "".join(css), body


GROUPS = [
    ("Sheet", sheet_board, {"A": "부드러운 감속", "B": "살짝 튀는 스프링", "C": "뒤 화면 물러남"}),
    ("Push", push_board, {"A": "옆으로 밀기", "B": "사진이 커지며 열림", "C": "제자리에서 떠오름"}),
    ("Tab", tab_board, {"A": "크로스페이드", "B": "탭 방향으로 밀기", "C": "페이드 스루"}),
    ("Card", card_board, {"A": "기울며 날아감", "B": "가로로 빠짐", "C": "짧게 밀리며 사라짐"}),
    ("Share", share_board, {"A": "아래에서 올라옴", "B": "제자리 페이드", "C": "올라오며 체크 그리기"}),
]


def main():
    out = os.path.join(sys.argv[1], "project")
    os.makedirs(out, exist_ok=True)
    names = []
    for g, fn, labels in GROUPS:
        for k, label in labels.items():
            T, css, body = fn(k)
            name = f"Motion{g}{k}"
            with open(os.path.join(out, name + ".dc.html"), "w") as f:
                f.write(board(f"{name} {label}", T, css, body))
            names.append(name)
    print(" ".join(names))


if __name__ == "__main__":
    main()
