"""전체 화면 · 카테고리 묶음 보드를 만든다.

python3 gen_category.py <out_root>
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen_full_home import (THEMES, PHOTO, PURPOSE, DANGER, NUM, ICON_X, ICON_PEN, ICON_SPARK, lbl, head, tail,
                           photo_svg, btn_primary, btn_secondary)

ICON_BACK = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M15 5l-7 7 7 7"></path></svg>'
ICON_MORE = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="5" cy="12" r="1.5"></circle><circle cx="12" cy="12" r="1.5"></circle><circle cx="19" cy="12" r="1.5"></circle></svg>'
ICON_TRASH = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M5 7h14M10 7V5h4v2M7 7l1 13h8l1-13"></path></svg>'
ICON_PLUS = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 5v14M5 12h14"></path></svg>'
ICON_DOWN = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M6 9l6 6 6-6"></path></svg>'
ICON_SMALL_X = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M6 6l12 12M18 6L6 18"></path></svg>'

TOPS = ["패션·잡화", "뷰티·퍼스널케어", "디지털·IT", "가구·인테리어", "생활·주방·가전", "스포츠·아웃도어·여행",
        "취미·문화·컬렉터블", "유아·키즈", "반려동물", "자동차·모빌리티", "건강·웰빙"]

MINE = {
    "dac": {"name": "오디오 케이블·DAC", "top": "디지털·IT", "count": 2, "desc": "헤드폰에 연결하는 DAC와 교체용 케이블",
            "examples": ["포터블 DAC", "이어폰 케이블"],
            "items": [("FiiO BTR7", "₩259,000", "1 / 1", "white", "fiio.com · 3일 전 확인"),
                      ("오디오퀘스트 DragonFly Red", "₩299,000", "4 / 5", "brown", "29CM · 1주 전 확인")]},
    "lego": {"name": "레고", "top": "취미·문화·컬렉터블", "count": 0, "desc": "테크닉, 아이디어 시리즈", "examples": ["테크닉"], "items": []},
}

HEADPHONES = [
    ("소니 WH-1000XM6", "₩549,000", "1 / 1", "white", "출퇴근 헤드폰", "2일 전 확인", False),
    ("보스 QuietComfort Ultra", "₩499,000", "4 / 5", "brown", "출퇴근 헤드폰", "2일 전 확인", False),
    ("젠하이저 MOMENTUM 4", "₩389,000", "3 / 4", "beige", None, "5일 전 확인", False),
    ("애플 AirPods Max", "₩769,000", "1 / 1", "white", "출퇴근 헤드폰", "1주 전 확인", False),
    ("마샬 MAJOR V", "₩229,000", "4 / 5", "green", None, "1주 전 확인", True),
    ("뱅앤올룹슨 Beoplay H95", "₩1,190,000", "3 / 4", "beige", None, "2주 전 확인", False),
    ("소니 ULT WEAR", "₩279,000", "1 / 1", "white", "출퇴근 헤드폰", "2주 전 확인", False),
    ("오디오테크니카 ATH-M50x", "₩219,000", "4 / 5", "brown", None, "3주 전 확인", False),
]


def home_data(s):
    def t(name, count, href="#", custom=False):
        return {"name": name, "count": count, "href": href, "custom": custom}
    return [
        {"name": "패션·잡화", "types": [t("신발", 5), t("아우터", 3), t("가방", 3), t("상의", 2), t("패션 소품", 2)]},
        {"name": "뷰티·퍼스널케어", "types": [t("스킨케어", 2), t("향수", 2)]},
        {"name": "디지털·IT", "types": [t("헤드폰", 8, f"FCategoryList{s}.dc.html"), t("키보드", 3), t("카메라·액션캠", 3), t("모니터", 2),
                                       t("마우스·트랙패드", 2), t("웨어러블 기기", 2), t("이어폰", 1), t("태블릿", 1),
                                       t("오디오 케이블·DAC", 2, f"FCategoryListCustom{s}.dc.html", True)]},
        {"name": "가구·인테리어", "types": [t("조명", 3), t("의자", 2), t("책상·테이블", 2)]},
        {"name": "생활·주방·가전", "types": [t("주방 가전", 3), t("공기·온습도 관리", 2), t("조리 도구", 1)]},
        {"name": "스포츠·아웃도어·여행", "types": [t("캠핑 용품", 4), t("러닝 용품", 2), t("여행 가방·캐리어", 2), t("백패킹 소품", 1, "#", True)]},
        {"name": "취미·문화·컬렉터블", "types": [t("피규어·컬렉터블", 2), t("보드게임·퍼즐", 1), t("레고", 0, f"FCategoryListCustomEmpty{s}.dc.html", True)]},
        {"name": "건강·웰빙", "types": [t("수면·회복 용품", 1)]},
    ]


def nav(T, theme, cur="category"):
    s = theme
    cur_bg = "#FFFFFF" if theme == "L" else "#F4F3F0"

    def tab(href, label, icon, cur):
        aria = ' aria-current="page"' if cur else ""
        look = f"background: {cur_bg}; color: #1D1D1D; font-weight: 700;" if cur else f"color: {T['tab_text']};"
        return (f'<a href="{href}"{aria} style="flex-grow: 1; height: 48px; border-radius: 24px; {look} display: flex; '
                f'align-items: center; justify-content: center; gap: 6px; font-size: 15px; text-decoration: none">{icon}{label}</a>')
    return (f'<nav aria-label="하단 탭" style="position: absolute; left: 16px; right: 16px; bottom: 24px; z-index: 5; height: 64px; box-sizing: border-box; padding: 8px; border-radius: 32px; background: {T["tab"]}; display: flex; align-items: center; justify-content: space-between">'
            + tab(f"FHome{s}.dc.html", "홈", '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 11l8-7 8 7v9h-5v-6H9v6H4z"></path></svg>', cur == "home")
            + tab(f"FCategoryHome{s}.dc.html", "카테고리", '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="4" width="7" height="7" rx="2"></rect><rect x="13" y="4" width="7" height="7" rx="2"></rect><rect x="4" y="13" width="7" height="7" rx="2"></rect><rect x="13" y="13" width="7" height="7" rx="2"></rect></svg>', cur == "category")
            + tab(f"FPurposeHome{s}.dc.html", "목적", '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="8" width="16" height="12" rx="3"></rect><path d="M7 5h10"></path></svg>', cur == "purpose")
            + "</nav>")


def scrim(T, z):
    return f'<div style="position: absolute; inset: 0; z-index: {z}; background: {T["scrim"]}; backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px)"></div>'


def field_label(left, right, T):
    return f'<span style="display: flex; justify-content: space-between; font-size: 13px; color: {T["sub"]}"><span>{left}</span><span style="{lbl(12, T["sub"])}">{right}</span></span>'


def sheet(T, mode_edit, mode_create):
    """세부 카테고리 만들기·편집 시트. 시트 위 입력칸은 아이콘 타일색 면이다."""
    fld = f"box-sizing: border-box; width: 100%; height: 56px; padding: 0 18px; border-radius: 20px; border: none; background: {T['tile']}; font: inherit; font-size: 16px; color: inherit; outline: none"
    area = f"box-sizing: border-box; width: 100%; padding: 16px 18px; border-radius: 20px; border: none; background: {T['tile']}; font: inherit; font-size: 16px; line-height: 1.5; color: inherit; outline: none; resize: none"
    chip_x = f'<button type="button" onClick="{{{{markDirty}}}}" aria-label="예시 빼기" style="all: unset; cursor: pointer; width: 28px; height: 28px; display: flex; align-items: center; justify-content: center">{ICON_SMALL_X}</button>'
    edit = ""
    if mode_edit:
        edit = f"""<sc-if value="{{{{isEdit}}}}" hint-placeholder-val="{{{{true}}}}"><div style="{lbl(13, T['sub'])}">‘{{{{cTop}}}}’ 아래 · 상품 {{{{cCount}}}}개</div>
