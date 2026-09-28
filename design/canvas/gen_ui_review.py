# UI 피드백 적용 페이지 생성기. 확정 디자인 보드를 복제해 UI 피드백을 모두 반영한다.
# 사용: python3 gen_ui_review.py <src dir> <out dir>
import os, re, sys, json
from collections import Counter

SRC, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
P = 'UI-'
APP = ['Flow-Confirmed', 'PurposeDetail', 'P-D', 'CT-D', 'PT-A8', 'A8-AR2', 'AD-F2', 'ARCH-ACT2']
TITLES = {'Flow-Confirmed': 'UI 적용 · 기능 흐름 뷰', 'PurposeDetail': 'UI 적용 · 목적 상세 부품', 'P-D': 'UI 적용 · 홈',
          'CT-D': 'UI 적용 · 카테고리', 'PT-A8': 'UI 적용 · 목적 탭', 'A8-AR2': 'UI 적용 · 아카이브 목록',
          'AD-F2': 'UI 적용 · 기록 상세', 'ARCH-ACT2': 'UI 적용 · 기록 동작', 'Flow-Map': 'UI 적용 · 흐름도'}
LOG = {}

def sub(s, name, pat, rep, flags=0, count=0, need=True):
    n = len(re.findall(pat, s, flags)) if count == 0 else min(count, len(re.findall(pat, s, flags)))
    s2 = re.sub(pat, rep, s, count=count, flags=flags)
    LOG.setdefault(CUR, Counter())[name] += n
    return s2

def lit(s, name, a, b):
    LOG.setdefault(CUR, Counter())[name] += s.count(a)
    return s.replace(a, b)

# 1. 라벨 글꼴: 고정폭은 숫자·영문에만, 한글·공백은 Pretendard로
MONO_URL = 'https://fonts.gstatic.com/s/jetbrainsmono/v24/tDbv2o-flEEny0FZhsfKu5WU4zr3E_BX0PnT8RD8yKwBNntkaToggR7BYRbKPxDcwgknk-4.woff2'
HELMET_CSS = (
    '@font-face{font-family:"Label Mono";font-weight:400 500;font-display:swap;src:url(%s) format("woff2");unicode-range:U+0021-007E}\n'
    'body{word-break:keep-all;overflow-wrap:break-word}\n'
    'button[aria-label="닫기"],button[aria-label="뒤로"],nav[aria-label="주요 메뉴"] button{position:relative}\n'
    'button[aria-label="닫기"]::after,button[aria-label="뒤로"]::after{content:"";position:absolute;inset:-4px}\n'
    'nav[aria-label="주요 메뉴"] button::after{content:"";position:absolute;inset:-2px 0}\n') % MONO_URL
LABEL = "font-family: 'Label Mono', Pretendard, sans-serif; font-size: 11px; font-weight: 500; letter-spacing: .02em"


# 스크롤 목록 위아래 흐림: 위는 고정 줄 아래로 들어가는 내용, 아래는 떠 있는 버튼·탭 뒤
def mask(full, clear):
    g = 'linear-gradient(to bottom, transparent 0, #000 14px, #000 calc(100%% - %dpx), transparent calc(100%% - %dpx))' % (full, clear)
    return '-webkit-mask-image: %s; mask-image: %s' % (g, g)
MASK_LIST = mask(116, 72)   # 하단 탭만 떠 있는 목록
MASK_FAB = mask(176, 96)    # 비교 끝내기 버튼과 하단 탭이 떠 있는 목록
MASK_PICK = mask(196, 112)  # 선택 칩과 넣기 버튼이 떠 있는 후보 추가 창
SCROLL = '<div class="scroll" style="flex: 1 1 auto; min-height: 0; overflow-y: auto; overscroll-behavior: contain">'

def block_span(s, start):
    i = s.index(start); depth = 0
    for m in re.finditer(r'<sc-if\b|</sc-if>', s[i:]):
        depth += 1 if m.group(0) == '<sc-if' else -1
        if depth == 0: return i, i + m.end()

GS = "<span style=\"font-family: 'Space Grotesk', Pretendard, sans-serif\">"

