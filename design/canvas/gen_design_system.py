# 디자인 시스템 페이지 보드 생성기: 확정 보드에서 쓰는 값과 공통 요소를 모은다.
# 사용: python3 gen_design_system.py <확정 보드 폴더> <출력 폴더>
# 값은 확정 보드(2026-09-28, 캔버스 버전 295)에서 센 것이다. 새로 정한 값이 아니다.
import os, re, sys
from palette_sets import SETS, INK, tone
from oklch import contrast
SRC, OUT = sys.argv[1], sys.argv[2]; os.makedirs(OUT, exist_ok=True)

P_D = open(os.path.join(SRC, 'P-D.dc.html')).read()
HELMET = P_D[P_D.index('<helmet>'):P_D.index('</helmet>') + len('</helmet>')]
NAV = P_D[P_D.index('<nav aria-label="주요 메뉴"'):P_D.index('</nav>') + len('</nav>')]
NAV = re.sub(r'position: absolute; left: 20px; right: 20px; bottom: 28px; ', 'width: 350px; ', NAV)
PT = open(os.path.join(SRC, 'PT-A8.dc.html')).read()
IMGS = list(dict.fromkeys(re.findall(r'/_blob/([0-9a-f]{32})', PT[PT.index('</helmet>'):])))

W = 1248
GS = "font-family: 'Space Grotesk', Pretendard, sans-serif"
LM = "font-family: 'Label Mono', Pretendard, sans-serif; font-size: 11px; font-weight: 500; letter-spacing: .02em"
DIM = 'rgba(17,17,17,.62)'
S = {c['n']: c for c in SETS['PS']}
ORDER = ['보라', '하늘', '주황', '연두', '분홍', '민트', '노랑', '파랑', '토프', '코랄', '초록', '라일락', '베이지', '회색']
NAME = {'주황': '살구'}

def svg(d, size=16, sw=1.8):
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="{sw}" '
            f'stroke-linecap="round" stroke-linejoin="round">{d}</svg>')
I_X = '<path d="M6 6l12 12"></path><path d="M18 6L6 18"></path>'
I_DOWN = '<path d="M6 9l6 6 6-6"></path>'
I_RIGHT = '<path d="M9 5l7 7-7 7"></path>'
I_ARROW = '<path d="M5 12h14"></path><path d="M13 6l6 6-6 6"></path>'
I_PLUS = '<path d="M12 5v14"></path><path d="M5 12h14"></path>'
I_EDIT = '<path d="M4 20h4L19 9l-4-4L4 16z"></path><path d="M13.5 6.5l4 4"></path>'
I_TRASH = '<path d="M4 7h16"></path><path d="M9 7V4.5h6V7"></path><path d="M6 7l1 13h10l1-13"></path>'
I_BOX = '<rect x="3" y="4" width="18" height="5" rx="1.5"></rect><path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9"></path><path d="M10 13h4"></path>'
I_SPARK = '<path d="M12 3v18"></path><path d="M3 12h18"></path><path d="M5.6 5.6l12.8 12.8"></path><path d="M18.4 5.6L5.6 18.4"></path>'
I_CHECK = '<path d="M5 12l5 5 9-10"></path>'