<label style="display: flex; flex-direction: column; gap: 8px">{field_label("이름 · 필수", "최대 40자", T)}<input type="text" maxLength="40" defaultValue="{{{{cName}}}}" placeholder="예: 오디오 케이블·DAC" onInput="{{{{markDirty}}}}" style="{fld}"></label>
<label style="display: flex; flex-direction: column; gap: 8px">{field_label("설명 · 선택", "최대 200자", T)}<textarea maxLength="200" rows="2" defaultValue="{{{{cDesc}}}}" placeholder="어떤 상품을 넣을지 적어 두면 AI 분류에 참고해요" onInput="{{{{markDirty}}}}" style="{area}"></textarea></label>
<div style="display: flex; flex-direction: column; gap: 8px">{field_label("포함 예시 · 선택", "{{exCount}}", T)}
<div style="display: flex; flex-wrap: wrap; gap: 8px"><sc-for list="{{{{cExamples}}}}" as="e" hint-placeholder-count="2"><span style="height: 36px; padding: 0 6px 0 14px; border-radius: 18px; background: {T['tile']}; display: flex; align-items: center; gap: 4px; font-size: 14px">{{{{e.name}}}}{chip_x}</span></sc-for></div>
<input type="text" maxLength="60" placeholder="예시를 입력하고 추가" onInput="{{{{markDirty}}}}" style="{fld}"></div>
<sc-if value="{{{{hasItems}}}}" hint-placeholder-val="{{{{false}}}}"><p style="margin: 0; font-size: 13px; line-height: 1.5; word-break: keep-all; color: {T['sub']}">{{{{renameNote}}}}</p></sc-if>
</sc-if>"""
    create = ""
    if mode_create:
        on = f"background: {T['inv']}; color: {T['inv_text']}; font-weight: 700"
        off = f"background: {T['tile']}; color: {T['text']}"
        top_btn = lambda look, hint: (f'<sc-if value="{{{{t.{hint}}}}}" hint-placeholder-val="{{{{{"false" if hint == "on" else "true"}}}}}"><button type="button" onClick="{{{{t.pick}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; height: 40px; padding: 0 16px; border-radius: 20px; display: flex; align-items: center; font-size: 14px; white-space: nowrap; {look}">{{{{t.name}}}}</button></sc-if>')
        create = f"""<sc-if value="{{{{isCreate}}}}" hint-placeholder-val="{{{{false}}}}"><div style="display: flex; flex-direction: column; gap: 8px">{field_label("상위 카테고리 · 필수", "", T)}<div style="display: flex; flex-wrap: wrap; gap: 8px"><sc-for list="{{{{tops}}}}" as="t" hint-placeholder-count="11">{top_btn(on, "on")}{top_btn(off, "off")}</sc-for></div></div>
