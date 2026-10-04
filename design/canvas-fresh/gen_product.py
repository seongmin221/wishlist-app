"""전체 화면 · 상품 묶음 보드를 만든다.

python3 gen_product.py <out_root>
템플릿 안의 [[키]]는 테마 값으로, {{키}}는 보드 런타임 값으로 채워진다.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen_full_home import THEMES, PHOTO, head, tail
from gen_purpose import fill, COLORS, HOME_PS, ICONS, icon_switch

with open(os.path.join(os.path.dirname(__file__), "taxonomy.json")) as f:
    TAX = json.load(f)

EXT = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"></path></svg>'
CAM = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 8h3l2-3h6l2 3h3v11H4z"></path><circle cx="12" cy="13" r="3.5"></circle></svg>'
PHOTO_ADD = '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><rect x="3" y="5" width="18" height="14" rx="3"></rect><circle cx="9" cy="11" r="2"></circle><path d="M21 16l-5-5-8 8"></path></svg>'
SPARK = '<svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1"></path></svg>'
HELP = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="12" r="9"></circle><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6v.6M12 17h.01"></path></svg>'
WARN = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 4l9 16H3z"></path><path d="M12 10v4M12 17h.01"></path></svg>'
REDO = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"></path></svg>'
CHEV = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M9 5l7 7-7 7"></path></svg>'


def purposes_js(T):
    out = []
    for name, _, _, ci, ic in HOME_PS:
        d = {"name": name, "face": COLORS[ci][1], "dotStyle": "background: " + COLORS[ci][1] + ";" + (" border: 1px solid " + COLORS[ci][2] + ";" if T["ring"] else "")}
        d.update({"k_" + k: k == ic for k in ICONS})
        out.append(d)
    return out


def top_bar(mode_fill=False):
    """사진 위에 떠 있는 뒤로·⋯ 버튼. 편집 중에도 왼쪽 위는 뒤로이고 취소처럼 동작한다."""
    btn = "position: absolute; top: 52px; z-index: 3; border: none; width: 44px; height: 44px; border-radius: 22px; background: [[card]]; color: [[text]]; display: flex; align-items: center; justify-content: center"
    return f"""<sc-if value="{{{{view}}}}" hint-placeholder-val="{{{{true}}}}"><a href="{{{{backHref}}}}" aria-label="뒤로" style="left: 16px; {btn}">[[BACK]]</a><button type="button" onClick="{{{{toggleMenu}}}}" aria-label="더보기" style="right: 16px; {btn}">[[MORE]]</button></sc-if>
<sc-if value="{{{{edit}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{cancel}}}}" aria-label="뒤로" style="left: 16px; {btn}">[[BACK]]</button></sc-if>"""


def menu(items):
    btn = lambda on, icon, label: f'<button type="button" onClick="{{{{{on}}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 52px; padding: 0 18px; display: flex; align-items: center; gap: 12px; font-size: 15px">{icon}{label}</button>'
    inner = "".join(btn(*i) for i in items)
    return f"""<sc-if value="{{{{menuOpen}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{closeMenu}}}}" aria-label="메뉴 닫기" style="all: unset; position: absolute; inset: 0; z-index: 8"></button>
<div role="menu" style="position: absolute; right: 16px; top: 104px; z-index: 9; width: 200px; box-sizing: border-box; padding: 6px 0; border-radius: 20px; border: 1px solid [[line]]; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column">{inner}</div></sc-if>"""


def bottom_bar():
    return """<div style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 4; padding: 12px 20px 36px; background: [[bg]]; display: flex; gap: 10px">
<sc-if value="{{view}}" hint-placeholder-val="{{true}}"><a href="#" style="flex: 1 1 0; height: 56px; border-radius: 28px; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; justify-content: center; gap: 8px; font-size: 16px; font-weight: 700">원본 보기[[EXT]]</a></sc-if>
<sc-if value="{{edit}}" hint-placeholder-val="{{false}}"><button type="button" onClick="{{cancel}}" style="flex: 1 1 0; height: 56px; border-radius: 28px; border: none; background: [[card]]; color: [[text]]; font-size: 16px; font-weight: 500">취소</button><button type="button" onClick="{{save}}" style="flex: 2 1 0; height: 56px; border-radius: 28px; border: none; background: [[inv]]; color: [[inv_text]]; font-size: 16px; font-weight: 700">저장</button></sc-if>
</div>"""


def dialog(key, label, body, ok_html, cancel_on="closeDelete", cancel_label="취소"):
    return f"""<sc-if value="{{{{{key}}}}}" hint-placeholder-val="{{{{false}}}}"><div style="position: absolute; inset: 0; z-index: 20; [[scrimDiv]]"></div>
