"""전체 화면 · 홈 계열 보드와 검토 보드를 만든다.

python3 gen_full_home.py <out_root>
out_root/project/ 아래에 보드 파일을 쓴다.
"""
import os
import sys


THEMES = {
    "L": dict(
        s="L", name="라이트", bg="#F8F8F8", card="#FFFFFF", sheet="#FFFFFF", tile="#F0F0F0", icon_tile="#F0F0F0",
        field="#FFFFFF", line="#E2E2E2", text="#1D1D1D", sub="#5E5E5E", tab="#1D1D1D", tab_text="#FFFFFF",
        inv="#1D1D1D", inv_text="#FFFFFF", handle="#DCDCDC", dash="#BDBDBD", back1="#EEEEEE", back2="#E4E4E4",
        scrim="rgba(0,0,0,0.24)", ring=True,
    ),
    "D": dict(
        s="D", name="다크", bg="#1D1D1D", card="#312F30", sheet="#2A2A2A", tile="#3A3939", icon_tile="#1D1D1D",
        field="#312F30", line="#3A3939", text="#F4F3F0", sub="#A9A7A2", tab="#312F30", tab_text="#F4F3F0",
        inv="#F4F3F0", inv_text="#1D1D1D", handle="#4A4948", dash="#5A5958", back1="#2A2A2A", back2="#262626",
        scrim="rgba(0,0,0,0.45)", ring=False,
    ),
}

PURPOSE = {
    "출퇴근 헤드폰": ("#F96857", "#F84D39"),
    "가을 트레일 러닝": ("#F9CD61", "#B38107"),
    "홈오피스 의자": ("#7477FF", "#7477FF"),
}

PHOTO = {
    "white": ("#FFFFFF", "#9A9A96", "#D8D8D4"),
    "beige": ("#E9E8E4", "#7C7B77", "#BDBCB7"),
    "brown": ("#7A6B5B", "#E9DFD2", "#A99683"),
    "green": ("#3F4B44", "#C9D3CB", "#7F8E84"),
}

# 보조 라벨 서체. 결정 전까지 지금 값(IBM Plex Mono)을 쓴다.
LABEL_FONTS = {
    "mono": ("'IBM Plex Mono', 'IBM Plex Sans KR', monospace", "letter-spacing: 0.02em; ", "family=IBM+Plex+Mono:wght@400;500"),
    "plex": ("'IBM Plex Sans KR', sans-serif", "", ""),
    "noto": ("'Noto Sans KR', sans-serif", "", "family=Noto+Sans+KR:wght@400;500;700"),
    "gothic": ("'Gothic A1', sans-serif", "", "family=Gothic+A1:wght@400;500;700"),
}
LABEL = "plex"

DANGER = {
    "A": dict(L="#C62828", D="#C62828"),  # 빨강 면 + 흰 글자
    "B": dict(L="#C62828", D="#FF7A70"),  # 무채색 버튼 + 빨강 글자
    "C": dict(L="#C62828", D="#FF7A70"),  # 검은 버튼 유지, 되돌릴 수 없음 줄만 빨강
}


def lbl(size, color, label=None, extra=""):
    fam, ls, _ = LABEL_FONTS[label or LABEL]
    return f"font-family: {fam}; font-size: {size}px; font-weight: 500; {ls}white-space: nowrap; color: {color};{extra}"


NUM = "font-weight: 700; font-variant-numeric: tabular-nums; line-height: 1;"


def fonts_link(label=None):
    fams = ["family=Do+Hyeon"]
    extra = LABEL_FONTS[label or LABEL][2]
    if extra:
        fams.append(extra)
    fams.append("family=IBM+Plex+Sans+KR:wght@400;500;700")
    return "https://fonts.googleapis.com/css2?" + "&amp;".join(fams) + "&amp;display=swap"


def head(title, T, label=None):
    return f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="stylesheet" href="{fonts_link(label)}">
<style>
body{{margin:0}}
button{{font:inherit;color:inherit}}
a{{color:{T['text']};text-decoration:none}}a:hover{{color:{T['sub']}}}
input::placeholder{{color:{T['sub']}}}
</style>
</helmet>
"""


def tail(script):
    return f"""</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":390,"height":844}}}}'>
{script}
</script>
</body>
</html>
"""


def photo_svg(kind, size="56%"):
    _, s, f = PHOTO[kind]
    return (f'<svg aria-hidden="true" width="{size}" height="{size}" viewBox="0 0 48 48" fill="none" stroke="{s}" stroke-width="3" stroke-linecap="round">'
            f'<path d="M10 30v-6a14 14 0 0 1 28 0v6"></path><rect x="7" y="28" width="8" height="12" rx="3" fill="{f}"></rect>'
            f'<rect x="33" y="28" width="8" height="12" rx="3" fill="{f}"></rect></svg>')


def dot(name, T, size=10):
    face, ring = PURPOSE[name]
    border = f" border: 1px solid {ring};" if T["ring"] else ""
    return f'<span aria-hidden="true" style="width: {size}px; height: {size}px; flex-shrink: 0; box-sizing: border-box; border-radius: {size // 2}px; background: {face};{border}"></span>'


ICON_X = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M6 6l12 12M18 6L6 18"></path></svg>'
ICON_CHECK = '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12l5 5L20 7"></path></svg>'
ICON_DOWN = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" style="{st}"><path d="M6 9l6 6 6-6"></path></svg>'
ICON_SPARK = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 4v16M4 12h16M6.3 6.3l11.4 11.4M17.7 6.3L6.3 17.7"></path></svg>'
ICON_PEN = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 20h4L19 9l-4-4L4 16z"></path><path d="M13 7l4 4"></path></svg>'
ICON_SUN = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1"></path></svg>'
ICON_RIGHT = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M9 5l7 7-7 7"></path></svg>'


def header(T, title, home):
    return f"""<header style="padding: 52px 16px 8px; display: flex; align-items: center; gap: 8px">
<a href="{home}" aria-label="닫기" style="width: 44px; height: 44px; border-radius: 22px; background: {T['card']}; display: flex; align-items: center; justify-content: center">{ICON_X}</a>
<div style="flex-grow: 1; text-align: center; font-size: 16px; font-weight: 700">{title}</div>
<sc-if value="{{{{notDone}}}}" hint-placeholder-val="{{{{true}}}}"><div style="width: 44px; text-align: right; {lbl(14, T['sub'])}">{{{{pos}}}}</div></sc-if>
<sc-if value="{{{{done}}}}" hint-placeholder-val="{{{{false}}}}"><div style="width: 44px"></div></sc-if>
</header>
"""


def done_block(T, heading, home):
    return f"""<sc-if value="{{{{done}}}}" hint-placeholder-val="{{{{false}}}}">