<label style="display: flex; flex-direction: column; gap: 8px">{field_label("이름 · 필수", "최대 40자", T)}<input type="text" maxLength="40" placeholder="예: 오디오 케이블·DAC" onInput="{{{{markDirty}}}}" style="{fld}"></label>
<label style="display: flex; flex-direction: column; gap: 8px">{field_label("설명 · 선택", "최대 200자", T)}<textarea maxLength="200" rows="2" placeholder="어떤 상품을 넣을지 적어 두면 AI 분류에 참고해요" onInput="{{{{markDirty}}}}" style="{area}"></textarea></label>
<div style="display: flex; flex-direction: column; gap: 8px">{field_label("포함 예시 · 선택", "최대 5개", T)}<input type="text" maxLength="60" placeholder="예시를 입력하고 추가" onInput="{{{{markDirty}}}}" style="{fld}"></div>
<p style="margin: 0; font-size: 13px; line-height: 1.5; word-break: keep-all; color: {T['sub']}">공용 카테고리는 바뀌지 않고, 나만 쓰는 세부 카테고리로 추가돼요.</p></sc-if>"""
    title = ((f'<sc-if value="{{{{isEdit}}}}" hint-placeholder-val="{{{{true}}}}">세부 카테고리 편집</sc-if>' if mode_edit else "")
             + (f'<sc-if value="{{{{isCreate}}}}" hint-placeholder-val="{{{{false}}}}">신규 카테고리</sc-if>' if mode_create else ""))
    ok = ((f'<sc-if value="{{{{isEdit}}}}" hint-placeholder-val="{{{{true}}}}">저장</sc-if>' if mode_edit else "")
          + (f'<sc-if value="{{{{isCreate}}}}" hint-placeholder-val="{{{{false}}}}">만들기</sc-if>' if mode_create else ""))
    return f"""<sc-if value="{{{{sheetOpen}}}}" hint-placeholder-val="{{{{false}}}}">{scrim(T, 10)}
