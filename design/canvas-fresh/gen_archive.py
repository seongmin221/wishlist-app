"""전체 화면 · 아카이브(끝난 비교) 묶음 보드를 만든다.

python3 gen_archive.py <out_root>
템플릿 안의 [[키]]는 테마 값으로, {{키}}는 보드 런타임 값으로 채워진다.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen_full_home import THEMES, PHOTO, head, tail, photo_svg
from gen_category import nav
from gen_purpose import fill, ICONS, icon_switch, thumb

RECORDS = {
    "padding": dict(title="겨울 패딩", ended="9월 20일 종료", icon="i_star", count=4,
                    items=[("노스페이스 1996 눕시", "아우터", "1 / 1", "brown", True), ("아크테릭스 세륨 후디", "아우터", "3 / 4", "green", False),
                           ("파타고니아 다운 스웨터", "아우터", "4 / 5", "beige", False), ("몽벨 플라즈마 1000", "아우터", "1 / 1", "white", False)]),
    "keyboard": dict(title="기계식 키보드", ended="8월 30일 종료", icon="i_book", count=3,
                     items=[("키크론 Q1 Pro", "키보드", "1 / 1", "beige", False), ("레오폴드 FC900R", "키보드", "4 / 5", "white", False),
                            ("리얼포스 R3", "키보드", "3 / 4", "green", False)]),
}

FACE = {"L": "#E8E8E8", "D": "#3A3939"}  # 아카이브 상세의 위 면은 무채색


def list_board(theme):
    T = THEMES[theme]
    cards = []
    for key, href in (("padding", f"FArchiveDetail{theme}.dc.html"), ("keyboard", f"FArchiveDetailNoPurchase{theme}.dc.html")):
        r = RECORDS[key]
        kinds = [it[3] for it in r["items"]][:3]
        stack = "".join(f'<div style="width: 44px; height: 44px; box-sizing: border-box; border-radius: 14px; overflow: hidden; border: 2px solid [[card]]; margin-left: {0 if i == 0 else -16}px"><div style="width: 40px; height: 40px; box-sizing: border-box; padding: 5px; background: {PHOTO[k][0]}; display: flex; align-items: center; justify-content: center">{photo_svg(k, "100%")}</div></div>' for i, k in enumerate(kinds))
        bought = [it for it in r["items"] if it[4]]
        buy = ""
        if bought:
            n, _, _, k, _ = bought[0]
            buy = (f'<div style="display: flex; align-items: center; gap: 10px; padding: 8px 12px 8px 8px; border-radius: 14px; background: [[tile]]; font-size: 13px">'
                   f'<span style="width: 28px; height: 28px; flex-shrink: 0; box-sizing: border-box; padding: 3px; border-radius: 8px; background: {PHOTO[k][0]}; display: flex">{photo_svg(k, "100%")}</span>'
                   f'<span style="min-width: 0; word-break: keep-all"><span style="color: [[sub]]">구매</span> {n}</span></div>')
        hi = {f"k_{k}": k == r["icon"] for k in ICONS}
        cards.append(f"""<a href="{href}" style="box-sizing: border-box; padding: 16px; border-radius: 28px; background: [[card]]; display: flex; flex-direction: column; gap: 12px">