<div style="flex-grow: 1; padding: 0 24px 120px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px; text-align: center">
<span style="width: 64px; height: 64px; border-radius: 28px; background: {T['card']}; display: flex; align-items: center; justify-content: center">{ICON_CHECK}</span>
<h2 style="margin: 0; font-size: 24px; font-weight: 700">{heading}</h2>
<p style="margin: 0; font-size: 15px; color: {T['sub']}">{{{{summary}}}}</p>
<a href="{home}" style="margin-top: 12px; height: 52px; padding: 0 28px; border-radius: 26px; background: {T['inv']}; color: {T['inv_text']}; display: flex; align-items: center; font-size: 16px; font-weight: 700">홈으로</a>
<button type="button" onClick="{{{{restart}}}}" style="all: unset; cursor: pointer; min-height: 44px; font-size: 14px; color: {T['sub']}">처음부터 다시 보기</button>
</div>
</sc-if>
"""


def btn_primary(T, label, on, extra="", radius=28, h=56):
    return f'<button type="button" onClick="{{{{{on}}}}}" style="flex: 1 1 0; height: {h}px; border-radius: {radius}px; border: none; background: {T["inv"]}; color: {T["inv_text"]}; display: flex; align-items: center; justify-content: center; gap: 8px; font-size: 16px; font-weight: 700{extra}">{label}</button>'


def btn_secondary(T, label, on, bg=None, extra="", radius=28, h=56):
    return f'<button type="button" onClick="{{{{{on}}}}}" style="flex: 1 1 0; height: {h}px; border-radius: {radius}px; border: none; background: {bg or T["card"]}; color: {T["text"]}; display: flex; align-items: center; justify-content: center; gap: 8px; font-size: 16px; font-weight: 500{extra}">{label}</button>'


# ---------------------------------------------------------------- 분류·목적 확인 연속 처리

REVIEW_ITEMS = [
    dict(name="마샬 MAJOR V", price="₩229,000", cat="헤드폰", purpose="출퇴근 헤드폰", photo="white", hasDup=False,
         why="AI가 카테고리와 목적을 연결했어요"),
    dict(name="호카 스피드고트 6", price="₩209,000", cat="신발", purpose="가을 트레일 러닝", photo="beige", hasDup=True,
         why="AI가 카테고리와 목적을 연결했어요"),
    dict(name="발뮤다 더 토스터", price="₩349,000", cat="주방 가전", purpose=None, photo="brown", hasDup=False,
         why="AI가 카테고리를 정했어요. 맞는 목적은 찾지 못했어요"),
]


def review_items_js(T):
    out = []
    for it in REVIEW_ITEMS:
        bg, s, f = PHOTO[it["photo"]]
        p = it["purpose"]
        face, ring = PURPOSE[p] if p else ("", "")
        out.append("{" + ", ".join([
            f'"name": "{it["name"]}"', f'"price": "{it["price"]}"', f'"cat": "{it["cat"]}"',
            f'"purpose": "{p or "목적 미지정"}"', f'"hasDot": {"true" if p else "false"}',
            f'"dotStyle": "background: {face};{(" border: 1px solid " + ring + ";") if (p and T["ring"]) else ""}"',
            f'"purposeColor": "{T["text"] if p else T["sub"]}"',
            f'"ph": "{bg}", "phS": "{s}", "phF": "{f}"', f'"hasDup": {"true" if it["hasDup"] else "false"}', f'"why": "{it["why"]}"',
        ]) + "}")
    return "[" + ", ".join(out) + "]"


def confirm_dialog(T, key, title, thumb_kind, meta, bullets, ok_label, ok_on, destructive, danger, theme):
    red = DANGER[danger][theme] if danger else None
    lis = []
    for b in bullets:
        if danger == "C" and destructive and b == "되돌릴 수 없어요":
            lis.append(f'<li style="color: {red}; font-weight: 700">{b}</li>')
        else:
            lis.append(f"<li>{b}</li>")
    cancel = f'<button type="button" onClick="{{{{cancelConfirm}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: {T["tile"]}; color: {T["text"]}; font-size: 16px; font-weight: 500">취소</button>'
    if destructive and danger == "A":
        ok = f'<button type="button" onClick="{{{{{ok_on}}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: {red}; color: #FFFFFF; font-size: 16px; font-weight: 700">{ok_label}</button>'
    elif destructive and danger == "B":
        ok = f'<button type="button" onClick="{{{{{ok_on}}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: {T["tile"]}; color: {red}; font-size: 16px; font-weight: 700">{ok_label}</button>'
    else:
        ok = f'<button type="button" onClick="{{{{{ok_on}}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: {T["inv"]}; color: {T["inv_text"]}; font-size: 16px; font-weight: 700">{ok_label}</button>'
    bg = PHOTO[thumb_kind][0]
    return f"""<sc-if value="{{{{{key}}}}}" hint-placeholder-val="{{{{false}}}}"><div style="position: absolute; inset: 0; z-index: 20; background: {T['scrim']}; backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px)"></div>