<div role="dialog" aria-label="{label}" style="position: absolute; left: 24px; right: 24px; top: 50%; transform: translateY(-50%); z-index: 21; box-sizing: border-box; padding: 24px 20px 20px; border-radius: 36px; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column; gap: 12px">
{body}
<div style="display: flex; gap: 10px; margin-top: 4px"><button type="button" onClick="{{{{{cancel_on}}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: [[tile]]; color: [[text]]; font-size: 16px; font-weight: 500">{cancel_label}</button>{ok_html}</div>
</div></sc-if>"""


def discard_dialog():
    body = '<h2 style="margin: 0; font-size: 20px; font-weight: 700">변경 사항을 버릴까요?</h2><ul style="margin: 0; padding-left: 20px; font-size: 15px; line-height: 1.6"><li>편집한 내용이 저장되지 않아요</li></ul>'
    ok = '<button type="button" onClick="{{discardAll}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: [[inv]]; color: [[inv_text]]; font-size: 16px; font-weight: 700">버리기</button>'
    return dialog("isDiscard", "변경 사항을 버릴까요?", body, ok, "keepEditing", "계속 편집")


def delete_dialog(thumb_html, name, bullets, help_text, back_href):
    lis = "".join(f"<li>{b}</li>" for b in bullets)
    body = f"""<div style="display: flex; align-items: center; justify-content: space-between; gap: 8px"><h2 style="margin: 0; font-size: 20px; font-weight: 700">상품을 삭제할까요?</h2><button type="button" onClick="{{{{toggleHelp}}}}" aria-label="삭제 도움말" aria-expanded="{{{{help}}}}" style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 22px; border: none; background: [[tile]]; color: [[text]]; display: flex; align-items: center; justify-content: center">{HELP}</button></div>
<div style="display: flex; align-items: center; gap: 12px; padding: 10px; border-radius: 20px; background: [[tile]]">{thumb_html}<span style="font-size: 15px; font-weight: 700; word-break: keep-all">{name}</span></div>
<ul style="margin: 0; padding-left: 20px; font-size: 15px; line-height: 1.6; word-break: keep-all; display: flex; flex-direction: column; gap: 2px">{lis}</ul>
<sc-if value="{{{{help}}}}" hint-placeholder-val="{{{{false}}}}"><div style="padding: 12px 16px; border-radius: 20px; background: [[tile]]; font-size: 13px; line-height: 1.6; word-break: keep-all; color: [[sub]]">{help_text}</div></sc-if>"""
    ok = f'<a href="{back_href}" style="flex: 1 1 0; height: 52px; border-radius: 26px; background: [[red]]; color: #FFFFFF; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 700">삭제</a>'
    return dialog("isDelete", "상품 삭제 확인", body, ok)


def purpose_sheet():
    radio_on = '<span style="width: 24px; height: 24px; flex-shrink: 0; border-radius: 12px; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; justify-content: center">[[CHECK]]</span>'
    radio_off = '<span style="width: 24px; height: 24px; flex-shrink: 0; box-sizing: border-box; border-radius: 12px; border: 2px solid [[dash]]"></span>'
    return f"""<sc-if value="{{{{sheetOpen}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{closeSheet}}}}" aria-label="닫기" style="all: unset; position: absolute; inset: 0; z-index: 10; [[scrimDiv]]"></button>
<div style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 11; max-height: 760px; overflow-y: auto; box-sizing: border-box; padding: 10px 20px 36px; border-radius: 36px 36px 0 0; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column; gap: 12px">
<div style="align-self: center; width: 40px; height: 5px; border-radius: 3px; background: [[handle]]"></div>
<h2 style="margin: 4px 0 0; font-size: 20px; font-weight: 700">목적 선택</h2>
<div style="display: flex; flex-direction: column">
<sc-for list="{{{{options}}}}" as="o" hint-placeholder-count="7"><button type="button" onClick="{{{{o.pick}}}}" aria-pressed="{{{{o.on}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 56px; display: flex; align-items: center; gap: 12px; font-size: 15px">
<span style="width: 36px; height: 36px; flex-shrink: 0; border-radius: 12px; background: {{{{o.face}}}}; color: #1D1D1D; display: flex; align-items: center; justify-content: center">{icon_switch("o", 18)}</span><span style="flex-grow: 1; min-width: 0">{{{{o.name}}}}</span>
<sc-if value="{{{{o.on}}}}" hint-placeholder-val="{{{{false}}}}">{radio_on}</sc-if><sc-if value="{{{{o.off}}}}" hint-placeholder-val="{{{{true}}}}">{radio_off}</sc-if>
</button></sc-for>
</div>
<button type="button" style="height: 52px; border-radius: 26px; border: 1.5px dashed [[sub]]; background: transparent; color: [[text]]; display: flex; align-items: center; justify-content: center; gap: 6px; font-size: 15px; font-weight: 700">[[PLUS]]새 목적 만들기</button>
<button type="button" onClick="{{{{unlink}}}}" style="height: 52px; border-radius: 26px; border: none; background: [[tile]]; color: [[text]]; font-size: 15px; font-weight: 500">목적 연결 해제</button>
</div></sc-if>"""


def category_sheet():
    fld = "box-sizing: border-box; width: 100%; height: 56px; padding: 0 18px; border-radius: 20px; border: none; background: [[tile]]; font: inherit; font-size: 16px; color: inherit; outline: none"
    area = "box-sizing: border-box; width: 100%; padding: 16px 18px; border-radius: 20px; border: none; background: [[tile]]; font: inherit; font-size: 16px; line-height: 1.5; color: inherit; outline: none; resize: none"
    flabel = lambda l, r: f'<span style="display: flex; justify-content: space-between; font-size: 13px; color: [[sub]]"><span>{l}</span><span style="[[lbl12:sub]]">{r}</span></span>'
    chip = "all: unset; cursor: pointer; box-sizing: border-box; height: 40px; padding: 0 14px; border-radius: 20px; display: flex; align-items: center; gap: 6px; font-size: 14px; white-space: nowrap"
    rail_btn = lambda key, hint, look: f'<sc-if value="{{{{r.{key}}}}}" hint-placeholder-val="{{{{{hint}}}}}"><button type="button" onClick="{{{{r.pick}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; min-height: 52px; {look}; display: flex; align-items: center; font-size: 14px; line-height: 1.3; word-break: keep-all">{{{{r.label}}}}</button></sc-if>'
    return f"""<sc-if value="{{{{catOpen}}}}" hint-placeholder-val="{{{{false}}}}"><div style="position: absolute; inset: 0; z-index: 10; [[scrimDiv]]"></div>