<div style="display: flex; align-items: center; gap: 14px">
<div style="display: flex; flex-shrink: 0">{stack}</div>
<div style="flex-grow: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px"><span style="font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 20px; line-height: 1.0">{r['title']}</span><span style="[[lbl12:sub]]">후보 {r['count']} · {r['ended']}</span></div>[[RIGHT]]
</div>{buy}</a>""")
    tpl = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: [[bg]]; color: [[text]]; font-family: 'IBM Plex Sans KR', sans-serif">
<div style="position: absolute; inset: 0; overflow-y: auto">
<div style="height: 44px"></div>
<header style="position: sticky; top: 0; z-index: 1; background: [[bg]]; padding: 12px 20px 14px; display: flex; align-items: center; gap: 12px"><a href="FPurposeHome[[s]].dc.html" aria-label="뒤로" style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 22px; background: [[card]]; display: flex; align-items: center; justify-content: center">[[BACK]]</a>
<h1 style="margin: 0; display: flex; align-items: baseline; gap: 8px; font-size: 22px; font-weight: 700">끝난 비교<span style="font-size: 20px; [[NUM]] color: [[sub]]">2</span></h1></header>
<div style="padding: 8px 20px 140px; display: flex; flex-direction: column; gap: 12px">
{''.join(cards)}
</div>
</div>
{nav(T, theme, 'purpose')}
</div>
"""
    return head(f"{T['name']} 끝난 비교 목록", T) + fill(tpl, T, theme) + tail("class Component extends DCLogic {\nrenderVals() { return {}; }\n}")