<div role="dialog" aria-label="{title}" style="position: absolute; left: 24px; right: 24px; top: 50%; transform: translateY(-50%); z-index: 21; box-sizing: border-box; padding: 24px 20px 20px; border-radius: 36px; background: {T['sheet']}; display: flex; flex-direction: column; gap: 12px">
<h2 style="margin: 0; font-size: 20px; font-weight: 700; word-break: keep-all">{title}</h2>
{meta}
<ul style="margin: 0; padding-left: 20px; font-size: 15px; line-height: 1.6; word-break: keep-all; display: flex; flex-direction: column; gap: 2px">{''.join(lis)}</ul>
<div style="display: flex; gap: 10px; margin-top: 4px">{cancel}{ok}</div>
</div></sc-if>"""


def item_row(T, kind, name, meta):
    bg = PHOTO[kind][0]
    return (f'<div style="display: flex; align-items: center; gap: 12px; padding: 10px; border-radius: 20px; background: {T["tile"]}">'
            f'<span style="width: 44px; height: 44px; flex-shrink: 0; box-sizing: border-box; padding: 4px; border-radius: 10px; background: {bg}; display: flex; align-items: center; justify-content: center; overflow: hidden">{photo_svg(kind, "80%")}</span>'
            f'<span style="min-width: 0; display: flex; flex-direction: column; gap: 2px"><span style="font-size: 15px; font-weight: 700">{name}</span>'
            f'<span style="{lbl(12, T["sub"])}">{meta}</span></span></div>')


def compare_card(T, theme, new):
    kind = "beige" if new else "brown"
    tag = (f'<span style="align-self: flex-start; height: 26px; padding: 0 10px; border-radius: 13px; background: {T["inv"]}; color: {T["inv_text"]}; display: flex; align-items: center; font-size: 12px; font-weight: 700">새로 저장</span>'
           if new else
           f'<span style="align-self: flex-start; height: 26px; padding: 0 10px; border-radius: 13px; background: {T["tile"]}; display: flex; align-items: center; font-size: 12px; font-weight: 700">이미 있던 상품</span>')
    border = f"2px solid {T['text']}" if new else f"1.5px solid {T['line']}"
    price = "₩209,000" if new else "₩199,000"
    saved = "오늘" if new else "3주 전"
    seller = "musinsa.com" if new else "hoka.com"
    ai = f'<span style="display: block; margin-top: 2px; {lbl(11, T["sub"])}">AI 연결</span>' if new else ""
    dt = f"color: {T['sub']}; white-space: nowrap"
    return f"""<div style="flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 10px; padding: 10px; border-radius: 28px; border: {border}">
{tag}
<div style="aspect-ratio: 1 / 1; box-sizing: border-box; padding: 16px; border-radius: 20px; background: {PHOTO[kind][0]}; display: flex; align-items: center; justify-content: center; overflow: hidden">{photo_svg(kind, "80%")}</div>
<div style="display: flex; flex-direction: column; gap: 4px; padding: 0 2px">
<div style="font-size: 15px; font-weight: 700; line-height: 1.35; word-break: keep-all">호카 스피드고트 6</div>
<div style="font-size: 18px; {NUM}">{price}</div>
</div>
<dl style="margin: 0; padding: 0 2px; display: grid; grid-template-columns: auto 1fr; column-gap: 8px; row-gap: 6px; font-size: 13px; line-height: 1.4">
<dt style="{dt}">저장</dt><dd style="margin: 0; {lbl(13, T['text'])}">{saved}</dd>
<dt style="{dt}">목적</dt><dd style="margin: 0; word-break: keep-all"><span style="display: inline-flex; align-items: center; gap: 6px; vertical-align: top">{dot("가을 트레일 러닝", T, 8)}</span> 가을 트레일 러닝{ai}</dd>
<dt style="{dt}">카테고리</dt><dd style="margin: 0">신발</dd>
<dt style="{dt}">판매처</dt><dd style="margin: 0; {lbl(13, T['text'], extra=' overflow: hidden; text-overflow: ellipsis;')}">{seller}</dd>
</dl>
</div>"""


def review_board(theme, title, init, home, danger="A"):
    T = THEMES[theme]
    items = review_items_js(T)
    dash = T["dash"]
    dup_sheet = f"""<sc-if value="{{{{dupOpen}}}}" hint-placeholder-val="{{{{false}}}}"><div style="position: absolute; inset: 0; z-index: 10; background: {T['scrim']}; backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px)"></div>
<div style="position: absolute; left: 0; right: 0; bottom: 0; max-height: calc(100% - 64px); z-index: 11; border-radius: 36px 36px 0 0; background: {T['sheet']}; display: flex; flex-direction: column">
<div style="align-self: center; width: 40px; height: 5px; margin-top: 10px; border-radius: 3px; background: {T['handle']}"></div>
<div style="padding: 8px 12px 0 20px; display: flex; align-items: flex-start; gap: 8px">
<div style="flex-grow: 1; display: flex; flex-direction: column; gap: 4px; padding-top: 6px"><h2 style="margin: 0; font-size: 20px; font-weight: 700">같은 상품일까요?</h2><p style="margin: 0; font-size: 14px; color: {T['sub']}">같은 상품으로 보이는 항목이 이미 있어요</p></div>
<button type="button" onClick="{{{{closeDup}}}}" aria-label="닫기" style="width: 44px; height: 44px; border-radius: 22px; border: none; background: {T['tile']}; display: flex; align-items: center; justify-content: center">{ICON_X}</button>
</div>
<div style="min-height: 0; overflow-y: auto; padding: 16px 20px 36px; display: flex; flex-direction: column; gap: 16px">
<div style="display: flex; gap: 10px">
{compare_card(T, theme, True)}
{compare_card(T, theme, False)}
</div>
<div style="display: flex; flex-direction: column; gap: 8px">
<div style="display: flex">{btn_primary(T, "둘 다 두기", "askBoth", h=52, radius=26)}</div>
<div style="display: flex; gap: 8px">{btn_secondary(T, "새 항목 지우기", "askNew", bg=T['tile'], h=52, radius=26)}{btn_secondary(T, "기존 항목 지우기", "askOld", bg=T['tile'], h=52, radius=26)}</div>
<p style="margin: 4px 0 0; font-size: 13px; line-height: 1.5; word-break: keep-all; color: {T['sub']}">어느 쪽도 자동으로 합치지 않아요. 고르지 않고 닫아도 두 항목 모두 남아요.</p>
</div>
</div>
</div></sc-if>"""
    new_meta = item_row(T, "beige", "호카 스피드고트 6", "새로 저장 · 오늘 · musinsa.com")
    old_meta = item_row(T, "brown", "호카 스피드고트 6", "이미 있던 상품 · 3주 전 · hoka.com")
    dialogs = (
        confirm_dialog(T, "confirmBoth", "둘 다 둘까요?", "beige", new_meta + old_meta,
                       ["같은 상품이 목록과 목적에 두 번 보여요", "나중에 상세에서 하나를 지울 수 있어요"], "둘 다 두기", "keepBoth", False, danger, theme)
        + confirm_dialog(T, "confirmNew", "새 항목을 지울까요?", "beige", new_meta,
                         ["새 항목과 이 확인 카드가 사라져요", "이미 있던 상품은 그대로 남아요", "되돌릴 수 없어요"], "새 항목 지우기", "delNew", True, danger, theme)
        + confirm_dialog(T, "confirmOld", "이미 있던 상품을 지울까요?", "brown", old_meta,
                         ["‘가을 트레일 러닝’ 후보에서 빠져요", "새 항목은 그대로 남아요", "되돌릴 수 없어요"], "기존 항목 지우기", "delOld", True, danger, theme)
    )
    row = lambda label, val: f"""<div style="display: flex; align-items: center; gap: 10px; min-height: 44px; padding: 0 4px 0 14px; border-radius: 14px; background: {T['tile']}">