<div style="position: absolute; left: 0; right: 0; top: 96px; bottom: 0; z-index: 11; border-radius: 36px 36px 0 0; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column">
<div style="align-self: center; width: 40px; height: 5px; margin-top: 10px; border-radius: 3px; background: [[handle]]"></div>
<div style="padding: 8px 12px 12px 20px; display: flex; align-items: center; gap: 8px">
<sc-if value="{{{{create}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{endCreate}}}}" aria-label="뒤로" style="width: 44px; height: 44px; margin-left: -8px; flex-shrink: 0; border-radius: 22px; border: none; background: [[tile]]; color: [[text]]; display: flex; align-items: center; justify-content: center">[[BACK]]</button></sc-if>
<h2 style="flex-grow: 1; margin: 0; font-size: 20px; font-weight: 700"><sc-if value="{{{{notCreate}}}}" hint-placeholder-val="{{{{true}}}}">카테고리 선택</sc-if><sc-if value="{{{{create}}}}" hint-placeholder-val="{{{{false}}}}">신규 카테고리</sc-if></h2>
<button type="button" onClick="{{{{closeCat}}}}" aria-label="닫기" style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 22px; border: none; background: [[tile]]; color: [[text]]; display: flex; align-items: center; justify-content: center">[[X]]</button>
</div>
<sc-if value="{{{{notCreate}}}}" hint-placeholder-val="{{{{true}}}}"><div style="flex-grow: 1; min-height: 0; display: flex; border-top: 1px solid [[line]]">
<nav aria-label="상위 카테고리" style="width: 120px; flex-shrink: 0; padding: 6px 0 36px; overflow-y: auto; display: flex; flex-direction: column"><sc-for list="{{{{rail}}}}" as="r" hint-placeholder-count="11">{rail_btn("on", "false", "padding: 6px 8px 6px 17px; border-left: 3px solid [[text]]; font-weight: 700")}{rail_btn("off", "true", "padding: 6px 8px 6px 20px; color: [[sub]]")}</sc-for></nav>
<div style="flex-grow: 1; min-width: 0; padding: 4px 16px 36px 8px; overflow-y: auto; display: flex; flex-direction: column; gap: 8px">
<h3 style="margin: 0; min-height: 44px; display: flex; align-items: center; font-size: 13px; font-weight: 500; color: [[sub]]">{{{{curTop}}}}</h3>
<div style="display: flex; flex-wrap: wrap; gap: 8px">
<sc-for list="{{{{chips}}}}" as="c" hint-placeholder-count="8"><sc-if value="{{{{c.on}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{c.pick}}}}" aria-pressed="true" style="{chip}; background: [[inv]]; color: [[inv_text]]; font-weight: 700">{{{{c.name}}}}</button></sc-if><sc-if value="{{{{c.off}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" onClick="{{{{c.pick}}}}" style="{chip}; background: [[tile]]; color: [[text]]">{{{{c.name}}}}</button></sc-if></sc-for>
<button type="button" onClick="{{{{startCreate}}}}" style="{chip}; border: 1.5px dashed [[sub]]; color: [[sub]]">[[PLUS]]카테고리</button>
</div></div></div></sc-if>
<sc-if value="{{{{create}}}}" hint-placeholder-val="{{{{false}}}}"><div style="flex-grow: 1; min-height: 0; overflow-y: auto; padding: 8px 20px 36px; border-top: 1px solid [[line]]; display: flex; flex-direction: column; gap: 20px">
<p style="margin: 8px 0 0; font-size: 14px; line-height: 1.5; word-break: keep-all; color: [[sub]]">‘{{{{curTop}}}}’ 아래에 나만 쓰는 세부 카테고리를 만들어요. 공용 카테고리는 바뀌지 않아요.</p>
<label style="display: flex; flex-direction: column; gap: 8px">{flabel("이름 · 필수", "최대 40자")}<input type="text" maxLength="40" placeholder="예: 오디오 케이블·DAC" style="{fld}"></label>
<label style="display: flex; flex-direction: column; gap: 8px">{flabel("설명 · 선택", "최대 200자")}<textarea maxLength="200" rows="2" placeholder="어떤 상품을 넣을지 적어 두면 AI 분류에 참고해요" style="{area}"></textarea></label>
<label style="display: flex; flex-direction: column; gap: 8px">{flabel("포함 예시 · 선택", "최대 5개")}<input type="text" maxLength="60" placeholder="예시를 입력하고 추가" style="{fld}"></label>
<span style="[[lbl13:sub]]">내 세부 카테고리 3 / 20</span>
<div style="display: flex; gap: 10px"><button type="button" onClick="{{{{endCreate}}}}" style="flex: 1 1 0; height: 56px; border-radius: 28px; border: none; background: [[tile]]; color: [[text]]; font-size: 16px; font-weight: 500">취소</button><button type="button" onClick="{{{{endCreate}}}}" style="flex: 2 1 0; height: 56px; border-radius: 28px; border: none; background: [[inv]]; color: [[inv_text]]; font-size: 16px; font-weight: 700">만들기</button></div>
</div></sc-if>
</div></sc-if>"""


def product_board(theme, title, init, kind="normal", es="gray"):
    """es: 편집 표시 방식. line(먹색 밑줄), gray(회색 밑줄), pencil(연필 아이콘), fill(면)"""
    """kind: normal(일반 상품), fill(정보 보완 필요)"""
    T = THEMES[theme]
    s = theme
    bg, st, fl = PHOTO["white"]
    # 보기와 편집에서 같은 칸에 두고, 편집이면 밑줄만 보인다
    box = "box-sizing: border-box; width: 100%; margin: 0; padding: 4px 0; border: none; border-bottom: 1.5px solid transparent; border-radius: 0; background: transparent; font-family: 'IBM Plex Sans KR', sans-serif; color: [[text]]; outline: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis"
    uline = "border-bottom-color: [[text]]"
    pen_html = ""
    if es == "gray":
        box = box.replace("border-bottom: 1.5px solid transparent", "border-bottom: 1px solid transparent")
        uline = "border-bottom-color: [[dash]]"
    elif es == "pencil":
        box = box.replace("border-bottom: 1.5px solid transparent", "border-bottom: 0")
        uline = "padding-right: 32px"
        pen_html = '<span aria-hidden="true" style="position: absolute; right: 0; top: 50%; transform: translateY(-50%); color: [[sub]]; display: flex"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 20h4L19 9l-4-4L4 16z"></path><path d="M13 7l4 4"></path></svg></span>'
    elif es == "fill":
        box = box.replace("border-bottom: 1.5px solid transparent", "border-bottom: 0").replace("width: 100%; margin: 0;", "width: calc(100% + 24px); margin: 0 -12px;").replace("border-radius: 0;", "border-radius: 12px;")
        uline = "background: [[card]]"
    # 한글 글자 아래 끝에서 밑줄까지 3px. 줄 높이를 글자 크기와 같게 두고, 글꼴별로 잰 한글 아래 여백을 빼서 아래 패딩을 정했다.
    # (IBM Plex Sans KR: 14px 굵게 0.25px, 22px 굵게 0.5px) 영문만 있는 칸은 기준선이 높아 조금 더 떨어진다.
    brand_box = box + "; height: 20.25px; padding: 3px 0 2.25px; font-size: 14px; font-weight: 700; line-height: 14px"
    name_box = box + "; height: 28.5px; padding: 3px 0 2.5px; font-size: 22px; font-weight: 700; line-height: 22px; margin-top: 8px"
    if es == "fill":
        brand_box = brand_box.replace("padding: 3px 0 2.25px;", "padding: 3px 12px 2.25px;")
        name_box = name_box.replace("padding: 3px 0 2.5px;", "padding: 3px 12px 2.5px;")
    row_view = lambda label, val: f'<div style="min-height: 56px; padding: 0 16px; display: flex; align-items: center; gap: 12px"><span style="width: 60px; flex-shrink: 0; font-size: 14px; color: [[sub]]">{label}</span><span style="flex-grow: 1; min-width: 0; display: flex; justify-content: flex-end; font-size: 15px; font-weight: 700">{val}</span></div>'
    row_edit = lambda label, val, on: f'<button type="button" onClick="{{{{{on}}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 56px; padding: 0 12px 0 16px; display: flex; align-items: center; gap: 12px"><span style="width: 60px; flex-shrink: 0; font-size: 14px; color: [[sub]]">{label}</span><span style="flex-grow: 1; min-width: 0; display: flex; justify-content: flex-end; font-size: 15px; font-weight: 700">{val}</span>{CHEV}</button>'
    cat_val = '<sc-if value="{{hasCat}}" hint-placeholder-val="{{true}}">{{catLabel}}</sc-if><sc-if value="{{noCat}}" hint-placeholder-val="{{false}}"><span style="font-weight: 400; color: [[sub]]">골라 주세요</span></sc-if>'
    pur_val = ('<sc-if value="{{hasPurpose}}" hint-placeholder-val="{{true}}"><span style="display: inline-flex; align-items: center; gap: 8px"><span aria-hidden="true" style="width: 10px; height: 10px; flex-shrink: 0; box-sizing: border-box; border-radius: 5px; {{pdot}}"></span>{{purpose}}</span></sc-if>'
               '<sc-if value="{{noPurpose}}" hint-placeholder-val="{{false}}"><span style="font-weight: 400; color: [[sub]]">목적 미지정</span></sc-if>')
    group = lambda inner: f'<div style="border-radius: 28px; background: [[card]]; padding: 4px 0; display: flex; flex-direction: column">{inner}</div>'
    thumb = f'<span style="width: 44px; height: 44px; flex-shrink: 0; box-sizing: border-box; padding: 5px; border-radius: 10px; background: {bg}; display: flex; align-items: center; justify-content: center">{photo_svg_local("white")}</span>'
    if kind == "normal":
        photo = f"""<div style="padding: 108px 20px 0"><div style="position: relative; aspect-ratio: 1 / 1; box-sizing: border-box; padding: 56px; border-radius: 20px; background: {bg}; display: flex; align-items: center; justify-content: center"><svg aria-hidden="true" width="100%" height="100%" viewBox="0 0 48 48" fill="none" stroke="{st}" stroke-width="3" stroke-linecap="round"><path d="M10 30v-6a14 14 0 0 1 28 0v6"></path><rect x="7" y="28" width="8" height="12" rx="3" fill="{fl}"></rect><rect x="33" y="28" width="8" height="12" rx="3" fill="{fl}"></rect></svg>