def detail_board(theme, key, title, init):
    T = THEMES[theme]
    r = RECORDS[key]
    face = FACE[theme]
    items = []
    for n, cat, ratio, k, b in sorted(r["items"], key=lambda x: not x[4]):
        bg, st, fl = PHOTO[k]
        items.append({"name": n, "cat": cat, "ratio": f"aspect-ratio: {ratio}; background: {bg}", "s": st, "f": fl, "bg": bg, "bought": b})
    hi = {f"k_{k}": k == r["icon"] for k in ICONS}
    btn = "border: none; width: 44px; height: 44px; flex-shrink: 0; border-radius: 22px; background: [[card]]; color: [[text]]; display: flex; align-items: center; justify-content: center"
    pill = "height: 44px; border-radius: 22px; border: none; display: flex; align-items: center; gap: 6px; font-size: 15px"
    # 목적 상세와 같은 밑줄 편집 칸. 한글 아래 끝에서 밑줄까지 3px
    name_box = "box-sizing: border-box; width: 100%; height: 28px; margin: 0; padding: 3px 0 0; border: none; border-bottom: 1px solid transparent; border-radius: 0; background: transparent; font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 28px; line-height: 24px; color: [[text]]; outline: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis"
    card = """<div style="display: flex; flex-direction: column; gap: 8px">
<div style="{{p.ratio}}; position: relative; border-radius: 20px; display: flex; align-items: center; justify-content: center; overflow: hidden"><svg aria-hidden="true" width="56%" height="56%" viewBox="0 0 48 48" fill="none" stroke="{{p.s}}" stroke-width="3" stroke-linecap="round"><path d="M10 30v-6a14 14 0 0 1 28 0v6"></path><rect x="7" y="28" width="8" height="12" rx="3" fill="{{p.f}}"></rect><rect x="33" y="28" width="8" height="12" rx="3" fill="{{p.f}}"></rect></svg>
<sc-if value="{{p.bought}}" hint-placeholder-val="{{false}}"><span style="position: absolute; left: 8px; top: 8px; height: 28px; padding: 0 10px 0 8px; border-radius: 14px; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; gap: 4px; font-size: 12px; font-weight: 700">[[CHECK]]구매</span></sc-if></div>
<span style="display: flex; flex-direction: column; gap: 4px; padding: 0 2px"><span style="font-size: 14px; line-height: 1.35; word-break: keep-all">{{p.name}}</span><span style="[[lbl12:sub]]">{{p.cat}}</span></span></div>"""
    col = lambda k, n: f'<div style="flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 20px"><sc-for list="{{{{{k}}}}}" as="p" hint-placeholder-count="{n}">{card}</sc-for></div>'
    lis = lambda xs: "".join(f"<li>{x}</li>" for x in xs)
    has_buy = any(it["bought"] for it in items)
    restore_items = [f"목적과 후보 {r['count']}개가 비교 중으로 돌아가요"] + (["구매 상품 지정이 지워져요"] if has_buy else []) + ["이 기록은 끝난 비교에서 사라져요"]
    del_list = "".join(f'<div style="display: flex; align-items: center; gap: 12px; font-size: 14px"><span style="width: 36px; height: 36px; flex-shrink: 0; box-sizing: border-box; padding: 4px; border-radius: 10px; background: {it["bg"]}; display: flex">{photo_svg_k(it)}</span>{it["name"]}</div>' for it in items)
    dialog = lambda k, label, body, ok: f"""<sc-if value="{{{{{k}}}}}" hint-placeholder-val="{{{{false}}}}"><div style="position: absolute; inset: 0; z-index: 12; [[scrimDiv]]"></div>
<div role="dialog" aria-label="{label}" style="position: absolute; left: 24px; right: 24px; top: 50%; transform: translateY(-50%); z-index: 13; box-sizing: border-box; padding: 24px 20px 20px; border-radius: 36px; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column; gap: 12px">
{body}
<div style="display: flex; gap: 10px; margin-top: 4px">{ok}</div>
</div></sc-if>"""
    cancel_btn = lambda on, label="취소": f'<button type="button" onClick="{{{{{on}}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: [[tile]]; color: [[text]]; font-size: 16px; font-weight: 500">{label}</button>'
    restore = dialog("isRestore", "비교를 다시 열까요?",
                     f'<h2 style="margin: 0; font-size: 20px; font-weight: 700">비교를 다시 열까요?</h2><ul style="margin: 0; padding-left: 20px; font-size: 15px; line-height: 1.6; word-break: keep-all">{lis(restore_items)}</ul>',
                     cancel_btn("closeRestore") + '<a href="FPurposeHome[[s]].dc.html" style="flex: 1 1 0; height: 52px; border-radius: 26px; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 700">다시 열기</a>')
    delete = dialog("isDelete", "기록을 삭제할까요?",
                    f"""<h2 style="margin: 0; font-size: 20px; font-weight: 700">기록을 삭제할까요?</h2><ul style="margin: 0; padding-left: 20px; font-size: 15px; line-height: 1.6">{lis(["기록 전체가 삭제돼요", "되돌릴 수 없어요"])}</ul>
<div style="border-radius: 20px; background: [[tile]]; overflow: hidden"><button type="button" onClick="{{{{toggleDelList}}}}" aria-expanded="{{{{delList}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 48px; padding: 0 14px 0 16px; display: flex; align-items: center; justify-content: space-between; font-size: 15px">함께 삭제되는 후보 기록 {r['count']}개<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" style="{{{{delArrow}}}}"><path d="M6 9l6 6 6-6"></path></svg></button>
<sc-if value="{{{{delList}}}}" hint-placeholder-val="{{{{false}}}}"><div style="max-height: 200px; overflow-y: auto; padding: 2px 16px 14px; display: flex; flex-direction: column; gap: 10px">{del_list}</div></sc-if></div>""",
                    cancel_btn("closeDelete") + '<a href="FArchiveList[[s]].dc.html" style="flex: 1 1 0; height: 52px; border-radius: 26px; background: [[red]]; color: #FFFFFF; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 700">삭제</a>')
    discard = dialog("isDiscard", "변경 사항을 버릴까요?",
                     '<h2 style="margin: 0; font-size: 20px; font-weight: 700">변경 사항을 버릴까요?</h2><ul style="margin: 0; padding-left: 20px; font-size: 15px; line-height: 1.6"><li>편집한 내용이 저장되지 않아요</li></ul>',
                     cancel_btn("keepEditing", "계속 편집") + '<button type="button" onClick="{{discardAll}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: [[inv]]; color: [[inv_text]]; font-size: 16px; font-weight: 700">버리기</button>')
    restore_icon = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M9 14L4 9l5-5"></path><path d="M4 9h11a5 5 0 0 1 0 10h-3"></path></svg>'
    mi = lambda on, icon, label: f'<button type="button" onClick="{{{{{on}}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 52px; padding: 0 18px; display: flex; align-items: center; gap: 12px; font-size: 15px">{icon}{label}</button>'
    tpl = f"""<div data-root="1" style="width: 390px; height: 844px; position: relative; overflow: hidden; background: [[bg]]; color: [[text]]; font-family: 'IBM Plex Sans KR', sans-serif">
<div data-scroll="1" onScroll="{{{{onScroll}}}}" style="position: absolute; inset: 0; overflow-y: auto; background: {face}">
<section style="padding: 52px 20px 28px; display: flex; flex-direction: column; gap: 16px">
<div style="display: flex; justify-content: space-between; min-height: 44px">
<sc-if value="{{{{view}}}}" hint-placeholder-val="{{{{true}}}}"><a href="FArchiveList[[s]].dc.html" aria-label="뒤로" style="{btn}">[[BACK]]</a><button type="button" onClick="{{{{toggleMenu}}}}" aria-label="더보기" style="{btn}">[[MORE]]</button></sc-if>
<sc-if value="{{{{edit}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{cancel}}}}" aria-label="뒤로" style="{btn}">[[BACK]]</button></sc-if>
</div>
<div style="display: flex; flex-direction: column; gap: 10px">
<span style="[[lbl13:sub]]">끝난 비교 · {r['ended']}</span>
<div style="display: flex; align-items: center; gap: 12px">
<span style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 14px; background: [[card]]; color: [[text]]; display: flex; align-items: center; justify-content: center">{icon_switch("hi", 22)}</span>
<sc-if value="{{{{view}}}}" hint-placeholder-val="{{{{true}}}}"><h1 style="flex-grow: 1; min-width: 0; {name_box}">{r['title']}</h1></sc-if>
<sc-if value="{{{{edit}}}}" hint-placeholder-val="{{{{false}}}}"><label style="flex-grow: 1; min-width: 0; display: block"><span style="position: absolute; left: -9999px">기록 제목</span><input type="text" class="ed" defaultValue="{r['title']}" onInput="{{{{markDirty}}}}" style="{name_box}; border-bottom-color: [[dash]]"></label></sc-if>
</div>
<div aria-hidden="{{{{view}}}}" style="display: flex; flex-direction: column; gap: 10px; overflow: hidden; transition: max-height 260ms ease, opacity 200ms ease, margin-top 260ms ease; {{{{extraStyle}}}}"><p style="margin: 0; font-size: 13px; line-height: 1.5; word-break: keep-all; color: [[sub]]">제목만 바뀌어요. 다른 목적의 이름은 바뀌지 않아요.</p>
<div style="display: flex; gap: 8px"><button type="button" onClick="{{{{cancel}}}}" style="{pill}; padding: 0 22px; background: [[card]]; color: [[text]]; font-weight: 500">취소</button><button type="button" onClick="{{{{save}}}}" style="{pill}; padding: 0 24px; background: [[inv]]; color: [[inv_text]]; font-weight: 700">저장</button></div></div>
</div>
</section>
<section data-sheet="1" style="min-height: 740px; box-sizing: border-box; border-radius: 36px 36px 0 0; background: [[sheet]]; color: [[text]]; padding: 0 16px 140px; display: flex; flex-direction: column; gap: 12px">
<button type="button" onClick="{{{{toggleCollapse}}}}" aria-label="{{{{handleLabel}}}}" style="all: unset; cursor: grab; align-self: stretch; height: 28px; display: flex; align-items: center; justify-content: center"><span style="width: 40px; height: 5px; border-radius: 3px; background: [[handle]]"></span></button>
<h2 style="margin: 0; padding: 0 4px; display: flex; align-items: baseline; gap: 6px; font-size: 15px; font-weight: 700">비교한 후보<span style="font-size: 18px; [[NUM]]">{r['count']}</span></h2>
<div style="display: flex; gap: 12px; align-items: flex-start">{col("left", 2)}{col("right", 2)}</div>
<p style="margin: 8px 4px 0; font-size: 13px; line-height: 1.5; word-break: keep-all; color: [[sub]]">종료 당시의 목적 이름과 카테고리로 보관돼요. 이후 카테고리나 목적을 바꿔도 이 기록은 바뀌지 않아요.</p>
</section>
</div>
<div aria-label="접힌 헤더" style="position: absolute; left: 0; right: 0; top: 0; z-index: 4; box-sizing: border-box; padding: 52px 20px 12px; display: flex; align-items: center; gap: 10px; color: [[text]]; {{{{barStyle}}}}">
<a href="FArchiveList[[s]].dc.html" aria-label="뒤로" style="{btn}">[[BACK]]</a>
<span style="flex-grow: 1; min-width: 0; display: flex; align-items: center; gap: 8px"><span style="width: 36px; height: 36px; flex-shrink: 0; border-radius: 12px; background: [[card]]; display: flex; align-items: center; justify-content: center">{icon_switch("hi", 18)}</span><span style="min-width: 0; font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 20px; line-height: 1.0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{r['title']}</span></span>
<button type="button" onClick="{{{{toggleMenu}}}}" aria-label="더보기" style="{btn}">[[MORE]]</button>
<span aria-hidden="true" style="position: absolute; left: 0; right: 0; top: 100%; height: 24px; background: {face}"><span style="position: absolute; inset: 0; border-radius: 36px 36px 0 0; background: [[sheet]]"></span></span>
</div>
{nav(T, theme, 'purpose')}
<sc-if value="{{{{menuOpen}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{closeMenu}}}}" aria-label="메뉴 닫기" style="all: unset; position: absolute; inset: 0; z-index: 8"></button>
<div role="menu" style="position: absolute; right: 20px; top: 104px; z-index: 9; width: 200px; box-sizing: border-box; padding: 6px 0; border-radius: 20px; border: 1px solid [[line]]; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column">{mi("startEdit", "[[PEN]]", "제목 수정")}{mi("openRestore", restore_icon, "비교 다시 열기")}{mi("openDelete", "[[TRASH]]", "기록 삭제")}</div></sc-if>
{restore}{delete}{discard}
</div>
"""
    script = f"""class Component extends DCLogic {{
constructor(props) {{ super(props); this.state = Object.assign({{ menu: false, edit: false, dirty: false, discard: false, restore: false, del: false, delList: false }}, {init}); }}
renderVals() {{
const s = this.state;
const c = !!s.collapsed;
const items = {json.dumps(items, ensure_ascii=False)};
return {{
left: items.filter((_, i) => i % 2 === 0), right: items.filter((_, i) => i % 2 === 1),
hi: {json.dumps(hi)},
view: !s.edit, edit: s.edit,
extraStyle: s.edit ? 'max-height: 120px; opacity: 1; margin-top: 0' : 'max-height: 0; opacity: 0; margin-top: -10px; pointer-events: none',
menuOpen: s.menu, toggleMenu: () => this.setState({{ menu: !s.menu }}), closeMenu: () => this.setState({{ menu: false }}),
startEdit: (e) => {{ const root = e.currentTarget.closest('[data-root]'); const sc = root && root.querySelector('[data-scroll]'); if (sc && sc.scrollTop > 0) {{ this._snap = true; sc.scrollTo({{ top: 0, behavior: 'smooth' }}); clearTimeout(this._st); this._st = setTimeout(() => {{ this._snap = false; this._last = 0; }}, 450); }} this.setState({{ edit: true, menu: false, dirty: false, collapsed: false }}); }},
cancel: () => s.dirty ? this.setState({{ discard: true }}) : this.setState({{ edit: false }}),
save: () => this.setState({{ edit: false, dirty: false }}),
markDirty: () => {{ if (!s.dirty) this.setState({{ dirty: true }}); }},
isDiscard: s.discard, keepEditing: () => this.setState({{ discard: false }}), discardAll: () => this.setState({{ discard: false, edit: false, dirty: false }}),
isRestore: s.restore, openRestore: () => this.setState({{ restore: true, menu: false }}), closeRestore: () => this.setState({{ restore: false }}),
isDelete: s.del, openDelete: () => this.setState({{ del: true, menu: false, delList: false }}), closeDelete: () => this.setState({{ del: false }}),
delList: s.delList, toggleDelList: () => this.setState({{ delList: !s.delList }}), delArrow: 'transition: transform 200ms ease; transform: rotate(' + (s.delList ? 180 : 0) + 'deg)',
barStyle: 'background: {face}; transition: opacity 220ms ease, transform 220ms ease;' + (c ? 'opacity: 1; transform: none;' : 'opacity: 0; transform: translateY(-8px); pointer-events: none;'),
handleLabel: c ? '헤더 펼치기' : '목록 넓게 보기',
onScroll: (e) => {{
const el = e.currentTarget; const sheet = el.querySelector('[data-sheet]'); if (!sheet) return;
const stop = sheet.offsetTop - 108; const top = el.scrollTop; const last = this._last || 0; this._last = top;
const n = top >= stop - 24; if (n !== c) this.setState({{ collapsed: n }});
if (this._snap) return;
const snap = (y) => {{ this._snap = true; el.scrollTo({{ top: y, behavior: 'smooth' }}); clearTimeout(this._st); this._st = setTimeout(() => {{ this._snap = false; this._last = el.scrollTop; }}, 450); }};
if (top > last && top > 0 && top < stop) snap(stop);
else if (top < last && top < stop && top > 0) snap(0);
}},
toggleCollapse: (e) => {{ const sc = e.currentTarget.closest('[data-scroll]'); const sheet = sc && sc.querySelector('[data-sheet]'); if (!sc || !sheet) return; sc.scrollTo({{ top: c ? 0 : sheet.offsetTop - 108, behavior: 'smooth' }}); }}
}};
}}
}}"""
    return head(title, T) + fill(tpl, T, theme) + tail(script)