<span style="min-width: 52px; white-space: nowrap; font-size: 13px; color: {T['sub']}">{label}</span>
{val}
<button type="button" style="all: unset; cursor: pointer; min-height: 44px; padding: 0 12px; font-size: 14px; color: {T['sub']}">변경</button>
</div>"""
    cat_val = '<span style="flex-grow: 1; min-width: 0; font-size: 15px; font-weight: 700">{{cur.cat}}</span>'
    pur_val = ('<span style="flex-grow: 1; min-width: 0; display: flex; align-items: center; gap: 8px">'
               '<sc-if value="{{cur.hasDot}}" hint-placeholder-val="{{true}}"><span aria-hidden="true" style="width: 10px; height: 10px; flex-shrink: 0; box-sizing: border-box; border-radius: 5px; {{cur.dotStyle}}"></span></sc-if>'
               '<span style="font-size: 15px; font-weight: 700; color: {{cur.purposeColor}}; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{cur.purpose}}</span></span>')
    body = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: {T['bg']}; color: {T['text']}; font-family: 'IBM Plex Sans KR', sans-serif; display: flex; flex-direction: column">
{header(T, "분류·목적 확인", home)}<sc-if value="{{{{notDone}}}}" hint-placeholder-val="{{{{true}}}}">
<div style="flex-grow: 1; min-height: 0; display: flex; flex-direction: column; padding: 12px 20px 36px">
<div style="position: relative; height: 574px; flex-shrink: 0">
<sc-if value="{{{{hasBack2}}}}" hint-placeholder-val="{{{{true}}}}"><div style="position: absolute; inset: 14px 6px -6px; border-radius: 28px; background: {T['back2']}; transform: rotate(3deg)"></div></sc-if>
<sc-if value="{{{{hasBack1}}}}" hint-placeholder-val="{{{{true}}}}"><div style="position: absolute; inset: 8px 2px -2px; border-radius: 28px; background: {T['back1']}; transform: rotate(-2.5deg)"></div></sc-if>
<article style="position: absolute; inset: 0; box-sizing: border-box; padding: 12px; border-radius: 28px; background: {T['card']}; border: 1.5px solid {T['line']}; display: flex; flex-direction: column; gap: 12px">
<div style="aspect-ratio: 1 / 1; flex-shrink: 0; box-sizing: border-box; padding: 32px; border-radius: 20px; background: {{{{cur.ph}}}}; display: flex; align-items: center; justify-content: center; overflow: hidden"><svg aria-hidden="true" width="100%" height="100%" viewBox="0 0 48 48" fill="none" stroke="{{{{cur.phS}}}}" stroke-width="3" stroke-linecap="round"><path d="M10 30v-6a14 14 0 0 1 28 0v6"></path><rect x="7" y="28" width="8" height="12" rx="3" fill="{{{{cur.phF}}}}"></rect><rect x="33" y="28" width="8" height="12" rx="3" fill="{{{{cur.phF}}}}"></rect></svg></div>
<div style="display: flex; flex-direction: column; gap: 4px; padding: 0 4px">
<div style="font-size: 18px; font-weight: 700; line-height: 1.3; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{{{{cur.name}}}}</div>
<div style="font-size: 20px; {NUM}">{{{{cur.price}}}}</div>
</div>
<div style="display: flex; flex-direction: column; gap: 6px">
{row("카테고리", cat_val)}
{row("목적", pur_val)}
</div>
<div style="height: 44px; flex-shrink: 0; display: flex; align-items: center">
<sc-if value="{{{{whyNotice}}}}" hint-placeholder-val="{{{{true}}}}"><div style="padding: 0 4px; font-size: 13px; line-height: 1.4; word-break: keep-all; color: {T['sub']}">{{{{cur.why}}}}</div></sc-if>
<sc-if value="{{{{dupOpenNotice}}}}" hint-placeholder-val="{{{{false}}}}"><div style="flex-grow: 1; box-sizing: border-box; height: 44px; display: flex; align-items: center; gap: 10px; padding: 0 2px 0 12px; border-radius: 14px; border: 1.5px dashed {dash}">
<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="8" y="8" width="12" height="12" rx="2"></rect><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"></path></svg>
<span style="flex-grow: 1; font-size: 13px; word-break: keep-all">같은 상품으로 보이는 항목이 있어요</span>
<button type="button" onClick="{{{{openDup}}}}" style="all: unset; cursor: pointer; min-height: 40px; padding: 0 12px; font-size: 14px; font-weight: 700">비교</button>
</div></sc-if>
<sc-if value="{{{{dupDoneNotice}}}}" hint-placeholder-val="{{{{false}}}}"><div style="flex-grow: 1; box-sizing: border-box; height: 44px; display: flex; align-items: center; gap: 8px; padding: 0 12px; border-radius: 14px; background: {T['tile']}; font-size: 13px"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12l5 5L20 7"></path></svg>{{{{dupDoneText}}}}</div></sc-if>
</div>
</article>
</div>
<div style="flex-grow: 1"></div>
<div style="padding-bottom: 12px; text-align: center; font-size: 13px; color: {T['sub']}">오른쪽으로 밀면 확정, 왼쪽으로 밀면 보류</div>
<div style="display: flex; gap: 10px">
{btn_secondary(T, '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M19 12H5M11 6l-6 6 6 6"></path></svg>보류', "hold")}
{btn_primary(T, '확정<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M5 12h14M13 6l6 6-6 6"></path></svg>', "confirm")}
</div>
</div>
</sc-if>
{done_block(T, "다 확인했어요", home)}{dup_sheet}
{dialogs}
</div>
"""
    script = f"""class Component extends DCLogic {{
constructor(props) {{ super(props); this.state = Object.assign({{ i: 0, ok: 0, later: 0, dup: false, confirm: null, dupDone: null }}, {init}); }}
renderVals() {{
const items = {items};
const s = this.state;
const done = s.i >= items.length;
const cur = done ? items[items.length - 1] : items[s.i];
const left = done ? 0 : items.length - s.i - 1;
return {{
cur: cur, done: done, notDone: !done,
pos: (Math.min(s.i, items.length - 1) + 1) + ' / ' + items.length,
hasBack1: left >= 1, hasBack2: left >= 2,
summary: '확정 ' + s.ok + ' · 보류 ' + s.later,
confirm: () => this.setState({{ i: s.i + 1, ok: s.ok + 1, dupDone: null }}),
hold: () => this.setState({{ i: s.i + 1, later: s.later + 1, dupDone: null }}),
restart: () => this.setState({{ i: 0, ok: 0, later: 0, dupDone: null }}),
whyNotice: !cur.hasDup,
dupOpenNotice: !!cur.hasDup && !s.dupDone, dupDoneNotice: !!cur.hasDup && !!s.dupDone,
dupDoneText: s.dupDone === 'both' ? '둘 다 두기로 했어요' : '이미 있던 상품을 지웠어요',
dupOpen: s.dup, openDup: () => this.setState({{ dup: true, confirm: null }}), closeDup: () => this.setState({{ dup: false }}),
askBoth: () => this.setState({{ confirm: 'both' }}), askNew: () => this.setState({{ confirm: 'new' }}), askOld: () => this.setState({{ confirm: 'old' }}),
confirmBoth: s.confirm === 'both', confirmNew: s.confirm === 'new', confirmOld: s.confirm === 'old', cancelConfirm: () => this.setState({{ confirm: null }}),
keepBoth: () => this.setState({{ dup: false, confirm: null, dupDone: 'both' }}),
delNew: () => this.setState({{ dup: false, confirm: null, i: s.i + 1 }}),
delOld: () => this.setState({{ dup: false, confirm: null, dupDone: 'old' }})
}};
}}
}}"""
    return head(title, T) + body + tail(script)