<sc-if value="{{{{edit}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{markDirty}}}}" style="position: absolute; right: 12px; bottom: 12px; height: 40px; padding: 0 16px 0 12px; border-radius: 20px; border: none; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 500">{CAM}사진 변경</button></sc-if></div></div>
"""
        body = f"""<div style="display: flex; flex-direction: column; gap: 0">
<sc-if value="{{{{view}}}}" hint-placeholder-val="{{{{true}}}}"><div style="{brand_box}">소니</div><div style="{name_box}">WH-1000XM6</div></sc-if>
<sc-if value="{{{{edit}}}}" hint-placeholder-val="{{{{false}}}}"><label style="display: block; position: relative"><span style="position: absolute; left: -9999px">브랜드</span><input type="text" defaultValue="소니" onInput="{{{{markDirty}}}}" class="ed" style="{brand_box}; {uline}">{pen_html}</label><label style="display: block; position: relative"><span style="position: absolute; left: -9999px">제품명</span><input type="text" defaultValue="WH-1000XM6" onInput="{{{{markDirty}}}}" class="ed" style="{name_box}; {uline}">{pen_html}</label></sc-if>
<div style="display: flex; align-items: baseline; gap: 6px; margin-top: 10px"><span style="font-size: 24px; [[NUM]]">KRW 549,000</span></div>
<div style="margin-top: 4px; font-size: 13px; line-height: 1.5; word-break: keep-all; color: [[sub]]">2일 전 확인한 가격이에요. 지금 가격은 원본에서 확인해 주세요.</div>
</div>
<sc-if value="{{{{view}}}}" hint-placeholder-val="{{{{true}}}}">{group(row_view("카테고리", cat_val) + row_view("목적", pur_val))}</sc-if>
<sc-if value="{{{{edit}}}}" hint-placeholder-val="{{{{false}}}}">{group(row_edit("카테고리", cat_val, "openCat") + row_edit("목적", pur_val, "openSheet"))}</sc-if>
<span style="[[lbl13:sub]]">9월 28일 저장</span>"""
        menu_html = menu([("startEdit", "[[PEN]]", "편집"), ("openDelete", "[[TRASH]]", "삭제")])
        dlg = delete_dialog(thumb, "소니 WH-1000XM6", ["‘출퇴근 헤드폰’ 후보에서 빠져요", "되돌릴 수 없어요"],
                            "삭제 뒤 이 목적에는 후보 4개가 남아요. 마지막 후보를 지워도 목적은 남아요. 이미 아카이브한 기록은 바뀌지 않아요.", f"FCategoryList{s}.dc.html")
        state = '{ edit: false, menu: false, del: false, help: false, sheet: false, discard: false, dirty: false, purpose: "출퇴근 헤드폰", draft: "출퇴근 헤드폰", cat: ["디지털·IT", "헤드폰"], draftCat: ["디지털·IT", "헤드폰"], catSheet: false, create: false, catTop: "디지털·IT" }'
        back = f"FCategoryList{s}.dc.html"
    else:
        photo = f"""<div style="position: relative; height: 330px; box-sizing: border-box; padding: 108px 20px 0">