<div style="position: absolute; left: 0; right: 0; top: 96px; bottom: 0; z-index: 11; border-radius: 36px 36px 0 0; background: {T['sheet']}; display: flex; flex-direction: column">
<div style="align-self: center; width: 40px; height: 5px; margin-top: 10px; border-radius: 3px; background: {T['handle']}"></div>
<div style="padding: 8px 12px 0 20px; display: flex; align-items: center; gap: 8px"><h2 style="flex-grow: 1; margin: 0; font-size: 20px; font-weight: 700">{title}</h2><button type="button" onClick="{{{{closeSheet}}}}" aria-label="닫기" style="width: 44px; height: 44px; border-radius: 22px; border: none; background: {T['tile']}; display: flex; align-items: center; justify-content: center">{ICON_X}</button></div>
<div style="flex-grow: 1; min-height: 0; overflow-y: auto; padding: 16px 20px 120px; display: flex; flex-direction: column; gap: 20px">
{edit}{create}
</div>
<div style="position: absolute; left: 0; right: 0; bottom: 0; padding: 12px 20px 36px; background: {T['sheet']}; display: flex; gap: 10px">
{btn_secondary(T, "취소", "closeSheet", bg=T['tile'])}
{btn_primary(T, ok, "save", extra="; flex-grow: 2")}
</div>
</div></sc-if>"""


def dialog(T, key, label, body, cancel_label, cancel_on, ok_html):
    return f"""<sc-if value="{{{{{key}}}}}" hint-placeholder-val="{{{{false}}}}">{scrim(T, 20)}
<div role="dialog" aria-label="{label}" style="position: absolute; left: 24px; right: 24px; top: 50%; transform: translateY(-50%); z-index: 21; box-sizing: border-box; padding: 24px 20px 20px; border-radius: 36px; background: {T['sheet']}; display: flex; flex-direction: column; gap: 12px">
{body}
<div style="display: flex; gap: 10px; margin-top: 4px"><button type="button" onClick="{{{{{cancel_on}}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: {T['tile']}; color: {T['text']}; font-size: 16px; font-weight: 500">{cancel_label}</button>{ok_html}</div>
</div></sc-if>"""


def discard_dialog(T):
    body = ('<h2 style="margin: 0; font-size: 20px; font-weight: 700">변경 사항을 버릴까요?</h2>'
            '<ul style="margin: 0; padding-left: 20px; font-size: 15px; line-height: 1.6"><li>편집한 내용이 저장되지 않아요</li></ul>')
    ok = f'<button type="button" onClick="{{{{discardAll}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: {T["inv"]}; color: {T["inv_text"]}; font-size: 16px; font-weight: 700">버리기</button>'
    return dialog(T, "isDiscard", "변경 사항을 버릴까요?", body, "계속 편집", "keepEditing", ok)


def rail_name(n):
    return n.replace("·", "·​")


# ---------------------------------------------------------------- 카테고리 첫 화면

def category_home(theme, title, init):
    T = THEMES[theme]
    s = theme
    data = home_data(s)
    for t in data:
        t["label"] = rail_name(t["name"])
    on = f"border-left: 3px solid {T['text']}; padding: 8px 10px 8px 17px; font-weight: 700"
    off = f"padding: 8px 10px 8px 20px; color: {T['sub']}"
    cur_attr = {"on": ' aria-current="true"', "off": ""}
    rb = lambda look, key, hint: (f'<sc-if value="{{{{r.{key}}}}}" hint-placeholder-val="{{{{{hint}}}}}"><button type="button" onClick="{{{{r.pick}}}}"{cur_attr[key]} style="all: unset; cursor: pointer; box-sizing: border-box; min-height: 52px; {look}; display: flex; align-items: center; font-size: 15px; line-height: 1.3; word-break: keep-all">{{{{r.label}}}}</button></sc-if>')
    body = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: {T['bg']}; color: {T['text']}; font-family: 'IBM Plex Sans KR', sans-serif">
<div style="position: absolute; inset: 0; display: flex; flex-direction: column">
<header style="padding: 56px 20px 16px; display: flex; flex-direction: column; gap: 6px"><h1 style="margin: 0; font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 28px; line-height: 1.0">카테고리</h1><span style="{lbl(13, T['sub'])}">상품 69개</span></header>
<div style="flex-grow: 1; min-height: 0; display: flex; border-top: 1px solid {T['line']}">
<nav aria-label="상위 카테고리" style="width: 124px; flex-shrink: 0; padding: 8px 0 120px; display: flex; flex-direction: column; overflow-y: auto"><sc-for list="{{{{rail}}}}" as="r" hint-placeholder-count="8">{rb(on, "on", "false")}{rb(off, "off", "true")}</sc-for></nav>
<section style="flex-grow: 1; min-width: 0; padding: 8px 16px 120px 8px; display: flex; flex-direction: column; gap: 8px; overflow-y: auto"><h2 style="margin: 0; min-height: 44px; display: flex; align-items: center; font-size: 13px; font-weight: 500; color: {T['sub']}">{{{{curName}}}}</h2>
<div style="display: flex; flex-wrap: wrap; gap: 8px"><sc-for list="{{{{chips}}}}" as="ty" hint-placeholder-count="6"><a href="{{{{ty.href}}}}" style="box-sizing: border-box; height: 40px; padding: 0 14px; border-radius: 20px; display: flex; align-items: center; gap: 7px; white-space: nowrap; font-size: 14px; background: {T['card']}; color: {T['text']}"><span>{{{{ty.name}}}}</span><span style="font-size: 14px; {NUM} color: {T['sub']}">{{{{ty.count}}}}</span></a></sc-for>
<button type="button" onClick="{{{{addChip}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; height: 40px; padding: 0 14px; border-radius: 20px; display: flex; align-items: center; gap: 6px; white-space: nowrap; font-size: 14px; border: 1.5px dashed {T['sub']}; color: {T['sub']}">{ICON_PLUS}추가</button></div>
</section>
</div>
</div>
{nav(T, theme)}
{sheet(T, False, True)}{discard_dialog(T)}
</div>
"""
    script = f"""class Component extends DCLogic {{
constructor(props) {{ super(props); this.state = Object.assign({{ sel: '디지털·IT', sheet: null, newTop: '디지털·IT', dirty: false, discard: false }}, {init}); }}
renderVals() {{
const s = this.state;
const home = {json.dumps(data, ensure_ascii=False)};
const tops = {json.dumps(TOPS, ensure_ascii=False)};
const cur = home.find((t) => t.name === s.sel) || home[0];
return {{
rail: home.map((t) => ({{ label: t.label, on: t.name === cur.name, off: t.name !== cur.name, pick: () => this.setState({{ sel: t.name }}) }})),
curName: cur.name, chips: cur.types,
addChip: () => this.setState({{ sheet: 'create', newTop: cur.name, dirty: false }}),
isEdit: false, isCreate: s.sheet === 'create', sheetOpen: !!s.sheet,
closeSheet: () => s.dirty ? this.setState({{ discard: true }}) : this.setState({{ sheet: null }}),
markDirty: () => {{ if (!s.dirty) this.setState({{ dirty: true }}); }},
isDiscard: s.discard, keepEditing: () => this.setState({{ discard: false }}), discardAll: () => this.setState({{ discard: false, sheet: null, dirty: false }}),
save: () => this.setState({{ sheet: null, dirty: false }}),
tops: tops.map((n) => ({{ name: n, on: s.newTop === n, off: s.newTop !== n, pick: () => this.setState({{ newTop: n }}) }}))
}};
}}
}}"""
    return head(title, T) + body + tail(script)


