# 디자인 시스템 색(확정 톤 +, 바탕 #F7F7F3)과 무채색 단계 A안을 UI 피드백 적용 보드에 반영한다.
# 2026-09-27에는 강하게 세트(SETS['S'])로 만들었고, 2026-09-28 확정 톤 +(SETS['PS'])로 바꿨다.
# 사용: python3 gen_colorsys.py <src dir> <out dir>
import os, re, sys
from collections import Counter
from palette_sets import SETS, NEUTRAL
from oklch import contrast
SRC, OUT = sys.argv[1], sys.argv[2]; os.makedirs(OUT, exist_ok=True)
S = {c['n']: c for c in SETS['PS']}
INK = '#111111'

def mix(a, b, w):  # a*w + b*(1-w)
    x = [int(a[i:i + 2], 16) for i in (1, 3, 5)]; y = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    return '#' + ''.join('%02X' % round(x[j] * w + y[j] * (1 - w)) for j in range(3))
def tone(surface, target, w=0.45):  # 같은 색의 어두운 톤, 대비 target 이상
    while True:
        t = mix(surface, INK, w)
        if contrast(t, surface) >= target or w <= 0: return t
        w -= 0.02

# 확정 색 -> 강하게 세트
MAP = {'#D9EC9A': S['연두']['h'], '#F4E4A6': S['노랑']['h'], '#CFDFD4': S['하늘']['h'],   # 홈 챙길 일 블록
       '#DCD6F0': S['보라']['h'], '#CFE3F0': S['하늘']['h'], '#F3D9C9': S['주황']['h']}   # 목적 색 (셋째는 확정 디자인처럼 살구)
# 옅은 목적 색(PT-A8 아카이브 보기): 원래와 같은 비율(흰색 쪽 37.5%)로 다시 만든다
PALE = {'#E1ECF1': mix(S['하늘']['h'], '#FFFFFF', 0.625), '#E8E5F1': mix(S['보라']['h'], '#FFFFFF', 0.625)}
SURF = {}  # 새 면 색 -> (헤드라인 둘째 줄 3:1, 작은 글자 4.5:1)
for h in list(MAP.values()) + list(PALE.values()):
    SURF[h] = (tone(h, 3.0), tone(h, 4.5))
OLD_SEC = {'#667075': '#CFE3F0', '#6C6A75': '#DCD6F0', '#776B64': '#F3D9C9'}
LOG = Counter()

def element_end(s, i):
    tag = re.match(r'<([a-z0-9-]+)', s[i:]).group(1); depth = 0
    for m in re.finditer(r'<(/?)%s\b[^>]*>' % tag, s[i:]):
        depth += -1 if m.group(1) else 1
        if depth == 0: return i + m.end()
    return len(s)

def recolor_inside(s):
    # 색 면을 가진 요소 안의 작은 보조 글자(먹색 62%)를 같은 색의 어두운 톤으로
    out, pos = [], 0
    pat = re.compile(r'<(div|button|a)\b[^>]*style="[^"]*background: (%s)[;"]' % '|'.join(map(re.escape, SURF)))
    for m in pat.finditer(s):
        if m.start() < pos: continue
        end = element_end(s, m.start()); block = s[m.start():end]; surf = m.group(2)
        n = block.count('color: rgba(17,17,17,.62)'); LOG['small-text-tone'] += n
        block = block.replace('color: rgba(17,17,17,.62)', 'color: %s' % SURF[surf][1])
        out.append(s[pos:m.start()]); out.append(block); pos = end
    out.append(s[pos:]); return ''.join(out)

def apply(s):
    for old, surf in OLD_SEC.items():
        LOG['headline-2nd'] += s.count('color: %s' % old)
        s = s.replace('color: %s' % old, 'color: %s' % SURF[MAP[surf]][0])
    for a, b in list(MAP.items()) + list(PALE.items()):
        LOG['surface'] += len(re.findall(re.escape(a), s, re.I)); s = re.sub(re.escape(a), b, s, flags=re.I)
    # 목적 점 테두리(먹색 20%)는 옅은 면에서도 흰 바탕 위 점이 보이도록 남긴다
    s = recolor_inside(s)
    for a, b in NEUTRAL.items():
        LOG['neutral'] += len(re.findall(re.escape(a), s, re.I)); s = re.sub(re.escape(a), b, s, flags=re.I)
    return s

PKEY = {'closet': '보라', 'run': '하늘', 'lamp': '주황'}
def purpose_detail(s):
    # 목적 상세 머리 면을 목적 색으로 (아카이브 상세와 같은 형태)
    a = '<div style="padding: 58px 20px 0; display: flex; flex-direction: column; gap: 18px; flex-shrink: 0">'
    assert s.count(a) >= 1
    s = s.replace(a, '<div style="background: {{ pcolor }}; border-radius: 0 0 32px 32px; padding: 58px 20px 26px; display: flex; flex-direction: column; gap: 14px; flex-shrink: 0">', 1)
    b = 'color: rgba(17,17,17,.5)">{{ line2 }}</span></h1>'
    assert s.count(b) == 1; s = s.replace(b, 'color: {{ psec }}">{{ line2 }}</span></h1>')
    c = '<p style="margin: -10px 0 0; font-size: 13px; line-height: 19px; color: rgba(17,17,17,.62)">{{ desc }}</p>'
    assert s.count(c) == 1; s = s.replace(c, c.replace('margin: -10px 0 0', 'margin: -6px 0 0').replace('rgba(17,17,17,.62)', '{{ plab }}'))
    d = 'width: 8px; height: 8px; border-radius: 999px; background: %s' % S['보라']['h']
    LOG['pd-dot'] += s.count(d); s = s.replace(d, 'width: 8px; height: 8px; border-radius: 999px; background: {{ pcolor }}')
    js = 'v.pname = s.pname;'
    assert s.count(js) == 1
    table = ', '.join("%s: ['%s', '%s', '%s']" % (k, S[n]['h'], SURF[S[n]['h']][0], SURF[S[n]['h']][1]) for k, n in PKEY.items())
    s = s.replace(js, "const PC = { %s }; const pc = PC[s.key] || PC.closet; v.pcolor = pc[0]; v.psec = pc[1]; v.plab = pc[2]; " % table + js)
    LOG['pd-header'] += 1
    return s

for f in sorted(os.listdir(SRC)):
    if not (f.startswith('UI-') and f.endswith('.dc.html')): continue
    s = open(os.path.join(SRC, f)).read()
    s = apply(s)
    if f == 'UI-PurposeDetail.dc.html': s = purpose_detail(s)
    open(os.path.join(OUT, f), 'w').write(s)
print(dict(LOG)); print('MAP', MAP, 'PALE', PALE); print('SURF', SURF)