<button type="button" onClick="{{{{startEdit}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; height: 100%; border-radius: 28px; border: 1.5px dashed [[dash]]; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; font-size: 14px; color: [[sub]]">{PHOTO_ADD}사진 추가 · 선택</button></div>"""
        dashed_row = lambda label, ph: f'<button type="button" onClick="{{{{startEdit}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 56px; padding: 0 12px 0 16px; border-radius: 20px; border: 1.5px dashed [[sub]]; display: flex; align-items: center; gap: 12px"><span style="width: 60px; flex-shrink: 0; font-size: 14px; color: [[sub]]">{label}</span><span style="flex-grow: 1; text-align: right; font-size: 15px; color: [[sub]]">{ph}</span>{CHEV}</button>'
        body = f"""<div style="display: flex; align-items: center; gap: 12px; padding: 16px; border-radius: 28px; background: [[card]]">
<span style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 14px; background: [[icon_tile]]; display: flex; align-items: center; justify-content: center">{WARN}</span>
<span style="flex-grow: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px"><span style="font-size: 15px; font-weight: 700; word-break: keep-all">상품 정보를 가져오지 못했어요</span><span style="[[lbl12:sub]]">coupang.com</span></span>
<sc-if value="{{{{view}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" style="height: 40px; padding: 0 14px 0 12px; flex-shrink: 0; border-radius: 20px; border: none; background: [[icon_tile]]; color: [[text]]; display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 500">{REDO}다시 분석</button></sc-if>
</div>
<p style="margin: 0; font-size: 14px; line-height: 1.5; word-break: keep-all; color: [[sub]]">제품명과 카테고리를 넣으면 카테고리 목록에 들어가요.</p>
<sc-if value="{{{{view}}}}" hint-placeholder-val="{{{{true}}}}"><div style="display: flex; flex-direction: column; gap: 8px">{dashed_row("제품명", "입력해 주세요")}{dashed_row("카테고리", "골라 주세요")}</div></sc-if>
<sc-if value="{{{{edit}}}}" hint-placeholder-val="{{{{false}}}}">{group(f'<label style="min-height: 56px; padding: 0 16px; display: flex; align-items: center; gap: 12px"><span style="width: 60px; flex-shrink: 0; font-size: 14px; color: [[sub]]">제품명</span><input type="text" placeholder="예: 소니 WH-1000XM6" onInput="{{{{markDirty}}}}" style="flex-grow: 1; min-width: 0; height: 40px; border: none; border-bottom: 1.5px solid [[text]]; background: transparent; font: inherit; font-size: 15px; font-weight: 700; color: [[text]]; text-align: right; outline: none"></label>' + row_edit("카테고리", cat_val, "openCat") + row_edit("목적", pur_val, "openSheet"))}</sc-if>
<span style="[[lbl13:sub]]">오늘 저장</span>"""
        menu_html = menu([("startEdit", "[[PEN]]", "편집"), ("openDelete", "[[TRASH]]", "삭제")])
        dlg = delete_dialog(f'<span style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 10px; background: [[sheet]]; display: flex; align-items: center; justify-content: center; color: [[sub]]">{PHOTO_ADD.replace("28", "20")}</span>',
                            "coupang.com에서 저장한 상품", ["되돌릴 수 없어요"], "목적에 연결되지 않은 상품이라 다른 곳은 바뀌지 않아요.", f"FHome{s}.dc.html")
        state = '{ edit: false, menu: false, del: false, help: false, sheet: false, discard: false, dirty: false, purpose: null, draft: null, cat: null, draftCat: null, catSheet: false, create: false, catTop: "디지털·IT" }'
        back = f"FHome{s}.dc.html"
    tpl = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: [[bg]]; color: [[text]]; font-family: 'IBM Plex Sans KR', sans-serif">
{top_bar()}
<div style="position: absolute; inset: 0; overflow-y: auto">
{photo}
<div style="padding: 20px 20px 140px; display: flex; flex-direction: column; gap: 20px">
{body}
</div>
</div>
{bottom_bar()}
{menu_html}{dlg}{purpose_sheet()}{category_sheet()}{discard_dialog()}
</div>
"""
    tax = [{"top": t["top"], "label": t["top"].replace("·", "·​"), "subs": t["subs"] + t["custom"]} for t in TAX]
    script = f"""class Component extends DCLogic {{
constructor(props) {{ super(props); this.state = Object.assign({state}, {init}); }}
renderVals() {{
const s = this.state;
const ps = {json.dumps(purposes_js(T), ensure_ascii=False)};
const tax = {json.dumps(tax, ensure_ascii=False)};
const shown = s.edit ? s.draft : s.purpose;
const sp = ps.find((p) => p.name === shown);
const cat = s.edit ? s.draftCat : s.cat;
const cur = tax.find((t) => t.top === s.catTop) || tax[0];
const isSel = (n) => !!s.draftCat && s.draftCat[0] === cur.top && s.draftCat[1] === n;
return {{
edit: s.edit, view: !s.edit, backHref: '{back}',
startEdit: () => this.setState({{ edit: true, menu: false, draft: s.purpose, draftCat: s.cat, dirty: false }}),
cancel: () => (s.dirty || s.draft !== s.purpose || s.draftCat !== s.cat) ? this.setState({{ discard: true }}) : this.setState({{ edit: false, sheet: false }}),
markDirty: () => {{ if (!s.dirty) this.setState({{ dirty: true }}); }},
isDiscard: s.discard, keepEditing: () => this.setState({{ discard: false }}),
discardAll: () => this.setState({{ discard: false, edit: false, sheet: false, catSheet: false, dirty: false, draft: s.purpose, draftCat: s.cat }}),
save: () => this.setState({{ edit: false, sheet: false, dirty: false, purpose: s.draft, cat: s.draftCat }}),
hasCat: !!cat, noCat: !cat, catLabel: cat ? cat[1] : '',
catOpen: s.catSheet, openCat: () => this.setState({{ catSheet: true, create: false, catTop: s.draftCat ? s.draftCat[0] : s.catTop }}), closeCat: () => this.setState({{ catSheet: false, create: false }}),
rail: tax.map((t) => ({{ label: t.label, on: t.top === cur.top, off: t.top !== cur.top, pick: () => this.setState({{ catTop: t.top, create: false }}) }})),
curTop: cur.top, chips: cur.subs.map((n) => ({{ name: n, on: isSel(n), off: !isSel(n), pick: () => this.setState({{ draftCat: [cur.top, n], catSheet: false, dirty: true }}) }})),
create: s.create, notCreate: !s.create, startCreate: () => this.setState({{ create: true }}), endCreate: () => this.setState({{ create: false }}),
menuOpen: s.menu, toggleMenu: () => this.setState({{ menu: !s.menu }}), closeMenu: () => this.setState({{ menu: false }}),
isDelete: s.del, openDelete: () => this.setState({{ del: true, menu: false, help: false }}), closeDelete: () => this.setState({{ del: false }}),
help: s.help, toggleHelp: () => this.setState({{ help: !s.help }}),
sheetOpen: s.sheet, openSheet: () => this.setState({{ sheet: true }}), closeSheet: () => this.setState({{ sheet: false }}),
hasPurpose: !!shown, noPurpose: !shown, purpose: shown || '', pdot: sp ? sp.dotStyle : '',
unlink: () => this.setState({{ draft: null, sheet: false, dirty: true }}),
options: ps.map((p) => ({{ ...p, on: s.draft === p.name, off: s.draft !== p.name, pick: () => this.setState({{ draft: p.name, sheet: false, dirty: true }}) }}))
}};
}}
}}"""
    extra = {"EXT": EXT}
    return head(title, T) + fill(tpl, T, theme, extra) + tail(script)