# ---------------------------------------------------------------- 세부 유형 상품 목록

def product_js(T, rows):
    out = []
    for name, price, ratio, kind, purpose, meta, pending in rows:
        bg, st, fl = PHOTO[kind]
        face, ring = PURPOSE[purpose] if purpose else ("", "")
        out.append({
            "name": name, "price": price, "ratio": f"aspect-ratio: {ratio}; background: {bg}",
            "s": st, "f": fl, "pending": pending,
            "hasDot": bool(purpose),
            "dotStyle": f"background: {face};" + (f" border: 1px solid {ring};" if (purpose and T["ring"]) else ""),
            "meta": (purpose + " · " if purpose else ("목적 미지정 · " if purpose is None and meta.endswith("확인") and "·" not in meta else "")) + meta,
        })
    return out


def product_col(T, key):
    return f"""<div style="flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 20px"><sc-for list="{{{{{key}}}}}" as="p" hint-placeholder-count="3"><a href="FProductDetail{T['s']}.dc.html" style="display: flex; flex-direction: column; gap: 8px">
<div style="{{{{p.ratio}}}}; position: relative; box-sizing: border-box; border-radius: 20px; display: flex; align-items: center; justify-content: center; overflow: hidden"><svg aria-hidden="true" width="56%" height="56%" viewBox="0 0 48 48" fill="none" stroke="{{{{p.s}}}}" stroke-width="3" stroke-linecap="round"><path d="M10 30v-6a14 14 0 0 1 28 0v6"></path><rect x="7" y="28" width="8" height="12" rx="3" fill="{{{{p.f}}}}"></rect><rect x="33" y="28" width="8" height="12" rx="3" fill="{{{{p.f}}}}"></rect></svg>
<sc-if value="{{{{p.pending}}}}" hint-placeholder-val="{{{{false}}}}"><span aria-label="분류·목적 미확정" style="position: absolute; left: 8px; top: 8px; width: 32px; height: 32px; border-radius: 10px; background: #FFFFFF; color: #1D1D1D; display: flex; align-items: center; justify-content: center"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 4v16M4 12h16M6.3 6.3l11.4 11.4M17.7 6.3L6.3 17.7"></path></svg></span></sc-if></div>
<span style="display: flex; flex-direction: column; gap: 4px; padding: 0 2px">
<span style="font-size: 14px; line-height: 1.35; word-break: keep-all">{{{{p.name}}}}</span>
<span style="font-size: 18px; {NUM}">{{{{p.price}}}}</span>
<span style="display: flex; align-items: center; gap: 6px; min-width: 0"><sc-if value="{{{{p.hasDot}}}}" hint-placeholder-val="{{{{true}}}}"><span aria-hidden="true" style="width: 10px; height: 10px; flex-shrink: 0; box-sizing: border-box; border-radius: 5px; {{{{p.dotStyle}}}}"></span></sc-if><span style="{lbl(12, T['sub'], extra=' overflow: hidden; text-overflow: ellipsis;')}">{{{{p.meta}}}}</span></span>
</span></a></sc-for></div>"""