# ---------------------------------------------------------------- 정보 보완 연속 처리

def fill_board(theme, home):
    T = THEMES[theme]
    field = f"height: 56px; box-sizing: border-box; padding: 0 18px; border-radius: 20px; border: none; background: {T['field']}; font: inherit; font-size: 16px; color: inherit; outline: none"
    body = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: {T['bg']}; color: {T['text']}; font-family: 'IBM Plex Sans KR', sans-serif; display: flex; flex-direction: column">
{header(T, "정보 보완 필요", home)}<sc-if value="{{{{notDone}}}}" hint-placeholder-val="{{{{true}}}}">
<div style="flex-grow: 1; min-height: 0; overflow-y: auto; padding: 12px 20px 140px; display: flex; flex-direction: column; gap: 20px">
<div style="display: flex; align-items: center; gap: 12px; padding: 16px; border-radius: 28px; background: {T['card']}">
<span style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 14px; background: {T['icon_tile']}; display: flex; align-items: center; justify-content: center">{ICON_PEN}</span>
<div style="flex-grow: 1; display: flex; flex-direction: column; gap: 4px; min-width: 0">
<span style="font-size: 15px; font-weight: 700; word-break: keep-all">{{{{cur.msg}}}}</span>
<span style="{lbl(12, T['sub'])}">{{{{cur.domain}}}}</span>
</div>
<a href="#" style="min-height: 44px; padding: 0 4px; display: flex; align-items: center; gap: 4px; font-size: 14px">원본<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"></path></svg></a>
</div>
<sc-if value="{{{{cur.failed}}}}" hint-placeholder-val="{{{{true}}}}">
<div style="display: flex">{btn_secondary(T, '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"></path></svg>다시 분석하기', "reanalyze", h=52, radius=26)}</div>
<div style="display: flex; align-items: center; gap: 10px; font-size: 13px; color: {T['sub']}"><span style="flex-grow: 1; height: 1px; background: {T['line']}"></span>또는 직접 입력<span style="flex-grow: 1; height: 1px; background: {T['line']}"></span></div>
</sc-if>
<div style="display: flex; flex-direction: column; gap: 8px">
<span style="font-size: 13px; color: {T['sub']}">대표 이미지 · 선택</span>
<sc-if value="{{{{cur.noImg}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" style="height: 120px; border-radius: 20px; border: 1.5px dashed {T['dash']}; background: transparent; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; font-size: 14px; color: {T['sub']}"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="5" width="18" height="14" rx="3"></rect><circle cx="9" cy="11" r="2"></circle><path d="M21 16l-5-5-8 8"></path></svg>사진 보관함에서 추가</button></sc-if>
<sc-if value="{{{{cur.hasImg}}}}" hint-placeholder-val="{{{{false}}}}"><div style="width: 120px; height: 120px; box-sizing: border-box; padding: 14px; border-radius: 20px; background: {{{{cur.ph}}}}; display: flex; align-items: center; justify-content: center; overflow: hidden"><svg aria-hidden="true" width="100%" height="100%" viewBox="0 0 48 48" fill="none" stroke="{{{{cur.phS}}}}" stroke-width="3" stroke-linecap="round"><path d="M10 30v-6a14 14 0 0 1 28 0v6"></path><rect x="7" y="28" width="8" height="12" rx="3" fill="{{{{cur.phF}}}}"></rect><rect x="33" y="28" width="8" height="12" rx="3" fill="{{{{cur.phF}}}}"></rect></svg></div></sc-if>
</div>
<label style="display: flex; flex-direction: column; gap: 8px">
<span style="font-size: 13px; color: {T['sub']}">제품명 · 필수</span>
<sc-if value="{{{{cur.failed}}}}" hint-placeholder-val="{{{{true}}}}"><input type="text" placeholder="예: 소니 WH-1000XM6" style="{field}"></sc-if><sc-if value="{{{{cur.ok}}}}" hint-placeholder-val="{{{{false}}}}"><input type="text" defaultValue="{{{{cur.name}}}}" style="{field}"></sc-if>
</label>
<div style="display: flex; flex-direction: column; gap: 8px">
<span style="font-size: 13px; color: {T['sub']}">카테고리 · 필수</span>
<button type="button" style="height: 56px; box-sizing: border-box; padding: 0 14px 0 18px; border-radius: 20px; border: none; background: {T['field']}; display: flex; align-items: center; justify-content: space-between; font-size: 16px; color: {T['sub']}">카테고리 선택{ICON_RIGHT}</button>
</div>
</div>
<div style="position: absolute; left: 0; right: 0; bottom: 0; padding: 12px 20px 36px; background: {T['bg']}; display: flex; gap: 10px">
{btn_secondary(T, "건너뛰기", "skip")}
{btn_primary(T, "저장하고 다음", "save", extra="; flex-grow: 2")}
</div>
</sc-if>
{done_block(T, "보완을 마쳤어요", home)}</div>
"""
    items = []
    for it in [dict(failed=True, photo=None, name="", domain="coupang.com", msg="상품 정보를 가져오지 못했어요"),
               dict(failed=False, photo="green", name="오크 원목 사이드 테이블", domain="ohou.se", msg="카테고리를 정하지 못했어요"),
               dict(failed=False, photo="white", name="FiiO BTR7", domain="fiio.com", msg="카테고리를 지워서 비었어요")]:
        bg, s, f = PHOTO[it["photo"] or "white"]
        items.append("{" + f'"failed": {"true" if it["failed"] else "false"}, "hasImg": {"true" if it["photo"] else "false"}, "name": "{it["name"]}", "domain": "{it["domain"]}", "msg": "{it["msg"]}", "ph": "{bg}", "phS": "{s}", "phF": "{f}"' + "}")
    script = f"""class Component extends DCLogic {{
constructor(props) {{ super(props); this.state = {{ i: 0, saved: 0, skipped: 0 }}; }}
renderVals() {{
const items = [{", ".join(items)}];
const s = this.state;
const done = s.i >= items.length;
const cur = items[Math.min(s.i, items.length - 1)];
return {{
cur: {{ ...cur, noImg: !cur.hasImg, ok: !cur.failed }}, done: done, notDone: !done,
pos: (Math.min(s.i, items.length - 1) + 1) + ' / ' + items.length,
summary: '저장 ' + s.saved + ' · 건너뜀 ' + s.skipped,
reanalyze: () => {{}},
save: () => this.setState({{ i: s.i + 1, saved: s.saved + 1 }}),
skip: () => this.setState({{ i: s.i + 1, skipped: s.skipped + 1 }}),
restart: () => this.setState({{ i: 0, saved: 0, skipped: 0 }})
}};
}}
}}"""
    return head(f"{T['name']} 홈 정보 보완 연속 처리", T) + body + tail(script)


# ---------------------------------------------------------------- 홈 (할 일 접고 펼치기)

def carousel_item(T, kind, name, meta, empty=False):
    if empty:
        ph = f'<span style="width: 92px; height: 92px; box-sizing: border-box; border-radius: 20px; border: 1.5px dashed {T["dash"]}; display: flex; align-items: center; justify-content: center; font-size: 12px; color: {T["sub"]}">사진 없음</span>'
        nm = f'<span style="font-size: 13px; line-height: 1.35; color: {T["sub"]}">{name}</span>'
    else:
        ph = f'<div style="width: 92px; height: 92px; box-sizing: border-box; padding: 12px; border-radius: 20px; background: {PHOTO[kind][0]}; display: flex; align-items: center; justify-content: center; overflow: hidden">{photo_svg(kind, "80%")}</div>'
        nm = f'<span style="font-size: 13px; line-height: 1.35; word-break: keep-all">{name}</span>'
    return f'<a href="#" style="flex-shrink: 0; width: 92px; display: flex; flex-direction: column; gap: 6px">{ph}{nm}<span style="{lbl(11, T["sub"])}">{meta}</span></a>'


def todo_card(T, n, icon, title, desc, count, target, inner):
    main_inner = (f'<span style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 14px; background: {T["icon_tile"]}; display: flex; align-items: center; justify-content: center">{icon}</span>'
                  f'<span style="flex-grow: 1; display: flex; flex-direction: column; gap: 2px; min-width: 0"><span style="font-size: 17px; font-weight: 700">{title}</span><span style="font-size: 13px; color: {T["sub"]}; word-break: keep-all">{desc}</span></span>'
                  f'<span style="font-size: 26px; {NUM}">{count}</span>')
    if target:
        main = f'<a href="{target}" style="flex-grow: 1; min-width: 0; min-height: 44px; display: flex; align-items: center; gap: 14px">{main_inner}</a>'
    else:
        main = f'<button type="button" onClick="{{{{t{n}}}}}" style="all: unset; cursor: pointer; flex-grow: 1; min-width: 0; min-height: 44px; display: flex; align-items: center; gap: 14px">{main_inner}</button>'
    chevron = ICON_DOWN.format(st="{{r%d}}" % n)
    return f"""<div style="border-radius: 28px; background: {T['card']}; overflow: hidden">