def photo_svg_local(kind):
    _, st, fl = PHOTO[kind]
    return (f'<svg aria-hidden="true" width="100%" height="100%" viewBox="0 0 48 48" fill="none" stroke="{st}" stroke-width="3" stroke-linecap="round"><path d="M10 30v-6a14 14 0 0 1 28 0v6"></path>'
            f'<rect x="7" y="28" width="8" height="12" rx="3" fill="{fl}"></rect><rect x="33" y="28" width="8" height="12" rx="3" fill="{fl}"></rect></svg>')


def processing_board(theme, title, init):
    T = THEMES[theme]
    s = theme
    dlg = delete_dialog(f'<span style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 10px; background: [[sheet]]; color: [[sub]]; display: flex; align-items: center; justify-content: center">{SPARK.replace("28", "20")}</span>',
                        "분석 중 · 29cm.co.kr", ["되돌릴 수 없어요"], "아직 목적에 연결되지 않은 상품이라 다른 곳은 바뀌지 않아요.", f"FHome{s}.dc.html")
    tpl = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: [[bg]]; color: [[text]]; font-family: 'IBM Plex Sans KR', sans-serif">
<a href="FHome[[s]].dc.html" aria-label="뒤로" style="position: absolute; left: 16px; top: 52px; z-index: 3; width: 44px; height: 44px; border-radius: 22px; background: [[card]]; color: [[text]]; display: flex; align-items: center; justify-content: center">[[BACK]]</a>
<button type="button" onClick="{{{{toggleMenu}}}}" aria-label="더보기" style="position: absolute; right: 16px; top: 52px; z-index: 3; border: none; width: 44px; height: 44px; border-radius: 22px; background: [[card]]; color: [[text]]; display: flex; align-items: center; justify-content: center">[[MORE]]</button>
<div style="position: absolute; inset: 0; overflow-y: auto">
<div style="height: 330px; box-sizing: border-box; padding: 108px 20px 0"><div style="height: 100%; border-radius: 28px; background: [[card]]; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; font-size: 14px; color: [[sub]]"><span style="width: 56px; height: 56px; border-radius: 20px; background: [[icon_tile]]; color: [[text]]; display: flex; align-items: center; justify-content: center">{SPARK}</span>상품 정보 추출 중</div></div>
<div style="padding: 24px 20px 140px; display: flex; flex-direction: column; gap: 12px">
<div style="font-size: 22px; font-weight: 700">29cm.co.kr</div>
<p style="margin: 0; font-size: 14px; line-height: 1.6; word-break: keep-all; color: [[sub]]">정보를 가져오는 동안은 편집할 수 없어요. 끝나면 앱을 다시 열거나 새로고침할 때 반영돼요.</p>
<span style="[[lbl13:sub]]">방금 저장</span>
</div>
</div>
<div style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 4; padding: 12px 20px 36px; background: [[bg]]"><a href="#" style="height: 56px; border-radius: 28px; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; justify-content: center; gap: 8px; font-size: 16px; font-weight: 700">원본 보기[[EXT]]</a></div>
{menu([("openDelete", "[[TRASH]]", "삭제")])}{dlg}
</div>
"""
    script = f"""class Component extends DCLogic {{
constructor(props) {{ super(props); this.state = Object.assign({{ menu: false, del: false, help: false }}, {init}); }}
renderVals() {{
const s = this.state;
return {{
menuOpen: s.menu, toggleMenu: () => this.setState({{ menu: !s.menu }}), closeMenu: () => this.setState({{ menu: false }}),
isDelete: s.del, openDelete: () => this.setState({{ del: true, menu: false, help: false }}), closeDelete: () => this.setState({{ del: false }}),
help: s.help, toggleHelp: () => this.setState({{ help: !s.help }})
}};
}}
}}"""
    return head(title, T) + fill(tpl, T, theme, {"EXT": EXT}) + tail(script)


BOARDS = [
    ("FProductDetail", "상품 · 상세"),
    ("FProductEdit", "상품 · 그 자리에서 편집"),
    ("FProductPurposeSheet", "상품 · 편집 · 목적 선택 시트"),
    ("FProductCategoryPicker", "상품 · 편집 · 카테고리 선택 시트"),
    ("FProductCategoryCreate", "상품 · 편집 · 신규 카테고리"),
    ("FProductDeleteConfirm", "상품 · 삭제 확인 (도움말 펼침)"),
    ("FProductFill", "상품 · 정보 보완 필요"),
    ("FProductFillEdit", "상품 · 정보 보완 필요 · 편집"),
    ("FProductProcessing", "상품 · 분석 중"),
]


def main():
    out = os.path.join(sys.argv[1], "project")
    os.makedirs(out, exist_ok=True)
    boards = {}
    for th in "LD":
        nm = THEMES[th]["name"]
        boards[f"FProductDetail{th}"] = product_board(th, f"{nm} 상품 상세", "{}")
        boards[f"FProductEdit{th}"] = product_board(th, f"{nm} 상품 편집", "{ edit: true }")
        boards[f"FProductPurposeSheet{th}"] = product_board(th, f"{nm} 상품 목적 선택", "{ edit: true, sheet: true }")
        boards[f"FProductCategoryPicker{th}"] = product_board(th, f"{nm} 상품 카테고리 선택", "{ edit: true, catSheet: true }")
        boards[f"FProductCategoryCreate{th}"] = product_board(th, f"{nm} 신규 카테고리", "{ edit: true, catSheet: true, create: true }")
        boards[f"FProductDeleteConfirm{th}"] = product_board(th, f"{nm} 상품 삭제 확인", "{ del: true, help: true }")
        boards[f"FProductFill{th}"] = product_board(th, f"{nm} 상품 정보 보완 필요", "{}", kind="fill")
        boards[f"FProductFillEdit{th}"] = product_board(th, f"{nm} 상품 정보 보완 편집", "{ edit: true }", kind="fill")
        boards[f"FProductProcessing{th}"] = processing_board(th, f"{nm} 상품 분석 중", "{}")
    for name, html in boards.items():
        with open(os.path.join(out, name + ".dc.html"), "w") as f:
            f.write(html)
    print(" ".join(sorted(boards)))


if __name__ == "__main__":
    main()