def list_header(T, theme, name, count, sub, more):
    more_btn = (f'<button type="button" onClick="{{{{toggleMenu}}}}" aria-label="카테고리 편집·삭제" style="border: none; width: 44px; height: 44px; flex-shrink: 0; border-radius: 22px; background: {T["card"]}; color: {T["text"]}; display: flex; align-items: center; justify-content: center">{ICON_MORE}</button>'
                if more else "")
    return f"""<div style="height: 44px"></div>
<header style="position: sticky; top: 0; z-index: 1; background: {T['bg']}; padding: 12px 20px 14px; display: flex; align-items: center; gap: 12px"><a href="FCategoryHome{theme}.dc.html" aria-label="뒤로" style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 22px; background: {T['card']}; display: flex; align-items: center; justify-content: center">{ICON_BACK}</a>
<div style="flex-grow: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px"><h1 style="margin: 0; display: flex; align-items: baseline; gap: 8px; font-size: 22px; font-weight: 700; word-break: keep-all">{name}<span style="font-size: 20px; {NUM} color: {T['sub']}">{count}</span></h1><span style="{lbl(13, T['sub'])}">{sub}</span></div>{more_btn}</header>"""


def category_list(theme):
    T = THEMES[theme]
    items = product_js(T, HEADPHONES)
    body = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: {T['bg']}; color: {T['text']}; font-family: 'IBM Plex Sans KR', sans-serif">
<div style="position: absolute; inset: 0; overflow-y: auto">
{list_header(T, theme, "헤드폰", 8, "디지털·IT", False)}
<div style="padding: 8px 16px 140px; display: flex; gap: 12px; align-items: flex-start">{product_col(T, "left")}{product_col(T, "right")}</div>
</div>
{nav(T, theme)}
</div>
"""
    script = f"""class Component extends DCLogic {{
renderVals() {{
const items = {json.dumps(items, ensure_ascii=False)};
return {{ left: items.filter((_, i) => i % 2 === 0), right: items.filter((_, i) => i % 2 === 1) }};
}}
}}"""
    return head(f"{T['name']} 카테고리 세부 유형 목록", T) + body + tail(script)


def category_custom(theme, key, title, init):
    T = THEMES[theme]
    c = MINE[key]
    rows = [(n, p, r, k, None, m, False) for n, p, r, k, m in c["items"]]
    items = product_js(T, rows)
    for it in items:
        it["meta"] = it["meta"].replace("목적 미지정 · ", "")
    del_items = [{"name": n, "bg": PHOTO[k][0], "s": PHOTO[k][1], "f": PHOTO[k][2]} for n, _, _, k, _ in c["items"]]
    bullets = (["상품 %d개는 홈의 ‘정보 보완 필요’로 옮겨져요" % c["count"], "거기서 카테고리를 다시 고를 수 있어요"] if c["count"] else []) + ["아카이브 기록은 바뀌지 않아요", "되돌릴 수 없어요"]
    josa = "을" if key == "dac" else "를"
    red = DANGER["A"][theme]
    del_body = f"""<h2 style="margin: 0; font-size: 20px; font-weight: 700; word-break: keep-all">‘{c['name']}’{josa} 삭제할까요?</h2>