<div style="padding: 16px 6px 16px 16px; display: flex; align-items: center; gap: 4px">
{main}
<button type="button" onClick="{{{{t{n}}}}}" aria-label="{{{{l{n}}}}}" aria-expanded="{{{{o{n}}}}}" style="width: 44px; height: 44px; flex-shrink: 0; border: none; border-radius: 22px; background: transparent; color: {T['text']}; display: flex; align-items: center; justify-content: center">{chevron}</button>
</div>
<sc-if value="{{{{o{n}}}}}" hint-placeholder-val="{{{{false}}}}">{inner}</sc-if>
</div>"""


def purpose_card(T, name, face, kinds, meta, target):
    thumbs = "".join(
        f'<div style="width: 44px; height: 44px; box-sizing: border-box; border-radius: 14px; overflow: hidden; border: 2px solid {face}; margin-left: {0 if i == 0 else -16}px"><div style="width: 40px; height: 40px; box-sizing: border-box; padding: 5px; background: {PHOTO[k][0]}; display: flex; align-items: center; justify-content: center">{photo_svg(k, "100%")}</div></div>'
        for i, k in enumerate(kinds))
    return (f'<a href="{target}" style="box-sizing: border-box; padding: 14px 16px; border-radius: 28px; background: {face}; color: #1D1D1D; display: flex; align-items: center; gap: 14px">'
            f'<div style="display: flex; flex-shrink: 0">{thumbs}</div>'
            f'<span style="flex-grow: 1; display: flex; flex-direction: column; gap: 4px; min-width: 0"><span style="font-family: \'Do Hyeon\', sans-serif; font-weight: 400; font-size: 20px; line-height: 1.0">{name}</span><span style="{lbl(12, "#1D1D1D")}">{meta}</span></span>{ICON_RIGHT}</a>')


def home_board(theme):
    T = THEMES[theme]
    s = theme
    review = f"FHomeReviewFlow{s}.dc.html"
    fill = f"FHomeFillFlow{s}.dc.html"
    car1 = (f'<div style="display: flex; gap: 12px; overflow-x: auto; padding: 0 16px 16px">'
            + carousel_item(T, "white", "마샬 MAJOR V", "헤드폰")
            + carousel_item(T, "beige", "호카 스피드고트 6", "신발 · 중복 후보")
            + carousel_item(T, "brown", "발뮤다 더 토스터", "주방 가전") + "</div>")
    car2 = (f'<div style="display: flex; gap: 12px; overflow-x: auto; padding: 0 16px 16px">'
            + carousel_item(T, None, "제품명 없음", "coupang.com", empty=True)
            + carousel_item(T, "green", "오크 원목 사이드 테이블", "카테고리 없음")
            + carousel_item(T, "white", "FiiO BTR7", "카테고리 삭제됨") + "</div>")
    proc = (f'<div style="margin: 0 12px 12px; padding: 10px; border-radius: 20px; background: {T["tile"]}; display: flex; align-items: center; gap: 12px">'
            f'<span style="width: 52px; height: 52px; flex-shrink: 0; border-radius: 14px; background: {T["card"]}; display: flex; align-items: center; justify-content: center">{ICON_SUN}</span>'
            f'<span style="flex-grow: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px"><span style="font-size: 14px; font-weight: 500">29cm.co.kr</span><span style="{lbl(12, T["sub"])}">상품 정보 추출 중</span></span>'
            f'<button type="button" style="height: 44px; padding: 0 16px; border-radius: 22px; border: none; background: {T["card"]}; font-size: 14px">삭제</button></div>')
    cur_bg = "#FFFFFF" if theme == "L" else "#F4F3F0"

    def tab(href, label, icon, cur):
        aria = ' aria-current="page"' if cur else ""
        look = f"background: {cur_bg}; color: #1D1D1D; font-weight: 700;" if cur else f"color: {T['tab_text']};"
        return (f'<a href="{href}"{aria} style="flex-grow: 1; height: 48px; border-radius: 24px; {look} display: flex; '
                f'align-items: center; justify-content: center; gap: 6px; font-size: 15px; text-decoration: none">{icon}{label}</a>')
    nav = (f'<nav aria-label="하단 탭" style="position: absolute; left: 16px; right: 16px; bottom: 24px; z-index: 5; height: 64px; box-sizing: border-box; padding: 8px; border-radius: 32px; background: {T["tab"]}; display: flex; align-items: center; justify-content: space-between">'
           + tab(f"FHome{s}.dc.html", "홈", '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 11l8-7 8 7v9h-5v-6H9v6H4z"></path></svg>', True)
           + tab(f"FCategoryHome{s}.dc.html", "카테고리", '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="4" width="7" height="7" rx="2"></rect><rect x="13" y="4" width="7" height="7" rx="2"></rect><rect x="4" y="13" width="7" height="7" rx="2"></rect><rect x="13" y="13" width="7" height="7" rx="2"></rect></svg>', False)
           + tab(f"FPurposeHome{s}.dc.html", "목적", '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="8" width="16" height="12" rx="3"></rect><path d="M7 5h10"></path></svg>', False)
           + "</nav>")
    detail = f"FPurposeDetail{s}.dc.html"
    body = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: {T['bg']}; color: {T['text']}; font-family: 'IBM Plex Sans KR', sans-serif">
<div style="position: absolute; inset: 0; overflow-y: auto">
<div style="padding: 56px 20px 140px; display: flex; flex-direction: column; gap: 32px">
<header style="display: flex; align-items: flex-start; justify-content: space-between">
<div style="display: flex; flex-direction: column; gap: 6px"><h1 style="margin: 0; font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 28px; line-height: 1.0">홈</h1><span style="{lbl(13, T['sub'])}">할 일 7개</span></div>
<a href="#" aria-label="설정" style="width: 44px; height: 44px; border-radius: 22px; background: {T['card']}; display: flex; align-items: center; justify-content: center"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="3"></circle><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1"></path></svg></a>
</header>
<section style="display: flex; flex-direction: column; gap: 12px">
<h2 style="margin: 0; font-size: 13px; font-weight: 500; color: {T['sub']}">할 일</h2>
{todo_card(T, 1, ICON_SPARK, "분류·목적 확인", "AI가 정리한 결과를 확인해 주세요", 3, review, car1)}
{todo_card(T, 2, ICON_PEN, "정보 보완 필요", "이름과 카테고리만 알려 주세요", 3, fill, car2)}
{todo_card(T, 3, ICON_SUN, "분류 중", "상품 정보를 가져오고 있어요", 1, None, proc)}
</section>
<section style="display: flex; flex-direction: column; gap: 12px">
<div style="display: flex; justify-content: space-between; align-items: center"><h2 style="margin: 0; font-size: 13px; font-weight: 500; color: {T['sub']}">비교 중인 목적</h2><a href="FPurposeHome{s}.dc.html" style="min-height: 44px; display: flex; align-items: center; font-size: 14px; color: {T['sub']}">목적 전체</a></div>
{purpose_card(T, "출퇴근 헤드폰", "#F96857", ["white", "brown", "beige"], "후보 5 · 어제 후보 추가", detail)}
{purpose_card(T, "가을 트레일 러닝", "#F9CD61", ["beige", "white", "green"], "후보 3 · 3일 전 후보 추가", detail)}
{purpose_card(T, "홈오피스 의자", "#7477FF", ["white", "brown", "white"], "후보 2 · 1주 전 후보 추가", detail)}
</section>
</div>
</div>
{nav}
</div>
"""
    script = """class Component extends DCLogic {
constructor(props) { super(props); this.state = { o1: false, o2: false, o3: false }; }
renderVals() {
const s = this.state;
const v = {};
[1, 2, 3].forEach((n) => {
const o = !!s['o' + n];
v['o' + n] = o;
v['l' + n] = o ? '접기' : '펼치기';
v['r' + n] = 'transition: transform 200ms ease; transform: rotate(' + (o ? 180 : 0) + 'deg)';
v['t' + n] = () => this.setState({ ['o' + n]: !o });
});
return v;
}
}"""
    return head(f"{T['name']} 홈 할 일 접고 펼치기", T) + body + tail(script)