def common(s, name):
    s = s.replace('</style>\n</helmet>', HELMET_CSS + '</style>\n</helmet>', 1)
    s = re.sub(r'<title>[^<]*</title>', '<title>%s</title>' % TITLES[name], s, count=1)
    # 1. 라벨
    s = sub(s, 'label-font', r"font-family: 'JetBrains Mono', Pretendard, monospace; font-size: 10px; letter-spacing: \.1[024]?em", LABEL)
    # 6. 개수 앞 0 빼기
    s = sub(s, 'count-pad', r"(font-family: 'Space Grotesk', Pretendard, sans-serif; font-size: (?:13|20)px; color: rgba\(17,17,17,\.62\)\">)0(\d)<", r'\1\2<')
    # 여백: 라벨 줄 양옆 16 -> 20
    s = sub(s, 'label-row-20', r'padding: (20|16)px 16px 0(; display: flex; justify-content: space-between)', r'padding: \1px 20px 0\2')
    s = sub(s, 'label-row-20', r'<div style="padding: 20px 16px 0">', '<div style="padding: 20px 20px 0">')
    # 3. 흰 면 위 작은 사진: 옅은 바탕 + 곱하기 합성
    s = sub(s, 'thumb-tile', r'(<span style="width: (?:34|40|48|56)px; height: (?:34|40|48|56)px; border-radius: [^;]+; overflow: hidden; )background: #FFFFFF(;[^"]*"><img [^>]*?style=")', r'\1background: #F2F2EE\2mix-blend-mode: multiply; ')
    # 4. 상품 이름 최대 두 줄
    s = sub(s, 'name-2line', r'(font-size: 13px; font-weight: 700; color: [^;]+;(?: flex: 1 1 auto; min-width: 0;)?) overflow: hidden; text-overflow: ellipsis; white-space: nowrap',
            r'\1 overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; line-height: 17px; word-break: break-all')
    # 목록 카드 상태 표시를 이름 줄 아래로
    ROW = r'<span style="display: flex; align-items: baseline; gap: 6px; min-width: 0">(?:<span [^>]*>[^<]*</span>){2}</span>'
    s = sub(s, 'status-below', r'(<span style="font-size: 11px; font-weight: 700; display: flex; align-items: center; gap: 4px">(?:<svg.*?</svg>)?구매한 상품</span>)(' + ROW + ')', r'\2\1')
    s = sub(s, 'status-below', r'(<span style="align-self: flex-start; height: 28px; [^"]*?)margin-bottom: 4px(">[^<]*</span>)(' + ROW + ')', r'\3\1margin-top: 4px\2')
    # 6. 구매 표시 하나로: 이름 뒤 `· 구매` -> 아래 줄 `✓ 구매한 상품`
    chk = re.search(r'<span style="font-size: 11px; font-weight: 700; display: flex; align-items: center; gap: 4px">(<svg.*?</svg>)구매한 상품</span>', s)
    CHK = chk.group(1) if chk else '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12l5 5 9-10"></path></svg>'
    s = sub(s, 'purchase-mark', r'(<span style="font-size: 16px; font-weight: 700">[^<]*?) <span style="font-size: 13px; font-weight: 700; color: #111111">· 구매</span></span>(<span style="font-size: 14px; color: rgba\(17,17,17,\.62\)">[^<]*</span>)',
            r'\1</span>\2<span style="font-size: 11px; font-weight: 700; display: flex; align-items: center; gap: 4px">' + CHK + '구매한 상품</span>')
    # 6. 시트 가격도 Space Grotesk
    s = sub(s, 'price-font', r'(<span style="font-size: 1[34]px; color: rgba\(17,17,17,\.62\)">)([^<]*?)([₩¥][\d,]+)', lambda m: m.group(1) + m.group(2) + GS + m.group(3) + '</span>')
    s = sub(s, 'price-font', r'(<span style="font-size: 14px; color: rgba\(17,17,17,\.62\)">)\{\{ (r\.p|pickPrice) \}\}', lambda m: m.group(1) + GS + '{{ %s }}</span>' % m.group(2))
    # 6. 메뉴: 시트 메뉴 항목 간격 36 -> 16, 길게 누르기 메뉴 항목 구분선 빼기
    s = sub(s, 'menu-gap', r'gap: 36px; padding: 8px 4px 0', 'gap: 16px; padding: 8px 4px 0')
    s = sub(s, 'lp-divider', r'height: 52px; border: 0; border-top: 1px solid rgba\(17,17,17,\.06\);', 'height: 52px; border: 0;')
    # 홈: ↗ -> →, 가로 목록 카드를 키워 옆 카드가 보이게
    s = lit(s, 'arrow', '<path d="M7 17L17 7"></path><path d="M9 7h8v8"></path>', '<path d="M5 12h14"></path><path d="M13 6l6 6-6 6"></path>')
    s = lit(s, 'home-card', 'width: 112px; flex-shrink: 0; display: flex; flex-direction: column; gap: 8px', 'width: 128px; flex-shrink: 0; display: flex; flex-direction: column; gap: 8px')
    s = lit(s, 'home-card', 'width: 112px; height: 120px; border-radius: 16px', 'width: 128px; height: 120px; border-radius: 16px')
    # 문구: 헤드라인 형식과 반복 표현
    for k, (a, b) in {
        'copy': ('이름이랑 카테고리만 알려주세요', '이름이랑 카테고리만 알려 주세요'),
    }.items():
        s = lit(s, k, a, b)
    s = lit(s, 'copy', '카테고리 6개로 나눠 뒀어요.', '카테고리 6개로 나눴어요.')
    s = lit(s, 'copy', '결정한 기록을 모아 뒀어요.', '최근에 끝낸 순서로 보여 드려요.')
    s = lit(s, 'copy', '최근 저장한 순서로 보여드려요.', '최근 저장한 순서로 보여 드려요.')
    s = lit(s, 'copy', '고르지 않으면 모두 보여드려요.', '고르지 않으면 모두 보여 드려요.')
    def josa(m):
        w, n = m.group(1), m.group(2)
        if w == '카테고리 미지정': return '미지정 상품이 %s개 있어요.' % n
        last = w[-1]; jong = (ord(last) - 0xAC00) % 28 if '가' <= last <= '힣' else 0
        return '%s%s %s개 있어요.' % (w, '이' if jong else '가', n)
    s = sub(s, 'copy-cat', r'([가-힣 ]+?) (\d+)개를 모아 뒀어요\.', josa)
    # 스크롤 목록 흐림, 목록 끝 여백
    s = lit(s, 'scroll-mask', SCROLL, SCROLL.replace('overscroll-behavior: contain"', 'overscroll-behavior: contain; ' + MASK_LIST + '"'))
    s = lit(s, 'list-pad', '<div style="padding: 14px 6px 120px">', '<div style="padding: 14px 6px 150px">')
    return s