<ul style="margin: 0; padding-left: 20px; font-size: 15px; line-height: 1.6; word-break: keep-all; display: flex; flex-direction: column; gap: 2px">{''.join('<li>%s</li>' % b for b in bullets)}</ul>
<sc-if value="{{{{hasItems}}}}" hint-placeholder-val="{{{{false}}}}"><div style="border-radius: 20px; background: {T['tile']}; overflow: hidden"><button type="button" onClick="{{{{toggleDelList}}}}" aria-expanded="{{{{delList}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 48px; padding: 0 14px 0 16px; display: flex; align-items: center; justify-content: space-between; font-size: 15px">옮겨지는 상품 {c['count']}개<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" style="{{{{delArrow}}}}"><path d="M6 9l6 6 6-6"></path></svg></button>
<sc-if value="{{{{delList}}}}" hint-placeholder-val="{{{{false}}}}"><div style="max-height: 200px; overflow-y: auto; padding: 2px 16px 14px; display: flex; flex-direction: column; gap: 10px"><sc-for list="{{{{delItems}}}}" as="d" hint-placeholder-count="2"><div style="display: flex; align-items: center; gap: 12px; font-size: 14px"><span style="width: 36px; height: 36px; flex-shrink: 0; box-sizing: border-box; padding: 4px; border-radius: 10px; background: {{{{d.bg}}}}; display: flex; align-items: center; justify-content: center"><svg aria-hidden="true" width="100%" height="100%" viewBox="0 0 48 48" fill="none" stroke="{{{{d.s}}}}" stroke-width="3" stroke-linecap="round"><path d="M10 30v-6a14 14 0 0 1 28 0v6"></path><rect x="7" y="28" width="8" height="12" rx="3" fill="{{{{d.f}}}}"></rect><rect x="33" y="28" width="8" height="12" rx="3" fill="{{{{d.f}}}}"></rect></svg></span>{{{{d.name}}}}</div></sc-for></div></sc-if></div></sc-if>"""
    del_ok = f'<a href="FCategoryHome{theme}.dc.html" style="flex: 1 1 0; height: 52px; border-radius: 26px; background: {red}; color: #FFFFFF; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 700">삭제</a>'
    menu_item = lambda on, icon, label: f'<button type="button" onClick="{{{{{on}}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 52px; padding: 0 18px; display: flex; align-items: center; gap: 12px; font-size: 15px">{icon}{label}</button>'
    menu = f"""<sc-if value="{{{{menuOpen}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{closeMenu}}}}" aria-label="메뉴 닫기" style="all: unset; position: absolute; inset: 0; z-index: 8"></button>