# ---------------------------------------------------------------- 보조 라벨 서체 후보

def font_pick_board(key, caption):
    T = THEMES["L"]
    fam = LABEL_FONTS[key][0]
    l = lambda size, color, extra="": lbl(size, color, key, extra)
    prod = lambda kind, name, price, meta: (
        f'<div style="flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 8px"><div style="aspect-ratio: 1 / 1; box-sizing: border-box; padding: 18px; border-radius: 20px; background: {PHOTO[kind][0]}; display: flex; align-items: center; justify-content: center">{photo_svg(kind, "100%")}</div>'
        f'<span style="display: flex; flex-direction: column; gap: 4px; padding: 0 2px"><span style="font-size: 14px; line-height: 1.35">{name}</span><span style="font-size: 18px; {NUM}">{price}</span><span style="{l(12, T["sub"])}">{meta}</span></span></div>')
    body = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: {T['bg']}; color: {T['text']}; font-family: 'IBM Plex Sans KR', sans-serif">
<div style="padding: 40px 20px 24px; display: flex; flex-direction: column; gap: 24px">
<div style="display: flex; flex-direction: column; gap: 4px"><span style="font-size: 13px; color: {T['sub']}">보조 라벨 서체</span><span style="font-size: 20px; font-weight: 700">{caption}</span></div>
<header style="display: flex; flex-direction: column; gap: 6px"><h1 style="margin: 0; font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 28px; line-height: 1.0">홈</h1><span style="{l(13, T['sub'])}">할 일 7개</span></header>
<div style="border-radius: 28px; background: {T['card']}; padding: 16px; display: flex; gap: 12px">
<div style="width: 92px; display: flex; flex-direction: column; gap: 6px"><div style="width: 92px; height: 92px; box-sizing: border-box; padding: 12px; border-radius: 20px; background: #E9E8E4; display: flex; align-items: center; justify-content: center">{photo_svg("beige", "100%")}</div><span style="font-size: 13px">호카 스피드고트 6</span><span style="{l(11, T['sub'])}">신발 · 중복 후보</span></div>
<div style="width: 92px; display: flex; flex-direction: column; gap: 6px"><span style="width: 92px; height: 92px; box-sizing: border-box; border-radius: 20px; border: 1.5px dashed {T['dash']}; display: flex; align-items: center; justify-content: center; font-size: 12px; color: {T['sub']}">사진 없음</span><span style="font-size: 13px; color: {T['sub']}">제품명 없음</span><span style="{l(11, T['sub'])}">coupang.com</span></div>
<div style="width: 92px; display: flex; flex-direction: column; gap: 6px"><div style="width: 92px; height: 92px; box-sizing: border-box; padding: 12px; border-radius: 20px; background: #3F4B44; display: flex; align-items: center; justify-content: center">{photo_svg("green", "100%")}</div><span style="font-size: 13px">오크 원목 사이드 테이블</span><span style="{l(11, T['sub'])}">카테고리 없음</span></div>
</div>
<a href="#" style="box-sizing: border-box; padding: 16px; border-radius: 28px; background: #F96857; color: #1D1D1D; display: flex; flex-direction: column; gap: 4px"><span style="font-family: 'Do Hyeon', sans-serif; font-size: 20px; line-height: 1.0">출퇴근 헤드폰</span><span style="{l(12, '#1D1D1D')}">후보 5 · 어제 후보 추가</span></a>
<div style="display: flex; gap: 12px">
{prod("white", "소니 WH-1000XM6", "₩549,000", "무신사 · 2일 전 확인")}
{prod("brown", "보스 QuietComfort Ultra", "₩499,000", "보스 공식몰 · 2일 전 확인")}
</div>
</div>
</div>
"""
    return head(f"보조 라벨 서체 {caption}", T, key) + body + tail("class Component extends DCLogic {\nrenderVals() { return {}; }\n}")


def main():
    boards = {}
    for th in "LD":
        home = f"FHome{th}.dc.html"
        boards[f"FHome{th}.dc.html"] = home_board(th)
        boards[f"FHomeReviewFlow{th}.dc.html"] = review_board(th, f"{THEMES[th]['name']} 홈 분류·목적 확인 연속 처리", "{}", home)
        boards[f"FDuplicateCompare{th}.dc.html"] = review_board(th, f"{THEMES[th]['name']} 중복 후보 비교", "{ i: 1, dup: true }", home)
        boards[f"FDuplicateConfirmBoth{th}.dc.html"] = review_board(th, f"{THEMES[th]['name']} 중복 후보 둘 다 두기 확인", "{ i: 1, dup: true, confirm: 'both' }", home)
        boards[f"FDuplicateConfirmNew{th}.dc.html"] = review_board(th, f"{THEMES[th]['name']} 중복 후보 새 항목 지우기 확인", "{ i: 1, dup: true, confirm: 'new' }", home)
        boards[f"FDuplicateConfirmOld{th}.dc.html"] = review_board(th, f"{THEMES[th]['name']} 중복 후보 기존 항목 지우기 확인", "{ i: 1, dup: true, confirm: 'old' }", home)
        boards[f"FHomeFillFlow{th}.dc.html"] = fill_board(th, home)
        for d in "ABC":
            boards[f"PickDanger{d}{th}.dc.html"] = review_board(th, f"위험 색 후보 {d} {THEMES[th]['name']}", "{ i: 1, dup: true, confirm: 'old' }", home, danger=d)
    for key, cap in [("plex", "A · IBM Plex Sans KR"), ("noto", "B · Noto Sans KR"), ("gothic", "C · Gothic A1"), ("mono", "지금 · IBM Plex Mono")]:
        boards[f"PickLabel{key.capitalize()}.dc.html"] = font_pick_board(key, cap)
    OUT = os.path.join(sys.argv[1], "project")
    os.makedirs(OUT, exist_ok=True)
    for name, html in boards.items():
        with open(os.path.join(OUT, name), "w") as f:
            f.write(html)
    print("\n".join(sorted(boards)))


if __name__ == "__main__":
    main()