def flow_confirmed(s):
    s = s.replace('name="PurposeDetail"', 'name="%sPurposeDetail"' % P)
    return category_index(s)

def category_index(s):
    # 카테고리 목차를 스크롤되게 감싸 마지막 줄이 탭에 가리지 않게
    start = '<sc-if value="{{ c_isIndex }}" hint-placeholder-val="{{ true }}">'
    if start not in s: return s
    a, b = block_span(s, start)
    inner = s[a + len(start):b - len('</sc-if>')]
    inner = inner.replace('<div style="padding: 24px 20px 120px;', '<div style="padding: 24px 20px 150px;', 1)
    wrap = '<div class="scroll" style="height: 844px; overflow-y: auto; overscroll-behavior: contain; ' + MASK_LIST + '">'
    LOG.setdefault(CUR, Counter())['cat-index-scroll'] += 1
    return s[:a] + start + wrap + inner + '</div></sc-if>' + s[b:]

def purpose_detail(s):
    # 헤드라인: 목적 이름 / 후보 N개를 비교하고 있어요. + 설명
    s = lit(s, 'pd-headline', '{{ pname }} 후보 {{ count }}개</span>', '{{ pname }}</span>')
    h1_end = '{{ line2 }}</span></h1>'
    s = lit(s, 'pd-desc', h1_end, h1_end + '<sc-if value="{{ hasDesc }}" hint-placeholder-val="{{ true }}"><p style="margin: -10px 0 0; font-size: 13px; line-height: 19px; color: rgba(17,17,17,.62)">{{ desc }}</p></sc-if>')
    s = lit(s, 'pd-js', "v.pname = s.pname; v.line2 = s.desc || '후보를 골라 넣어 볼까요?';",
            "v.pname = s.pname; v.line2 = s.members.length ? '후보 ' + s.members.length + '개를 비교하고 있어요.' : '후보를 골라 넣어 볼까요?'; v.desc = s.desc; v.hasDesc = !!s.desc; v.poolN = s.pool.length;")
    # 후보 추가 창 헤드라인
    s = lit(s, 'picker-headline', '{{ pname }}에 넣을 상품을</span>', '고를 수 있는 상품이 {{ poolN }}개 있어요.</span>')
    s = lit(s, 'picker-headline', 'color: rgba(17,17,17,.5)">골라 주세요</span>', 'color: rgba(17,17,17,.5)">{{ pname }}에 넣을 후보를 골라 주세요.</span>')
    # 첫 단계 시트에는 뒤로 버튼을 두지 않음
    s = sub(s, 'first-step-back', r'<button onClick="\{\{ pickBack \}\}" aria-label="뒤로"[^>]*>.*?</button>', '<span style="width: 36px; flex-shrink: 0"></span>', flags=re.S)
    # 선택 칩: 이름 폭 넓히기, 사진 바탕
    s = lit(s, 'chip-name', 'max-width: 76px;', 'max-width: 120px;')
    s = sub(s, 'chip-tile', r'<img src="\{\{ p\.img \}\}" alt="" style="width: 32px; height: 32px; border-radius: 999px; object-fit: cover; background: #FFFFFF">',
            '<span style="width: 32px; height: 32px; border-radius: 999px; overflow: hidden; background: #F2F2EE; display: inline-flex; flex-shrink: 0"><img src="{{ p.img }}" alt="" style="width: 100%; height: 100%; object-fit: cover; mix-blend-mode: multiply"></span>')
    # 후보 추가 창 목록은 칩과 버튼 뒤로 더 일찍 흐려지게 (두 번째 스크롤 목록)
    masked = SCROLL.replace('overscroll-behavior: contain"', 'overscroll-behavior: contain; ' + MASK_LIST + '"')
    i = s.index(masked); j = s.index(masked, i + 1)
    s = s[:i] + masked.replace(MASK_LIST, MASK_FAB) + s[i + len(masked):j] + masked.replace(MASK_LIST, MASK_PICK) + s[j + len(masked):]
    LOG[CUR]['picker-mask'] += 1
    return s

