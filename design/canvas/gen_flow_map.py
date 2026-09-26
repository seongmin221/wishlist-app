# 흐름도(Flow-Map) 생성기. 확정 보드를 dc-import로 절반 크기로 불러와 화살표로 잇는다.
import os, re, sys, json
SRC = os.environ.get('CANVAS_SRC', 'source/project') + '/'
OUT = sys.argv[1]
pd = open(SRC + 'PD-FINAL.dc.html').read()
helmet = pd[pd.index('<helmet>'):pd.index('</helmet>') + len('</helmet>')]
W, H = 1760, 2040
SC = 0.5
# (id, title, source, x, y, child_h, import_attrs)
NODES = [
  ('home',   '홈',               'P-D',            40,  200, 844,  'name="P-D"'),
  ('cat',    '카테고리 탭',       'CT-D',           400, 200, 1000, 'name="CT-D"'),
  ('ptab',   '목적 탭',          'PT-A8',          40,  840, 1000, 'name="PT-A8"'),
  ('detail', '목적 상세',         'PurposeDetail',  400, 840, 844,  'name="PurposeDetail" pkey="closet" start="detail"'),
  ('picker', '후보 추가',         'PurposeDetail',  760, 840, 844,  'name="PurposeDetail" pkey="lamp" start="picker"'),
  ('lp',     '길게 누르기 메뉴',   'PurposeDetail',  1120, 840, 844, 'name="PurposeDetail" pkey="closet" start="menu"'),
  ('finish', '비교 끝내기',       'PurposeDetail',  400, 1480, 844, 'name="PurposeDetail" pkey="closet" start="finish"'),
  ('arch',   '아카이브 목록',     'A8-AR2',         760, 1480, 1000, 'name="A8-AR2"'),
  ('rec',    '기록 상세',         'AD-F2',          1120, 1480, 1000, 'name="AD-F2"'),
  ('recact', '기록 동작',         'ARCH-ACT2',      1480, 1480, 1000, 'name="ARCH-ACT2" start="menu"'),
]
N = {n[0]: n for n in NODES}
def box(k):
    _, _, _, x, y, ch, _ = N[k]; return x, y, 390 * SC, ch * SC

def node(n):
    k, title, src, x, y, ch, attrs = n; w, h = 390 * SC, ch * SC
    return (f'<div style="position: absolute; left: {x}px; top: {y - 44}px; width: {w}px; display: flex; flex-direction: column; gap: 2px">'
            f'<span style="font-size: 15px; font-weight: 700; letter-spacing: -0.02em">{title}</span>'
            f"<span style=\"font-family: 'JetBrains Mono', Pretendard, monospace; font-size: 10px; letter-spacing: .1em; color: rgba(17,17,17,.5)\">{src}</span></div>"
            f'<div style="position: absolute; left: {x}px; top: {y}px; width: {w}px; height: {h}px; border-radius: 20px; overflow: hidden; background: #F7F7F3; box-shadow: 0 0 0 1px rgba(17,17,17,.1), 0 18px 40px -24px rgba(17,17,17,.35)">'
            f'<div style="width: 390px; height: {ch}px; transform: scale({SC}); transform-origin: top left">'
            f'<dc-import {attrs} hint-size="390px,{ch}px"></dc-import></div></div>')

def mid_r(k): x, y, w, h = box(k); return x + w, y + min(h, 422) / 2
def mid_l(k): x, y, w, h = box(k); return x, y + min(h, 422) / 2
def top_c(k): x, y, w, h = box(k); return x + w / 2, y
def bot_c(k): x, y, w, h = box(k); return x + w / 2, y + h

ARROWS = []   # (path d, label, lx, ly)
def straight(a, b, label):
    (x1, y1), (x2, y2) = a, b
    ARROWS.append((f'M {x1 + 8} {y1} L {x2 - 10} {y2}' if y1 == y2 else f'M {x1} {y1 + 8} L {x2} {y2 - 10}', label,
                   (x1 + x2) / 2, (y1 + y2) / 2))
straight(mid_r('home'), mid_l('cat'), '카테고리 탭')
hx, hy = bot_c('home'); px, py = top_c('ptab'); straight((hx, hy), (px, py), '목적 탭 · 목적 탭으로')
straight(mid_r('ptab'), mid_l('detail'), '목적 누르기')
straight(mid_r('detail'), mid_l('picker'), '후보 추가 카드')
dx, dy = top_c('detail'); lx, ly = top_c('lp')
ARROWS.append((f'M {dx} {dy - 60} C {dx} {dy - 120}, {lx} {ly - 120}, {lx} {ly - 58}', '상품 길게 누르기', (dx + lx) / 2, dy - 118))
bx, by = bot_c('detail'); fx, fy = top_c('finish'); ARROWS.append((f'M {bx} {by + 8} L {fx} {fy - 58}', '비교 끝내기', bx, (by + fy - 50) / 2))
straight(mid_r('finish'), mid_l('arch'), '보관하면 아카이브로')
p1x, p1y = bot_c('ptab'); ax, ay = top_c('arch')
ARROWS.append((f'M {p1x} {p1y + 8} L {p1x} {ay - 90} L {ax} {ay - 90} L {ax} {ay - 58}', '아카이브 전환', (p1x + 400) / 2, ay - 90))
straight(mid_r('arch'), mid_l('rec'), '기록 누르기')
straight(mid_r('rec'), mid_l('recact'), '··· 메뉴')

def svg():
    parts = [f'<svg width="{W}" height="{H}" viewBox="0 0 {W} {H}" style="position: absolute; left: 0; top: 0; pointer-events: none" aria-hidden="true">'
             '<defs><marker id="ah" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#111111"></path></marker></defs>']
    for d, label, x, y in ARROWS:
        parts.append(f'<path d="{d}" fill="none" stroke="#111111" stroke-width="1.6" marker-end="url(#ah)"></path>')
    parts.append('</svg>')
    labels = ''.join(f'<span style="position: absolute; left: {x}px; top: {y}px; transform: translate(-50%, -50%); background: #F7F7F3; padding: 3px 8px; border-radius: 999px; box-shadow: 0 0 0 1px rgba(17,17,17,.12); font-size: 12px; font-weight: 700; white-space: nowrap">{label}</span>'
                     for d, label, x, y in ARROWS)
    return ''.join(parts) + labels

HEAD = ('<div style="position: absolute; left: 40px; top: 40px; display: flex; flex-direction: column; gap: 6px">'
        '<h1 style="margin: 0; font-size: 34px; line-height: 40px; font-weight: 700; letter-spacing: -0.035em">확정 디자인 흐름도</h1>'
        '<span style="font-size: 14px; color: rgba(17,17,17,.62)">확정한 화면을 실제 보드 그대로 절반 크기로 불러와 이었어요. 보드를 고치면 여기도 함께 바뀌어요.</span></div>')

html = f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>확정 디자인 흐름도</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
{helmet}
<div style="width: {W}px; height: {H}px; position: relative; overflow: hidden; background: #EFEFEA; color: #111111">{HEAD}{''.join(node(n) for n in NODES)}{svg()}</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview": {{"width": {W}, "height": {H}}}}}'>
class Component extends DCLogic {{
renderVals() {{ return {{}}; }}
}}
</script>
</body>
</html>
'''
open(OUT + '/Flow-Map.dc.html', 'w').write(html)
print('ok', len(html))