def page(name, title, h, body):
    html = f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
{HELMET}
<div style="width: {W}px; height: {h}px; box-sizing: border-box; overflow: hidden; padding: 40px; background: #F7F7F3; color: #111111; display: flex; flex-direction: column; gap: 36px">{body}</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview": {{"width": {W}, "height": {h}}}}}'>
class Component extends DCLogic {{
renderVals() {{ return {{}}; }}
}}
</script>
</body>
</html>
'''
    open(os.path.join(OUT, name + '.dc.html'), 'w').write(html)

def head(title, desc):
    return (f'<div style="display: flex; flex-direction: column; gap: 8px"><h1 style="margin: 0; font-size: 34px; line-height: 40px; font-weight: 700; letter-spacing: -0.035em">{title}</h1>'
            f'<span style="font-size: 14px; line-height: 20px; color: {DIM}">{desc}</span></div>')
def sec(title, inner, note=''):
    n = f'<span style="font-size: 13px; color: {DIM}">{note}</span>' if note else ''
    return (f'<section style="display: flex; flex-direction: column; gap: 14px"><div style="display: flex; align-items: baseline; gap: 12px">'
            f'<h2 style="margin: 0; font-size: 20px; font-weight: 700; letter-spacing: -0.03em">{title}</h2>{n}</div>{inner}</section>')
def mono(t, color=DIM):
    return f'<span style="{LM}; color: {color}">{t}</span>'
def cell(label, inner, w=None):
    ws = f'width: {w}px; ' if w else ''
    return (f'<div style="{ws}display: flex; flex-direction: column; gap: 10px; align-items: flex-start">{inner}'
            f'<span style="font-size: 12px; line-height: 16px; color: {DIM}">{label}</span></div>')
def row(items, gap=24, wrap=True):
    return f'<div style="display: flex; gap: {gap}px; align-items: flex-start; {"flex-wrap: wrap" if wrap else ""}">' + ''.join(items) + '</div>'

# 1. 색 -------------------------------------------------------------------
NEU = [('카드', '#FFFFFF', '흰 카드, 시트, 검은 버튼 위 글자'), ('바탕', '#F7F7F3', '화면 바탕'), ('옅은 면', '#F2F2EE', '원형 버튼, 작은 사진 바탕'),
       ('칩', '#EAEAE4', '전환 버튼 바탕, 필터 pill'), ('비활성 면', '#E4E4DE', '비활성 버튼 바탕'), ('보조', '#A0A099', '› 화살표, 비활성 글자'), ('먹색', '#111111', '글자, 검은 버튼')]
def neu(n, h, use):
    return (f'<div style="width: 150px; display: flex; flex-direction: column; gap: 8px"><span style="height: 72px; border-radius: 16px; background: {h}; box-shadow: inset 0 0 0 1px rgba(17,17,17,.08)"></span>'
            f'<span style="font-size: 14px; font-weight: 700">{n}</span>{mono(h, INK)}<span style="font-size: 12px; line-height: 16px; color: {DIM}">{use}</span></div>')
ALPHA = [('62%', '보조 글자·설명', 'text'), ('50%', '헤드라인 둘째 줄', 'text'), ('30%', '흐린 글자 (구매 안 한 후보)', 'text'),
         ('20%', '테두리, 목적 점 테두리', 'line'), ('10%', '구분선', 'line'), ('6%', '옅은 구분선', 'line')]
def alpha(p, use, kind):
    a = int(p[:-1]) / 100
    demo = (f'<span style="font-size: 15px; font-weight: 700; color: rgba(17,17,17,{a})">글자 예시 Aa 123</span>' if kind == 'text'
            else f'<span style="display: block; width: 150px; height: 0; border-top: 1px solid rgba(17,17,17,{a}); margin: 9px 0"></span>')
    return (f'<div style="width: 180px; display: flex; flex-direction: column; gap: 6px">{demo}<span style="font-size: 14px; font-weight: 700">먹색 {p}</span>'
            f'<span style="font-size: 12px; color: {DIM}">{use}</span></div>')
def block(n):
    c = S[n]; h = c['h']; t3, t45 = tone(h, 3.0), tone(h, 4.5)
    return (f'<div style="width: 152px; height: 124px; box-sizing: border-box; border-radius: 20px; background: {h}; padding: 12px 14px; display: flex; flex-direction: column; justify-content: space-between">'
            f'<span style="display: flex; flex-direction: column; gap: 2px"><span style="font-size: 16px; font-weight: 700">{NAME.get(n, n)}</span>'
            f'<span style="font-size: 13px; font-weight: 700; color: {t3}">둘째 줄 {t3}</span><span style="font-size: 11px; color: {t45}">작은 글자 {t45}</span></span>'
            f'{mono(h, INK)}</div>')
dots = ''.join(f'<span style="display: flex; flex-direction: column; align-items: center; gap: 6px; width: 44px"><span style="width: 28px; height: 28px; border-radius: 999px; background: {S[n]["h"]}; box-shadow: 0 0 0 1px rgba(17,17,17,.2)"></span>'
               f'<span style="font-size: 11px; color: {DIM}">{k + 1}</span></span>' for k, n in enumerate(ORDER))
danger = (f'<div style="display: flex; gap: 24px; align-items: center">'
          f'<span style="width: 36px; height: 36px; border-radius: 12px; background: #FDECEA; color: #B42318; display: flex; align-items: center; justify-content: center">{svg(I_TRASH, 18)}</span>'
          f'<span style="font-size: 16px; font-weight: 700; color: #B42318">기록 지우기</span>'
          f'<span style="font-size: 12px; color: {DIM}">위험 글자·버튼 {mono("#B42318", INK)} · 아이콘 칸 {mono("#FDECEA", INK)}</span></div>')
scrim = (f'<div style="position: relative; width: 360px; height: 120px; border-radius: 20px; overflow: hidden; background: #F7F7F3">'
         f'<div style="position: absolute; left: 16px; top: 16px; display: flex; gap: 8px">' + ''.join(f'<span style="width: 70px; height: 88px; border-radius: 16px; background: {S[n]["h"]}"></span>' for n in ['보라', '하늘', '주황', '연두']) + '</div>'
         f'<div style="position: absolute; inset: 0; background: rgba(247,247,243,.4); backdrop-filter: blur(6px); -webkit-backdrop-filter: blur(6px)"></div>'
         f'<span style="position: absolute; right: 14px; bottom: 12px; font-size: 12px; font-weight: 700">바탕 40% + 흐림 6px</span></div>')
page('DS-COLOR', '디자인 시스템 · 색', 1360,
     head('색', '확정 보드에서 쓰는 색이에요. 화면 틀은 무채색이고, 색은 블록 면에만 써요. 값과 대비는 docs/architecture/client/color-palette.md에 있어요.')
     + sec('무채색 단계', row([neu(*x) for x in NEU], 12, False), '바탕 #F7F7F3의 색조로 맞춘 단계 (A안)')
     + sec('먹색 투명도', row([alpha(*x) for x in ALPHA], 16), '보조 글자와 선은 따로 회색을 두지 않아요')
     + sec('블록 색 · 확정 톤 +', f'<div style="display: grid; grid-template-columns: repeat(7, 152px); gap: 12px">' + ''.join(block(n) for n in ORDER) + '</div>',
           '면 위 글자는 먹색, 둘째 줄은 3:1, 작은 글자는 4.5:1 어두운 톤')
     + sec('역할', row([cell('홈 챙길 일: 분류·목적 확인 / 정보 보완 필요 / 정보 가져오는 중',
                             row([f'<span style="width: 96px; height: 56px; border-radius: 20px; background: {S[n]["h"]}"></span>' for n in ['연두', '노랑', '하늘']], 8)),
                        cell('목적 색 순서: 앱이 차례로 붙여요. 흰 면 위 점은 먹색 20% 테두리', row([dots], 0))], 48))
     + sec('위험 · 덮개', row([danger, scrim], 60)))

# 2. 글꼴 -----------------------------------------------------------------
TYPE = [
    ('헤드라인', 'font-size: 22px; line-height: 30px; font-weight: 700; letter-spacing: -0.035em', '챙길 일이 6개 있어요.', 'Pretendard 22/30 · 700 · -0.035em · 둘째 줄 먹색 50%'),
    ('큰 숫자', f'{GS}; font-size: 40px; line-height: 34px; font-weight: 500; letter-spacing: -0.04em', '3', 'Space Grotesk 40/34 · 500 · 홈 블록 개수'),
    ('단계 숫자', f'{GS}; font-size: 30px; line-height: 28px; font-weight: 500; letter-spacing: -0.04em', '01', 'Space Grotesk 30/28 · 500 · 빈 홈 저장 방법'),
    ('목차 제목', 'font-size: 20px; font-weight: 700; letter-spacing: -0.03em', '신발', 'Pretendard 20 · 700 · 카테고리 목차'),
    ('중간 숫자', f'{GS}; font-size: 20px; font-weight: 500; letter-spacing: -0.03em', '1', 'Space Grotesk 20 · 500 · 한 줄 블록 개수'),
    ('블록 이름', 'font-size: 17px; font-weight: 700; letter-spacing: -0.02em', '가을 옷장', 'Pretendard 17 · 700 · -0.02em · 목적·아카이브 블록'),
    ('시트 제목·항목', 'font-size: 16px; font-weight: 700', '이름과 설명 바꾸기', 'Pretendard 16 · 700'),
    ('버튼·블록 제목', 'font-size: 15px; font-weight: 700', '비교 끝내기', 'Pretendard 15 · 700 · 큰 버튼, 홈 블록 제목, 메뉴 항목'),
    ('큰 가격', f'{GS}; font-size: 15px; font-weight: 500', '₩159,000', 'Space Grotesk 15 · 500 · 홈 가로 카드'),
    ('작은 제목', 'font-size: 14px; font-weight: 700', '내 위시리스트', 'Pretendard 14 · 700 · 화면 이름, 탭, 알림'),
    ('목록 이름', 'font-size: 13px; line-height: 17px; font-weight: 700', 'T.T 팬츠 차콜', 'Pretendard 13/17 · 700 · 최대 두 줄'),
    ('가격', f'{GS}; font-size: 13px; font-weight: 500', '₩190,000', 'Space Grotesk 13 · 500 · 목록·시트 가격, 개수'),
    ('본문', 'font-size: 13px; line-height: 19px', '출근할 때 입을 옷', 'Pretendard 13/19 · 400 · 설명'),
    ('시트 설명', 'font-size: 13px; font-weight: 500; color: rgba(17,17,17,.62)', '목적 이름과 설명을 고쳐요', 'Pretendard 13 · 500 · 먹색 62%'),
    ('보조', 'font-size: 12px; line-height: 16px; color: rgba(17,17,17,.62)', 'AI가 정리해 둔 걸 확인해 주세요', 'Pretendard 12/16 · 400 · 먹색 62%'),
    ('전환 버튼', 'font-size: 12px; font-weight: 700', '진행 중', 'Pretendard 12 · 700'),
    ('라벨', f'{LM}; color: rgba(17,17,17,.62)', '9월 20일 종료 · 최근 저장순', 'Label Mono 11 · 500 · .02em · 숫자·영문 JetBrains Mono, 한글 Pretendard'),
    ('상태 표시', 'font-size: 11px; font-weight: 700', '✓ 구매한 상품', 'Pretendard 11 · 700'),
]
def trow(n, st, sample, spec):
    return (f'<div style="display: grid; grid-template-columns: 150px 1fr 420px; gap: 24px; align-items: center; padding: 14px 0; border-top: 1px solid rgba(17,17,17,.1)">'
            f'<span style="font-size: 14px; font-weight: 700">{n}</span><span style="{st}; white-space: nowrap">{sample}</span>'
            f'<span style="font-size: 12px; line-height: 16px; color: {DIM}">{spec}</span></div>')
FONTS = row([cell('한글·본문', '<span style="font-size: 30px; font-weight: 700; letter-spacing: -0.03em">Pretendard 가나다</span>'),
             cell('큰 숫자·가격', f'<span style="{GS}; font-size: 30px; font-weight: 500; letter-spacing: -0.03em">Space Grotesk 0123</span>'),
             cell('라벨 (숫자·영문만 고정폭)', f'<span style="font-family: \'Label Mono\', Pretendard, sans-serif; font-size: 30px; font-weight: 500">Label 09 라벨</span>')], 56)
page('DS-TYPE', '디자인 시스템 · 글꼴', 1260,
     head('글꼴', '확정 보드에서 쓰는 글꼴과 크기 단계예요. 한글은 단어 중간에서 줄바꾸지 않아요(keep-all).')
     + sec('글꼴', FONTS) + sec('크기 단계', '<div style="display: flex; flex-direction: column">' + ''.join(trow(*t) for t in TYPE) + '</div>'))

# 3. 간격·모서리·그림자 -----------------------------------------------------
SP = [2, 3, 4, 6, 8, 10, 12, 14, 18, 20]
sp = ''.join(f'<div style="display: flex; flex-direction: column; gap: 8px; align-items: flex-start; width: 64px"><span style="width: {v}px; height: 40px; background: {S["보라"]["h"]}; border-radius: 2px"></span>'
             f'<span style="{GS}; font-size: 15px; font-weight: 500">{v}</span></div>' for v in SP)
LAYOUT = [('20', '화면 글자 여백: 헤드라인·라벨·뒤로 가기의 양옆'), ('6', '목록 양옆: 상품 목록과 블록 목록'), ('4 / 10', '상품 목록 열 사이 / 행 사이 (카드 폭 187)'),
          ('6', '블록 사이'), ('58', '화면 위 여백 (상태 표시줄 포함)'), ('12 / 16', '떠 있는 시트 양옆 / 아래'), ('20 / 28', '하단 탭 양옆 / 아래'), ('44', '누르는 영역 최소 크기')]
lay = ''.join(f'<div style="display: grid; grid-template-columns: 110px 1fr; gap: 16px; padding: 10px 0; border-top: 1px solid rgba(17,17,17,.1)">'
              f'<span style="{GS}; font-size: 18px; font-weight: 500">{a}</span><span style="font-size: 13px; line-height: 20px">{b}</span></div>' for a, b in LAYOUT)
RAD = [('999', '버튼, pill, 탭, 원형 버튼', 120, 44), ('32', '머리 면 아래 모서리', 120, 70), ('28', '떠 있는 시트', 120, 90), ('20', '블록, 길게 누르기 메뉴', 120, 90),
       ('16', '상품 사진·카드', 120, 90), ('12', '36px 아이콘 칸', 36, 36), ('9', '30px 아이콘 칸', 30, 30)]
def rad(v, use, w, h):
    r = '0 0 32px 32px' if v == '32' else v + 'px'
    return cell(f'{v} · {use}', f'<span style="display: block; width: {w}px; height: {h}px; border-radius: {r}; background: #FFFFFF; box-shadow: inset 0 0 0 1px rgba(17,17,17,.12)"></span>', 140)
SH = [('0 12px 40px -12px rgba(17,17,17,.25)', '떠 있는 시트'), ('0 16px 48px -12px rgba(17,17,17,.3)', '길게 누르기 메뉴'), ('0 8px 24px -8px rgba(17,17,17,.4)', '떠 있는 검은 버튼')]
sh = row([cell(f'{u}<br>{v}', f'<span style="display: block; width: 180px; height: 80px; border-radius: 20px; background: #FFFFFF; box-shadow: {v}"></span>', 240) for v, u in SH], 40)
page('DS-SPACE', '디자인 시스템 · 간격과 모서리', 1120,
     head('간격 · 모서리 · 그림자', '확정 보드에서 쓰는 간격 단계, 화면 배치 규칙, 모서리와 그림자예요.')
     + sec('간격 단계', f'<div style="display: flex; gap: 8px; align-items: flex-end">{sp}</div>', 'px')
     + sec('화면 배치', f'<div style="display: flex; flex-direction: column; width: 720px">{lay}</div>')
     + sec('모서리', row([rad(*x) for x in RAD], 20))
     + sec('그림자', sh))

# 4. 공통 요소 --------------------------------------------------------------
BTN = 'border: 0; border-radius: 999px; font-size: 15px; font-weight: 700'
buttons = row([
    cell('기본 버튼 · 52 · 먹색', f'<button style="height: 52px; width: 300px; {BTN}; background: #111111; color: #FFFFFF">비교 끝내기</button>'),
    cell('비활성 · #E4E4DE / #A0A099', f'<button aria-disabled="true" style="height: 52px; width: 300px; {BTN}; background: #E4E4DE; color: #A0A099">다음</button>'),
    cell('위험 · #B42318', f'<button style="height: 52px; width: 300px; {BTN}; background: #B42318; color: #FFFFFF">기록 지우기</button>'),
    cell('글자 버튼', f'<button style="height: 44px; padding: 0 12px; border: 0; background: transparent; color: {DIM}; font-size: 15px; font-weight: 700">건너뛰기</button>'),
    cell('떠 있는 버튼 · 글자 폭', f'<button style="{BTN.replace("15px", "13px")}; background: #111111; color: #FFFFFF; display: flex; align-items: center; gap: 8px; padding: 12px 16px; min-height: 44px; box-shadow: 0 8px 24px -8px rgba(17,17,17,.4)">{svg(I_BOX, 18)}비교 끝내기</button>'),
    cell('작은 버튼 · 44', f'<a href="#" style="height: 44px; padding: 0 16px; border-radius: 999px; background: #111111; color: #FFFFFF; font-size: 13px; font-weight: 700; text-decoration: none; display: flex; align-items: center; gap: 4px">모두 확인하기{svg(I_ARROW)}</a>'),
    cell('하단 알림 · 52', f'<div role="status" style="width: 350px; height: 52px; border-radius: 999px; background: #111111; color: #FFFFFF; display: flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 700">아카이브에 보관했어요</div>'),
], 28)
toggle = ('<div role="tablist" aria-label="목적 보기" style="display: flex; padding: 3px; border-radius: 999px; background: #EAEAE4">'
          '<button role="tab" aria-selected="true" style="height: 38px; padding: 0 12px; border-radius: 999px; border: 0; background: #111111; color: #FFFFFF; font-size: 12px; font-weight: 700">진행 중</button>'
          '<button role="tab" aria-selected="false" style="height: 38px; padding: 0 12px; border-radius: 999px; border: 0; background: transparent; color: #111111; font-size: 12px; font-weight: 700">아카이브 2</button></div>')
PILL = 'height: 34px; padding: 0 12px; border-radius: 999px; border: 0; font-size: 13px; font-weight: 700; display: flex; align-items: center; gap: 6px'
pills = (f'<div style="display: flex; gap: 6px"><button aria-pressed="false" style="{PILL}; background: #EAEAE4; color: #111111">상의</button>'
         f'<button aria-pressed="true" style="{PILL}; background: #111111; color: #FFFFFF">신발</button>'
         f'<button style="{PILL}; background: transparent; color: #111111; padding: 0 4px">카테고리{svg(I_RIGHT, 14)}</button></div>')
circle = (f'<div style="display: flex; gap: 14px; align-items: center"><button aria-label="닫기" style="width: 36px; height: 36px; border-radius: 999px; border: 0; background: #F2F2EE; color: #111111; display: flex; align-items: center; justify-content: center">{svg(I_X, 16, 2)}</button>'
          f'<span style="width: 30px; height: 30px; border-radius: 999px; border: 1px solid rgba(17,17,17,.2); display: flex; align-items: center; justify-content: center">{svg(I_DOWN)}</span>'
          f'<span style="width: 30px; height: 30px; border-radius: 9px; background: #FFFFFF; display: flex; align-items: center; justify-content: center; box-shadow: inset 0 0 0 1px rgba(17,17,17,.08)">{svg(I_SPARK, 16, 1.6)}</span></div>')
controls = row([cell('전환 버튼 · 칩 면 위 38', toggle), cell('필터 pill · 34 · 안 고름 / 고름 / 더 보기', pills),
                cell('원형 닫기 36 · 펼침 30 · 아이콘 칸 30', circle)], 48)
def thumbs(bg, ring, n=3):
    return ''.join(f'<span style="display: inline-flex; margin-left: {0 if j == 0 else -8}px; border-radius: 999px; box-shadow: 0 0 0 2px {ring}"><span style="width: 30px; height: 30px; border-radius: 999px; overflow: hidden; background: #F2F2EE; display: inline-flex">'
                   f'<img src="/_blob/{IMGS[j % len(IMGS)]}" alt="" style="width: 100%; height: 100%; object-fit: cover; display: block; mix-blend-mode: multiply"></span></span>' for j in range(n))
hb = S['연두']['h']
home_block = (f'<div style="width: 378px; background: {hb}; border-radius: 20px; padding: 12px 14px 14px; box-sizing: border-box; display: flex; flex-direction: column; gap: 12px">'
              f'<div style="display: flex; justify-content: space-between; align-items: center"><span style="width: 30px; height: 30px; border-radius: 9px; background: #FFFFFF; display: flex; align-items: center; justify-content: center">{svg(I_SPARK, 16, 1.6)}</span>'
              f'<span style="width: 30px; height: 30px; border-radius: 999px; border: 1px solid rgba(17,17,17,.2); display: flex; align-items: center; justify-content: center">{svg(I_DOWN)}</span></div>'
              f'<div style="display: flex; justify-content: space-between; align-items: flex-end"><div style="display: flex; flex-direction: column; gap: 4px"><span style="font-size: 15px; font-weight: 700">분류·목적 확인</span>'
              f'<span style="font-size: 12px; color: {tone(hb, 4.5)}">AI가 정리해 둔 걸 확인해 주세요</span></div><span style="{GS}; font-size: 40px; line-height: 34px; font-weight: 500; letter-spacing: -0.04em">3</span></div></div>')
hs = S['하늘']['h']
line_block = (f'<div style="width: 378px; min-height: 54px; box-sizing: border-box; background: {hs}; border-radius: 20px; padding: 12px 14px; display: flex; align-items: center; gap: 10px">'
              f'<span style="width: 30px; height: 30px; border-radius: 9px; background: #FFFFFF"></span><span style="font-size: 14px; font-weight: 700">정보 가져오는 중</span>'
              f'<span style="{GS}; font-size: 20px; font-weight: 500; margin-left: auto">1</span><span style="width: 30px; height: 30px; border-radius: 999px; border: 1px solid rgba(17,17,17,.2); display: flex; align-items: center; justify-content: center">{svg(I_DOWN)}</span></div>')
pb = S['보라']['h']
purpose_block = (f'<div style="width: 378px; box-sizing: border-box; background: {pb}; border-radius: 20px; padding: 16px; min-height: 76px; display: flex; justify-content: space-between; align-items: center">'
                 f'<span style="display: flex; flex-direction: column; gap: 3px">{mono("출근할 때 입을 옷", tone(pb, 4.5))}<span style="font-size: 17px; font-weight: 700; letter-spacing: -0.02em">가을 옷장</span></span>'
                 f'<span style="display: flex">{thumbs("#F2F2EE", pb)}</span></div>')
archive_block = (f'<div style="width: 378px; box-sizing: border-box; background: #FFFFFF; border-radius: 20px; padding: 16px; min-height: 76px; display: flex; justify-content: space-between; align-items: center">'
                 f'<span style="display: flex; flex-direction: column; gap: 3px">{mono("9월 12일 종료")}<span style="display: flex; align-items: center; gap: 8px"><span style="width: 8px; height: 8px; border-radius: 999px; background: {pb}; box-shadow: 0 0 0 1px rgba(17,17,17,.2)"></span>'
                 f'<span style="font-size: 17px; font-weight: 700; letter-spacing: -0.02em">포켓몬 카드</span></span></span><span style="font-size: 12px; font-weight: 700; color: {DIM}">구매 안 함</span></div>')
blocks = row([cell('홈 챙길 일 블록 · 20 모서리', home_block), cell('한 줄 블록', line_block), cell('목적 블록 · 설명은 4.5:1 톤', purpose_block), cell('아카이브 블록 · 흰 면 + 목적 점', archive_block)], 28)
card = (f'<div style="width: 187px; display: flex; flex-direction: column; gap: 6px"><span style="width: 187px; height: 187px; border-radius: 16px; overflow: hidden; background: #FFFFFF; display: inline-flex">'
        f'<img src="/_blob/{IMGS[0]}" alt="" style="width: 100%; height: 100%; object-fit: cover; display: block"></span>'
        f'<span style="display: flex; flex-direction: column; gap: 2px; padding: 0 4px"><span style="display: flex; align-items: baseline; gap: 6px"><span style="font-size: 13px; line-height: 17px; font-weight: 700; flex: 1 1 auto; min-width: 0">T.T 팬츠 차콜</span>'
        f'<span style="{GS}; font-size: 13px; font-weight: 500; white-space: nowrap">₩190,000</span></span>'
        f'<span style="font-size: 11px; font-weight: 700; display: flex; align-items: center; gap: 4px">{svg(I_CHECK, 12, 2.4)}구매한 상품</span></span></div>')
add = (f'<button style="width: 187px; height: 187px; border-radius: 16px; border: 1px dashed rgba(17,17,17,.25); background: transparent; color: #111111; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; font-size: 13px; font-weight: 700">{svg(I_PLUS, 22)}후보 추가</button>')
chip = (f'<span style="display: inline-flex; align-items: center; gap: 8px; height: 44px; padding: 0 10px 0 6px; border-radius: 999px; background: #FFFFFF; box-shadow: 0 0 0 1px rgba(17,17,17,.08)">'
        f'<span style="width: 32px; height: 32px; border-radius: 999px; overflow: hidden; background: #F2F2EE; display: inline-flex"><img src="/_blob/{IMGS[1 % len(IMGS)]}" alt="" style="width: 100%; height: 100%; object-fit: cover; mix-blend-mode: multiply"></span>'
        f'<span style="font-size: 13px; font-weight: 700; max-width: 76px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">온 클라우드몬스터</span>{svg(I_X, 14, 2)}</span>')
label_row = (f'<div style="width: 390px; box-sizing: border-box; padding: 0 20px; display: flex; justify-content: space-between; align-items: baseline"><span style="display: flex; align-items: center; gap: 8px">'
             f'<span style="width: 8px; height: 8px; border-radius: 999px; background: {pb}; box-shadow: 0 0 0 1px rgba(17,17,17,.2)"></span>{mono("최근 저장순")}</span><span style="{GS}; font-size: 13px; color: {DIM}">5</span></div>')
headline = (f'<div style="width: 390px; box-sizing: border-box; padding: 0 20px"><h3 style="margin: 0; font-size: 22px; line-height: 30px; font-weight: 700; letter-spacing: -0.035em"><span style="display: block">가을 옷장</span>'
            f'<span style="display: block; color: rgba(17,17,17,.5)">후보 5개를 비교하고 있어요.</span></h3><p style="margin: 4px 0 0; font-size: 13px; line-height: 19px; color: {DIM}">출근할 때 입을 옷</p></div>')
lists = row([cell('상품 카드 · 187 · 사진 16 모서리', card), cell('후보 추가 카드', add), cell('선택 칩', chip),
             cell('헤드라인 묶음 · 두 줄 + 설명', headline), cell('라벨 줄 · 양옆 20', label_row)], 28)
def menu_item(t, d, danger=False):
    col = '#B42318' if danger else '#111111'
    return (f'<button style="min-height: 60px; border: 0; background: transparent; color: {col}; font-size: 16px; font-weight: 700; display: flex; align-items: center; gap: 14px; padding: 0; text-align: left; width: 100%">'
            f'{svg(I_TRASH if danger else I_EDIT, 22)}<span style="display: flex; flex-direction: column; gap: 4px; flex-grow: 1">{t}<span style="font-size: 13px; font-weight: 500; color: {DIM}">{d}</span></span>'
            f'<span style="color: #A0A099; display: flex">{svg(I_RIGHT, 18)}</span></button>')
sheet = (f'<div style="width: 366px; background: #FFFFFF; border-radius: 28px; box-shadow: 0 12px 40px -12px rgba(17,17,17,.25); padding: 18px 18px 28px; box-sizing: border-box">'
         f'<div style="display: flex; align-items: center; justify-content: space-between; padding-bottom: 14px"><span style="font-size: 16px; font-weight: 700">가을 옷장</span>'
         f'<button aria-label="닫기" style="width: 36px; height: 36px; border-radius: 999px; border: 0; background: #F2F2EE; color: #111111; display: flex; align-items: center; justify-content: center">{svg(I_X, 16, 2)}</button></div>'
         f'<div style="display: flex; flex-direction: column; gap: 16px; padding: 8px 4px 0">{menu_item("이름과 설명 바꾸기", "목적 이름과 설명을 고쳐요")}{menu_item("목적 지우기", "후보는 목적 미지정으로 바뀌어요", True)}</div></div>')
LP = 'height: 52px; border: 0; background: transparent; display: flex; align-items: center; gap: 14px; padding: 0 18px; width: 100%; text-align: left; font-size: 15px; font-weight: 700'
lp = (f'<div style="width: 232px; background: #FFFFFF; border-radius: 20px; box-shadow: 0 16px 48px -12px rgba(17,17,17,.3); overflow: hidden; display: flex; flex-direction: column">'
      f'<div style="padding: 14px 18px 12px; display: flex; flex-direction: column; gap: 3px; border-bottom: 1px solid rgba(17,17,17,.06)"><span style="font-size: 13px; font-weight: 700">T.T 팬츠 차콜</span><span style="{GS}; font-size: 13px; color: {DIM}">₩190,000</span></div>'
      f'<button style="{LP}; color: #111111">{svg(I_EDIT, 20)}상품 정보 수정</button><button style="{LP}; color: #B42318">{svg(I_TRASH, 20)}삭제</button></div>')
sheets = row([cell('떠 있는 시트 · 28 모서리 · 양옆 12, 아래 16', sheet), cell('길게 누르기 메뉴 · 항목 52 · 구분선 없음', lp), cell('하단 탭 · 54 · 먹색', NAV)], 40)
page('DS-COMP', '디자인 시스템 · 공통 요소', 1620,
     head('공통 요소', '확정 보드에서 반복되는 요소를 모았어요. 화면에서는 이 모양을 그대로 불러 써요.')
     + sec('버튼', buttons) + sec('선택 · 원형 버튼', controls) + sec('블록', blocks) + sec('목록 · 헤드라인', lists) + sec('시트 · 메뉴 · 탭', sheets))

# 5. 어긋난 값 -------------------------------------------------------------
def opt(tag, label, inner, note=''):
    return (f'<div style="width: 250px; box-sizing: border-box; padding: 16px; border-radius: 20px; background: #FFFFFF; display: flex; flex-direction: column; gap: 12px">'
            f'<span style="{GS}; font-size: 15px; font-weight: 500">{tag}</span><span style="font-size: 14px; font-weight: 700">{label}</span>{inner}'
            + (f'<span style="font-size: 12px; line-height: 16px; color: {DIM}">{note}</span>' if note else '') + '</div>')
def issue(n, title, where, opts):
    return (f'<div style="display: grid; grid-template-columns: 300px 1fr; gap: 24px; padding: 20px 0; border-top: 1px solid rgba(17,17,17,.1)">'
            f'<span style="display: flex; flex-direction: column; gap: 6px"><span style="{GS}; font-size: 20px; font-weight: 500">{n}</span><span style="font-size: 17px; font-weight: 700">{title}</span>'
            f'<span style="font-size: 12px; line-height: 17px; color: {DIM}">{where}</span></span>' + row(opts, 14) + '</div>')
def red(c): return f'<span style="display: flex; align-items: center; gap: 12px; font-size: 15px; font-weight: 700; color: {c}">{svg(I_TRASH, 20)}삭제</span>'
def steps(num, title, desc):
    return (f'<div style="display: flex; gap: 14px; align-items: flex-start"><span style="{GS}; font-size: 30px; line-height: 28px; font-weight: 500; letter-spacing: -0.04em; color: {num}; width: 44px">01</span>'
            f'<span style="display: flex; flex-direction: column; gap: 3px"><span style="font-size: 14px; font-weight: 700; color: {title}">공유 누르기</span><span style="font-size: 12px; color: {desc}">어디서든 괜찮아요</span></span></div>')
def card_bg(bg):
    return (f'<div style="border-radius: 20px; background: {bg}; padding: 12px 14px; box-shadow: inset 0 0 0 1px rgba(17,17,17,.06)"><span style="font-size: 12px; color: {DIM}">고르는 중인 목적</span>'
            f'<div style="font-size: 17px; font-weight: 700; letter-spacing: -0.02em; padding-top: 8px">가을 트레일 러닝</div></div>')
def btnh(h): return f'<button style="height: {h}px; width: 100%; {BTN}; background: #111111; color: #FFFFFF">2개 후보로 넣기</button>'
REVIEW = [
    issue('1', '위험 빨강', '길게 누르기 메뉴의 ‘삭제’만 #C23B2A, 시트·확인 버튼·기록 지우기는 #B42318',
          [opt('A', '#B42318로 맞추기', red('#B42318'), '모든 위험 동작을 같은 빨강으로'), opt('B', '지금대로 두기', red('#C23B2A'), '길게 누르기 메뉴만 다른 빨강')]),
    issue('2', '빈 홈 저장 방법 글자', '빈 홈 3종의 단계 숫자 #D9D9D2, 제목 #6E6E68이 무채색 단계 밖의 회색',
          [opt('A', '지금대로 두기', steps('#D9D9D2', '#6E6E68', '#A0A099'), '단계 밖 회색 두 개가 남아요'),
           opt('B', '무채색 단계로', steps('#E4E4DE', 'rgba(17,17,17,.62)', '#A0A099'), '숫자는 비활성 면, 제목은 먹색 62%'),
           opt('C', '먹색 투명도로', steps('rgba(17,17,17,.2)', 'rgba(17,17,17,.62)', 'rgba(17,17,17,.5)'), '숫자 20%, 제목 62%, 설명 50%')]),
    issue('3', '빈 홈 목적 카드 면', '‘챙길 일을 다 끝냈어요’ 홈의 고르는 중인 목적 카드가 비활성 면 #E4E4DE',
          [opt('A', '지금대로 · 비활성 면', card_bg('#E4E4DE')), opt('B', '칩 #EAEAE4', card_bg('#EAEAE4')), opt('C', '흰 카드 #FFFFFF', card_bg('#FFFFFF'), '아카이브 블록과 같은 흰 면')]),
    issue('4', '기본 버튼 높이', '기본 버튼은 52px(7곳), 후보 추가 창의 넣기 버튼만 54px',
          [opt('A', '52로 맞추기', btnh(52)), opt('B', '지금대로 두기', btnh(54), '선택 칩 줄 아래 버튼만 54')]),
]
page('DS-REVIEW', '디자인 시스템 · 정리할 값', 1000,
     head('정리할 값', '확정 보드를 모으다 규칙에서 벗어난 값을 찾았어요. 고르면 확정 보드와 디자인 시스템에 반영할게요.')
     + '<div style="display: flex; flex-direction: column">' + ''.join(REVIEW) + '</div>')
print('ok', os.listdir(OUT))