for name in APP:
    CUR = name
    s = open(os.path.join(SRC, name + '.dc.html')).read()
    s = common(s, name)
    if name == 'Flow-Confirmed': s = flow_confirmed(s)
    if name == 'CT-D': s = category_index(s)
    if name == 'PurposeDetail': s = purpose_detail(s)
    open(os.path.join(OUT, P + name + '.dc.html'), 'w').write(s)

# 흐름도: 복제 보드를 불러오도록
CUR = 'Flow-Map'
m = open(os.path.join(SRC, 'Flow-Map.dc.html')).read()
for name in APP:
    m = lit(m, 'map-import', 'dc-import name="%s"' % name, 'dc-import name="%s%s"' % (P, name))
m = re.sub(r'<title>[^<]*</title>', '<title>%s</title>' % TITLES['Flow-Map'], m, count=1)
m = lit(m, 'map-head', '>확정 디자인 흐름도</h1>', '>UI 피드백 적용 흐름도</h1>')
m = lit(m, 'map-head', '확정한 화면을 실제 보드 그대로 절반 크기로 불러와 이었어요. 보드를 고치면 여기도 함께 바뀌어요.',
        'UI 피드백을 적용한 복제 보드를 절반 크기로 불러와 이었어요. 확정 디자인 페이지와 나란히 비교해 보세요.')
open(os.path.join(OUT, P + 'Flow-Map.dc.html'), 'w').write(m)

for k, c in LOG.items():
    print(k, dict(c))
