# 사용: python3 gen_neutral.py <UI 보드 폴더> <출력 폴더>
# 무채색 단계 후보 생성기: UI 피드백 적용 보드(out/UI-*)에 A안·B안을 적용하고 단계 견본을 만든다
import os, re, sys
from oklch import hex2oklch, contrast
UI, OUT = sys.argv[1], sys.argv[2]; os.makedirs(OUT, exist_ok=True)  # UI: gen_ui_review·gen_colorsys 결과 폴더

from palette_sets import NEUTRAL as GRAY
INK_B, CARD_B = '#181811', '#FDFDFB'

def sec(surface, ink, w=0.45):
    c = [int(surface[i:i + 2], 16) for i in (1, 3, 5)]; k = [int(ink[i:i + 2], 16) for i in (1, 3, 5)]
    return '#' + ''.join('%02X' % round(c[j] * w + k[j] * (1 - w)) for j in range(3))
SEC_B = {'#667075': sec('#CFE3F0', INK_B), '#6C6A75': sec('#DCD6F0', INK_B), '#776B64': sec('#F3D9C9', INK_B)}

def apply(s, opt, prefix):
    for a, b in GRAY.items(): s = re.sub(re.escape(a), b, s, flags=re.I)
    if opt == 'B':
        for a, b in SEC_B.items(): s = s.replace(a, b)
        s = re.sub('#111111', INK_B, s, flags=re.I).replace('rgba(17,17,17,', 'rgba(24,24,17,').replace('rgba(17, 17, 17,', 'rgba(24, 24, 17,')
        s = re.sub('#FFFFFF', CARD_B, s, flags=re.I).replace('rgba(255,255,255,', 'rgba(253,253,251,')
    s = s.replace('name="UI-PurposeDetail"', 'name="%sPurposeDetail"' % prefix)
    return s

for opt, prefix, label in [('A', 'NA-', 'A안 · 회색 단계만'), ('B', 'NB-', 'B안 · 먹색·카드까지')]:
    for n in ['Flow-Confirmed', 'PurposeDetail']:
        s = open(os.path.join(UI, 'UI-%s.dc.html' % n)).read()
        s = re.sub(r'<title>[^<]*</title>', '<title>무채색 %s · %s</title>' % (label, '기능 흐름 뷰' if n == 'Flow-Confirmed' else '목적 상세'), s, count=1)
        open(os.path.join(OUT, prefix + n + '.dc.html'), 'w').write(apply(s, opt, prefix))

# 단계 견본
from canvas_src import helmet as _helmet
helmet = _helmet('P-D')
MONO = "font-family: 'JetBrains Mono', Pretendard, monospace; font-size: 10px; letter-spacing: .1em"
ROWS = [  # (단계, 역할, 지금 쓰는 값들, A, B)
    ('카드', '흰 카드·시트·버튼 글자', ['#FFFFFF'], '#FFFFFF', CARD_B),
    ('바탕', '화면 바탕', ['#F7F7F3'], '#F7F7F3', '#F7F7F3'),
    ('옅은 면', '원형 버튼·아이콘 칸·사진 바탕', ['#F2F2EE', '#F0F0EB', '#EFEFEA'], '#F2F2EE', '#F2F2EE'),
    ('칩', '전환 버튼 바탕·필터 pill', ['#ECECE6', '#E9E9E3'], '#EAEAE4', '#EAEAE4'),
    ('비활성 면', '비활성 버튼 바탕', ['#E5E5E0', '#E4E4DE'], '#E4E4DE', '#E4E4DE'),
    ('보조', '› 화살표·비활성 글자', ['#A5A59E', '#9C9C95'], '#A0A099', '#A0A099'),
    ('먹색', '글자·검은 버튼 (투명도로 보조 글자·선)', ['#111111'], '#111111', INK_B),
]
def chip(h, big=False):
    L, C, H = hex2oklch(h); size = 64 if big else 40
    return (f'<span style="display: flex; flex-direction: column; gap: 6px; align-items: flex-start">'
            f'<span style="width: {size}px; height: {size}px; border-radius: 14px; background: {h}; box-shadow: inset 0 0 0 1px rgba(17,17,17,.1)"></span>'
            f'<span style="{MONO}; color: #111111">{h}</span>'
            + (f'<span style="{MONO}; color: rgba(17,17,17,.62)">L{L:.3f} C{C:.4f}</span>' if big else '') + '</span>')
rows = []
for name, role, cur, a, b in ROWS:
    rows.append(
        '<div style="display: grid; grid-template-columns: 220px 1fr 150px 150px; gap: 24px; align-items: start; padding: 18px 0; border-top: 1px solid rgba(17,17,17,.1)">'
        f'<span style="display: flex; flex-direction: column; gap: 4px"><span style="font-size: 17px; font-weight: 700">{name}</span><span style="font-size: 13px; color: rgba(17,17,17,.62)">{role}</span></span>'
        f'<span style="display: flex; gap: 14px">{"".join(chip(h) for h in cur)}</span>'
        f'{chip(a, True)}{chip(b, True)}</div>')
W = 1248
head = ('<div style="display: grid; grid-template-columns: 220px 1fr 150px 150px; gap: 24px; padding-bottom: 10px">'
        + ''.join(f'<span style="font-size: 12px; font-weight: 700; color: rgba(17,17,17,.62)">{t}</span>' for t in ['단계', '지금 쓰는 값', 'A안 · 회색만', 'B안 · 먹색·카드까지']) + '</div>')
body = (f'<div style="width: {W}px; box-sizing: border-box; padding: 40px; background: #F7F7F3; color: #111111; display: flex; flex-direction: column; gap: 28px">'
        '<div style="display: flex; flex-direction: column; gap: 8px"><h1 style="margin: 0; font-size: 34px; line-height: 40px; font-weight: 700; letter-spacing: -0.035em">무채색 단계</h1>'
        '<span style="font-size: 14px; line-height: 20px; color: rgba(17,17,17,.62)">바탕 #F7F7F3의 색조(H 106)로 흩어진 회색 11가지를 역할별 단계로 묶었어요. A안은 회색만 묶고, B안은 먹색과 흰 카드에도 바탕 색조를 살짝 넣어요. 보조 글자와 선은 지금처럼 먹색의 투명도로 써서 먹색을 따라가요.</span></div>'
        '<div style="display: flex; flex-direction: column">' + head + ''.join(rows) + '</div></div>')
H = 1200
html = f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>무채색 단계</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
{helmet}
<div style="width: {W}px; height: {H}px; overflow: hidden; background: #F7F7F3">{body}</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview": {{"width": {W}, "height": {H}}}}}'>
class Component extends DCLogic {{
renderVals() {{ return {{}}; }}
}}
</script>
</body>
</html>
'''
open(os.path.join(OUT, 'NEU-RAMP.dc.html'), 'w').write(html)
print('ramp', W, H, 'secB', SEC_B)
for f in sorted(os.listdir(OUT)):
    t = open(os.path.join(OUT, f)).read()
    left = sorted(set(h.upper() for h in re.findall(r'#[0-9A-Fa-f]{6}\b', t)) & set(GRAY))
    print(f, 'old grays left:', left, '#111111' in t.upper() if f.startswith('NB') else '')
