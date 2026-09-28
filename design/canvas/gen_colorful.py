# 디자인 시스템 · 색 후보 보드 생성기: 세트 견본, 세트별 목적 탭·목적 상세, 바탕 후보별 홈
import os, re, sys
from palette_sets import SETS, contrast
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
from canvas_src import helmet as _helmet, tab_nav, purpose_data, board
helmet = _helmet('PT-A8')
pt_nav = tab_nav('PT-A8')
MONO = "font-family: 'JetBrains Mono', Pretendard, monospace; font-size: 10px; letter-spacing: .1em"
GS = "font-family: 'Space Grotesk', Pretendard, sans-serif"
LABEL = {'M': '중간', 'MD': '중간 + 짙은 색', 'S': '강하게', 'SD': '강하게 + 짙은 색', 'P': '확정 톤', 'PS': '확정 톤 +'}
BY = {k: {c['n']: c for c in v} for k, v in SETS.items()}

def page(title, w, h, body, script='renderVals() { return {}; }', bg='#F7F7F3'):
    hm = helmet.replace('background:#F7F7F3', 'background:%s' % bg)
    return f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
{hm}
{body}
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview": {{"width": {w}, "height": {h}}}}}'>
class Component extends DCLogic {{
{script}
}}
</script>
</body>
</html>
'''

# 1. 세트 견본
SW, SH, G = 150, 118, 10
def swatch(c):
    return (f'<div style="width: {SW}px; height: {SH}px; box-sizing: border-box; border-radius: 18px; background: {c["h"]}; padding: 12px 14px; display: flex; flex-direction: column; justify-content: space-between; color: {c["fg"]}">'
            f'<span style="display: flex; flex-direction: column; gap: 1px"><span style="font-size: 16px; font-weight: 700; letter-spacing: -0.02em">{c["n"]}</span>'
            f'<span style="font-size: 12px; font-weight: 700; color: {c["sec"]}">둘째 줄 글자</span></span>'
            f'<span style="display: flex; justify-content: space-between; align-items: baseline"><span style="{MONO}">{c["h"]}</span>'
            f'<span style="{MONO}; opacity: .7">C{c["C"]:.2f}</span></span></div>')
def section(key, desc):
    light = [c for c in SETS[key + 'D'] if not c['deep']]; deep = [c for c in SETS[key + 'D'] if c['deep']]
    grid = lambda cs: f'<div style="display: grid; grid-template-columns: repeat(7, {SW}px); gap: {G}px">' + ''.join(swatch(c) for c in cs) + '</div>'
    return (f'<div style="display: flex; flex-direction: column; gap: 14px"><div style="display: flex; align-items: baseline; gap: 12px">'
            f'<span style="font-size: 22px; font-weight: 700; letter-spacing: -0.03em">{LABEL[key]}</span><span style="font-size: 13px; color: rgba(17,17,17,.62)">{desc}</span></div>'
            + grid(light) + f'<span style="font-size: 12px; font-weight: 700; color: rgba(17,17,17,.62); padding-top: 4px">짙은 색 (흰 글자) · 짙은 색 세트에만 들어가요</span>' + grid(deep) + '</div>')
SWW = 40 * 2 + SW * 7 + G * 6; SWH = 1120
sheet = (f'<div style="width: {SWW}px; height: {SWH}px; box-sizing: border-box; padding: 40px; background: #F7F7F3; color: #111111; display: flex; flex-direction: column; gap: 36px">'
         '<div style="display: flex; flex-direction: column; gap: 8px"><h1 style="margin: 0; font-size: 34px; line-height: 40px; font-weight: 700; letter-spacing: -0.035em">블록 색 · 컬러풀 세트</h1>'
         '<span style="font-size: 14px; line-height: 20px; color: rgba(17,17,17,.62)">레퍼런스처럼 선명한 색 11개와 쉬어 가는 색 3개(토프·베이지·회색)로 짰어요. 글자색은 면 밝기로 정하고(대비 4.5:1 이상), 둘째 줄은 같은 색의 어두운 톤이에요(3:1 이상).</span></div>'
         + section('M', '채도 .08~.14 · 밝기 .79~.91 · 메모 앱 느낌') + section('S', '채도 .14~.18 · 밝기 .65~.89 · 스터디 카드·원형 느낌') + '</div>')
open(os.path.join(OUT, 'CP-SWATCH.dc.html'), 'w').write(page('블록 색 · 컬러풀 세트', SWW, SWH, sheet))

# 2. 세트별 목적 탭
ORDER = ['보라', '하늘', '코랄', '올리브', '연두', '분홍', '민트', '짙은 보라', '노랑', '파랑', '토프', '벽돌', '주황', '초록', '라일락', '남색', '베이지', '회색']
PURP = [('가을 옷장', '출근할 때 입을 옷'), ('가을 트레일 러닝', '주말 산길용'), ('거실 조명', ''), ('홈카페', '아침 커피 도구'), ('여름 휴가', '8월 제주'),
        ('책상 정리', ''), ('캠핑 장비', '가벼운 1박'), ('겨울 아우터', '출퇴근용 패딩'), ('주방 용품', ''), ('선물 목록', '엄마 생신'), ('러닝 워치', ''),
        ('침구', '가을 이불'), ('출근 가방', '노트북 들어가는 것'), ('향수', ''), ('운동화', '매일 신을 것'), ('노트북', '가벼운 것'), ('화분', ''), ('안경', '')]
_D, _P = purpose_data()
IMGS = [d['img'].split('/')[-1] for d in _D]
def header(title, line2, right):
    return ('<div style="padding: 58px 20px 0; display: flex; flex-direction: column; gap: 18px"><div style="height: 44px; display: flex; align-items: center; justify-content: space-between">'
            '<div style="display: flex; align-items: center; gap: 10px"><span style="width: 10px; height: 10px; border-radius: 999px; background: #111111"></span>'
            f'<span style="font-size: 14px; font-weight: 700">{title}</span></div>{right}</div>'
            f'<h1 style="margin: 0; font-size: 22px; line-height: 30px; font-weight: 700; letter-spacing: -0.035em"><span style="display: block; white-space: nowrap">{line2[0]}</span>'
            f'<span style="display: block; white-space: nowrap; color: rgba(17,17,17,.5)">{line2[1]}</span></h1></div>')
TOGGLE = ('<div role="tablist" aria-label="목적 보기" style="display: flex; padding: 3px; border-radius: 999px; background: #E9E9E3">'
          '<button role="tab" aria-selected="true" style="height: 38px; padding: 0 12px; border-radius: 999px; border: 0; background: #111111; color: #FFFFFF; font-size: 12px; font-weight: 700">진행 중</button>'
          '<button role="tab" aria-selected="false" style="height: 38px; padding: 0 12px; border-radius: 999px; border: 0; background: transparent; color: #111111; font-size: 12px; font-weight: 700">아카이브 2</button></div>')
def purpose_tab(key, order=ORDER):
    cs = [BY[key][n] for n in order if n in BY[key]]
    blocks = []
    for k, c in enumerate(cs):
        name, desc = PURP[k]; cnt = [3, 2, 0, 2, 1, 0, 3, 2, 1, 3, 0, 2, 1, 1, 2, 1, 0, 2][k]
        left = ('<span style="display: flex; flex-direction: column; gap: 3px; min-width: 0">'
                + (f'<span style="{MONO}; color: {c["sec"]}">{desc}</span>' if desc else '')
                + f'<span style="font-size: 17px; font-weight: 700; letter-spacing: -0.02em">{name}</span></span>')
        if cnt:
            th = ''.join(f'<span style="display: inline-flex; margin-left: {0 if j == 0 else -8}px; border-radius: 999px; box-shadow: 0 0 0 2px {c["h"]}">'
                         f'<span style="width: 30px; height: 30px; border-radius: 999px; overflow: hidden; background: #FFFFFF; display: inline-flex">'
                         f'<img src="/_blob/{IMGS[(k * 2 + j) % len(IMGS)]}" alt="" style="width: 100%; height: 100%; object-fit: cover; display: block"></span></span>' for j in range(cnt))
        else:
            th = (f'<span style="width: 30px; height: 30px; border-radius: 999px; box-sizing: border-box; border: 1.5px dashed {c["sec"]}; display: inline-flex; '
                  f'align-items: center; justify-content: center; {GS}; font-size: 12px; color: {c["sec"]}">0</span>')
        blocks.append(f'<button style="border: 0; background: {c["h"]}; border-radius: 20px; padding: 16px; min-height: 76px; color: {c["fg"]}; text-align: left; display: flex; '
                      f'justify-content: space-between; align-items: center; gap: 12px; width: 100%">{left}<span style="display: flex; align-items: center; flex-shrink: 0">{th}</span></button>')
    body = ('<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: #F7F7F3; color: #111111"><div class="scroll" style="height: 844px; overflow-y: auto">'
            + header('목적', ('고르는 중인 목적이 %d개 있어요.' % len(cs), '후보를 비교하고 결정을 끝내요.'), TOGGLE)
            + '<div style="padding: 20px 6px 120px; display: flex; flex-direction: column; gap: 6px">' + ''.join(blocks) + '</div></div>' + pt_nav + '</div>')
    return page('목적 탭 · %s' % LABEL[key], 390, 844, body)

# 3. 세트별 목적 상세: 머리 면을 목적 색으로 (아카이브 상세와 같은 형태)
ITEMS = [(_D[k]['n'], _D[k]['p'], _D[k]['img'].split('/')[-1], _D[k]['h']) for k in _P['closet']['items']]
def card(n, p, img, h):
    return (f'<div style="display: flex; flex-direction: column; gap: 6px"><span style="width: 187px; height: {h}px; border-radius: 16px; overflow: hidden; background: #FFFFFF; display: inline-flex">'
            f'<img src="/_blob/{img}" alt="" style="width: 100%; height: 100%; object-fit: cover; display: block"></span>'
            f'<span style="display: flex; align-items: baseline; gap: 6px; padding: 0 4px"><span style="font-size: 13px; font-weight: 700; flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{n}</span>'
            f'<span style="{GS}; font-size: 13px; font-weight: 500; white-space: nowrap">{p}</span></span></div>')
def purpose_detail(key):
    c = BY[key]['짙은 보라' if key.endswith('D') else '보라']; fg = c['fg']
    L = [ITEMS[0], ITEMS[2], ITEMS[4]]; R = [ITEMS[1], ITEMS[3]]
    col = lambda xs: '<div style="flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 10px">' + ''.join(card(*x) for x in xs) + '</div>'
    back = (f'<button style="height: 44px; padding: 0 10px 0 0; border: 0; background: transparent; color: {fg}; display: flex; align-items: center; font-size: 14px; font-weight: 700">'
            f'<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" style="margin-left: -5px; margin-right: 5px"><path d="M15 5l-7 7 7 7"></path></svg>목적</button>')
    more = (f'<button aria-label="목적 메뉴" style="width: 44px; height: 44px; margin-right: -10px; border: 0; background: transparent; color: {fg}; display: flex; align-items: center; justify-content: center">'
            '<svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><circle cx="5" cy="12" r="1.4"></circle><circle cx="12" cy="12" r="1.4"></circle><circle cx="19" cy="12" r="1.4"></circle></svg></button>')
    head = (f'<div style="background: {c["h"]}; color: {fg}; border-radius: 0 0 32px 32px; padding: 58px 20px 26px; display: flex; flex-direction: column; gap: 14px">'
            f'<div style="height: 44px; display: flex; align-items: center; justify-content: space-between">{back}{more}</div>'
            f'<div><h1 style="margin: 0; font-size: 22px; line-height: 30px; font-weight: 700; letter-spacing: -0.035em"><span style="display: block">가을 옷장</span>'
            f'<span style="display: block; color: {c["sec"]}">후보 5개를 비교하고 있어요.</span></h1>'
            f'<p style="margin: 8px 0 0; font-size: 13px; line-height: 19px; color: {c["sec"]}">출근할 때 입을 옷</p></div></div>')
    label = (f'<div style="padding: 20px 20px 0; display: flex; justify-content: space-between; align-items: baseline"><span style="display: flex; align-items: center; gap: 8px">'
             f'<span style="width: 8px; height: 8px; border-radius: 999px; background: {c["h"]}"></span><span style="{MONO}; color: rgba(17,17,17,.62)">최근 저장순</span></span>'
             f'<span style="{GS}; font-size: 13px; color: rgba(17,17,17,.62)">5</span></div>')
    fab = ('<div style="position: absolute; left: 0; right: 0; bottom: 96px; display: flex; justify-content: center"><button style="border-radius: 999px; border: 0; background: #111111; color: #FFFFFF; '
           'font-size: 13px; font-weight: 700; padding: 12px 16px; box-shadow: 0 8px 24px -8px rgba(17,17,17,.4)">비교 끝내기</button></div>')
    body = ('<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: #F7F7F3; color: #111111; display: flex; flex-direction: column">'
            + head + label + '<div class="scroll" style="flex: 1 1 auto; min-height: 0; overflow-y: auto"><div style="padding: 14px 6px 200px; display: flex; gap: 4px; align-items: flex-start">'
            + col(L) + col(R) + '</div></div>' + fab + pt_nav + '</div>')
    return page('목적 상세 · %s' % LABEL[key], 390, 844, body)

for key in ['M', 'MD', 'S', 'SD']:
    open(os.path.join(OUT, 'CP-PT-%s.dc.html' % key), 'w').write(purpose_tab(key))
    open(os.path.join(OUT, 'CP-PD-%s.dc.html' % key), 'w').write(purpose_detail(key))

# 4. 바탕 후보: 확정 홈(P-D)을 중간 세트 색으로 칠하고 바탕만 바꿈
BGS = [('F7F7F3', '#F7F7F3', '지금 · 살짝 따뜻한 회백색'), ('GRAY', '#EDEDED', '순수 회색 (레퍼런스 ①~③)'), ('CREAM', '#EFECDF', '따뜻한 크림 (레퍼런스 ④)'), ('WHITE', '#FFFFFF', '흰색 (레퍼런스 ⑥)')]
home = board('P-D')
MC = BY['M']
home = home.replace('#D9EC9A', MC['연두']['h']).replace('#F4E4A6', MC['노랑']['h']).replace('#CFDFD4', MC['하늘']['h'])
for code, bg, label in BGS:
    s = re.sub(r'#F7F7F3', bg, home, flags=re.I)
    s = re.sub(r'<title>[^<]*</title>', '<title>바탕 후보 · %s</title>' % label, s, count=1)
    open(os.path.join(OUT, 'CP-BG-%s.dc.html' % code), 'w').write(s)

# 5. 확정 톤 후보: `확정 디자인` 블록 색 느낌(P)과 한 단계 선명하게(PS)를 강하게 세트(S)와 비교
# 확정 톤은 셋째 목적 색이 살구(주황)였으므로 코랄·주황 순서를 바꾼다
ORDER_P = [{'코랄': '주황', '주황': '코랄'}.get(n, n) for n in ORDER]
def section_flat(key, desc):
    grid = f'<div style="display: grid; grid-template-columns: repeat(7, {SW}px); gap: {G}px">' + ''.join(swatch(c) for c in SETS[key]) + '</div>'
    return (f'<div style="display: flex; flex-direction: column; gap: 14px"><div style="display: flex; align-items: baseline; gap: 12px">'
            f'<span style="font-size: 22px; font-weight: 700; letter-spacing: -0.03em">{LABEL[key]}</span><span style="font-size: 13px; color: rgba(17,17,17,.62)">{desc}</span></div>' + grid + '</div>')
SWH_P = 1000
sheet = (f'<div style="width: {SWW}px; height: {SWH_P}px; box-sizing: border-box; padding: 40px; background: #F7F7F3; color: #111111; display: flex; flex-direction: column; gap: 36px">'
         '<div style="display: flex; flex-direction: column; gap: 8px"><h1 style="margin: 0; font-size: 34px; line-height: 40px; font-weight: 700; letter-spacing: -0.035em">블록 색 · 확정 톤 후보</h1>'
         '<span style="font-size: 14px; line-height: 20px; color: rgba(17,17,17,.62)">확정 디자인의 블록 6색과 같은 톤으로 14색을 맞췄어요. 이름·역할은 강하게 세트와 같고, 모든 면 위 글자는 먹색이에요.</span></div>'
         + section_flat('P', '채도 .02~.11 · 밝기 .86~.92 · 확정 6색 + PAL-15')
         + section_flat('PS', '확정 톤에서 밝기를 조금 낮추고 채도를 한 단계 · 채도 .06~.13')
         + section_flat('S', '지금 기록된 세트 · 채도 .10~.18 · 비교용') + '</div>')
open(os.path.join(OUT, 'CP-SWATCH-P.dc.html'), 'w').write(page('블록 색 · 확정 톤 후보', SWW, SWH_P, sheet))
for key in ['P', 'PS']:
    open(os.path.join(OUT, 'CP-PT-%s.dc.html' % key), 'w').write(purpose_tab(key, ORDER_P))
    open(os.path.join(OUT, 'CP-PD-%s.dc.html' % key), 'w').write(purpose_detail(key))
home0 = board('P-D')
for key in ['P', 'PS', 'S']:
    c = BY[key]
    h = home0.replace('#D9EC9A', c['연두']['h']).replace('#F4E4A6', c['노랑']['h']).replace('#CFDFD4', c['하늘']['h'])
    h = re.sub(r'<title>[^<]*</title>', '<title>홈 · %s</title>' % LABEL[key], h, count=1)
    open(os.path.join(OUT, 'CP-HOME-%s.dc.html' % key), 'w').write(h)
print('ok', SWW, SWH, SWH_P)