def photo_svg_k(it):
    return (f'<svg aria-hidden="true" width="100%" height="100%" viewBox="0 0 48 48" fill="none" stroke="{it["s"]}" stroke-width="3" stroke-linecap="round"><path d="M10 30v-6a14 14 0 0 1 28 0v6"></path>'
            f'<rect x="7" y="28" width="8" height="12" rx="3" fill="{it["f"]}"></rect><rect x="33" y="28" width="8" height="12" rx="3" fill="{it["f"]}"></rect></svg>')


BOARDS = [
    ("FArchiveList", "아카이브 · 끝난 비교 목록"),
    ("FArchiveDetail", "아카이브 · 상세 (구매 있음)"),
    ("FArchiveDetailNoPurchase", "아카이브 · 상세 (구매 없음)"),
    ("FArchiveEdit", "아카이브 · 제목 수정"),
    ("FArchiveRestoreConfirm", "아카이브 · 비교 다시 열기 확인"),
    ("FArchiveDeleteConfirm", "아카이브 · 기록 삭제 확인"),
]


def main():
    out = os.path.join(sys.argv[1], "project")
    os.makedirs(out, exist_ok=True)
    boards = {}
    for th in "LD":
        nm = THEMES[th]["name"]
        boards[f"FArchiveList{th}"] = list_board(th)
        boards[f"FArchiveDetail{th}"] = detail_board(th, "padding", f"{nm} 끝난 비교 상세", "{}")
        boards[f"FArchiveDetailNoPurchase{th}"] = detail_board(th, "keyboard", f"{nm} 끝난 비교 상세 구매 없음", "{}")
        boards[f"FArchiveEdit{th}"] = detail_board(th, "padding", f"{nm} 끝난 비교 제목 수정", "{ edit: true }")
        boards[f"FArchiveRestoreConfirm{th}"] = detail_board(th, "padding", f"{nm} 비교 다시 열기 확인", "{ restore: true }")
        boards[f"FArchiveDeleteConfirm{th}"] = detail_board(th, "padding", f"{nm} 기록 삭제 확인", "{ del: true, delList: true }")
    for name, html in boards.items():
        with open(os.path.join(out, name + ".dc.html"), "w") as f:
            f.write(html)
    print(" ".join(sorted(boards)))


if __name__ == "__main__":
    main()