<div role="menu" style="position: absolute; right: 20px; top: 112px; z-index: 9; width: 200px; box-sizing: border-box; padding: 6px 0; border-radius: 20px; border: 1px solid {T['line']}; background: {T['sheet']}; display: flex; flex-direction: column">{menu_item("openEdit", ICON_PEN.replace('width="20" height="20"', 'width="18" height="18"'), "편집")}{menu_item("openDelete", ICON_TRASH, "삭제")}</div></sc-if>"""
    empty = f"""<div style="flex-grow: 1; box-sizing: border-box; padding: 0 32px 112px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; text-align: center"><span style="width: 56px; height: 56px; border-radius: 20px; background: {T['card']}; display: flex; align-items: center; justify-content: center"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="4" width="7" height="7" rx="2"></rect><rect x="13" y="4" width="7" height="7" rx="2"></rect><rect x="4" y="13" width="7" height="7" rx="2"></rect><rect x="13" y="13" width="7" height="7" rx="2"></rect></svg></span><h2 style="margin: 0; font-size: 18px; font-weight: 700">아직 상품이 없어요</h2><p style="margin: 0; font-size: 14px; line-height: 1.5; word-break: keep-all; color: {T['sub']}">상품 편집에서 이 카테고리를 고르면 여기에 모여요.</p></div>"""
    body = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: {T['bg']}; color: {T['text']}; font-family: 'IBM Plex Sans KR', sans-serif">
<div style="position: absolute; inset: 0; overflow-y: auto"><div style="min-height: 100%; display: flex; flex-direction: column">
{list_header(T, theme, c['name'], c['count'], c['top'] + " · 직접 만든 카테고리", True)}
<sc-if value="{{{{hasList}}}}" hint-placeholder-val="{{{{true}}}}"><div style="padding: 8px 16px 140px; display: flex; gap: 12px; align-items: flex-start">{product_col(T, "left")}{product_col(T, "right")}</div></sc-if>
<sc-if value="{{{{emptyList}}}}" hint-placeholder-val="{{{{false}}}}">{empty}</sc-if>
</div></div>
{nav(T, theme)}
{menu}{sheet(T, True, False)}{dialog(T, "isDelete", "카테고리 삭제 확인", del_body, "취소", "closeDelete", del_ok)}{discard_dialog(T)}
</div>
"""
    script = f"""class Component extends DCLogic {{
constructor(props) {{ super(props); this.state = Object.assign({{ menu: false, sheet: null, del: false, delList: false, dirty: false, discard: false }}, {init}); }}
renderVals() {{
const s = this.state;
const c = {json.dumps({k: c[k] for k in ("name", "top", "count", "desc", "examples")}, ensure_ascii=False)};
const items = {json.dumps(items, ensure_ascii=False)};
return {{
left: items.filter((_, i) => i % 2 === 0), right: items.filter((_, i) => i % 2 === 1), hasList: items.length > 0, emptyList: items.length === 0,
menuOpen: s.menu, toggleMenu: () => this.setState({{ menu: !s.menu }}), closeMenu: () => this.setState({{ menu: false }}),
openEdit: () => this.setState({{ menu: false, sheet: 'edit', dirty: false }}),
isEdit: s.sheet === 'edit', isCreate: false, sheetOpen: !!s.sheet,
closeSheet: () => s.dirty ? this.setState({{ discard: true }}) : this.setState({{ sheet: null }}),
markDirty: () => {{ if (!s.dirty) this.setState({{ dirty: true }}); }},
isDiscard: s.discard, keepEditing: () => this.setState({{ discard: false }}), discardAll: () => this.setState({{ discard: false, sheet: null, dirty: false }}),
save: () => this.setState({{ sheet: null, dirty: false }}),
cName: c.name, cTop: c.top, cDesc: c.desc, cCount: c.count, hasItems: c.count > 0,
cExamples: c.examples.map((e) => ({{ name: e }})), exCount: c.examples.length + ' / 5',
renameNote: c.count ? '이름을 바꾸면 상품 ' + c.count + '개에 바로 반영돼요' : '',
openDelete: () => this.setState({{ menu: false, sheet: null, del: true, delList: false }}),
isDelete: s.del, closeDelete: () => this.setState({{ del: false }}),
delList: s.delList, toggleDelList: () => this.setState({{ delList: !s.delList }}),
delArrow: 'transition: transform 200ms ease; transform: rotate(' + (s.delList ? 180 : 0) + 'deg)',
delItems: {json.dumps(del_items, ensure_ascii=False)}
}};
}}
}}"""
    return head(title, T) + body + tail(script)


BOARDS = [
    ("FCategoryHome", "카테고리 · 첫 화면"),
    ("FCategoryAddSheet", "카테고리 · + 추가 시트"),
    ("FCategoryList", "카테고리 · 세부 유형 목록"),
    ("FCategoryListCustom", "카테고리 · 직접 만든 카테고리 목록 (⋯ 편집·삭제)"),
    ("FCategoryListCustomEmpty", "카테고리 · 직접 만든 카테고리 (상품 없음)"),
    ("FCategoryEditSheet", "카테고리 · 편집 시트"),
    ("FCategoryDeleteConfirm", "카테고리 · 삭제 확인"),
]


def main():
    out = os.path.join(sys.argv[1], "project")
    os.makedirs(out, exist_ok=True)
    boards = {}
    for th in "LD":
        nm = THEMES[th]["name"]
        boards[f"FCategoryHome{th}"] = category_home(th, f"{nm} 카테고리 첫 화면", "{}")
        boards[f"FCategoryAddSheet{th}"] = category_home(th, f"{nm} 세부 카테고리 추가", "{ sheet: 'create' }")
        boards[f"FCategoryList{th}"] = category_list(th)
        boards[f"FCategoryListCustom{th}"] = category_custom(th, "dac", f"{nm} 직접 만든 카테고리 목록", "{}")
        boards[f"FCategoryListCustomEmpty{th}"] = category_custom(th, "lego", f"{nm} 빈 직접 만든 카테고리 목록", "{}")
        boards[f"FCategoryEditSheet{th}"] = category_custom(th, "dac", f"{nm} 직접 만든 카테고리 편집", "{ sheet: 'edit' }")
        boards[f"FCategoryDeleteConfirm{th}"] = category_custom(th, "dac", f"{nm} 직접 만든 카테고리 삭제 확인", "{ del: true, delList: true }")
    for name, html in boards.items():
        with open(os.path.join(out, name + ".dc.html"), "w") as f:
            f.write(html)
    print(" ".join(sorted(boards)))


if __name__ == "__main__":
    main()
