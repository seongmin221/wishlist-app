"""전체 화면 · 목적 묶음 보드를 만든다.

python3 gen_purpose.py <out_root>
템플릿 안의 [[키]]는 테마 값으로, {{키}}는 보드 런타임 값으로 채워진다.
"""
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen_full_home import THEMES, PHOTO, DANGER, NUM, ICON_X, ICON_PEN, lbl, head, tail, photo_svg
from gen_category import nav, ICON_BACK, ICON_MORE, ICON_TRASH, ICON_DOWN

COLORS = [("코랄", "#F96857", "#F84D39"), ("머스터드", "#F9CD61", "#B38107"), ("페리윙클", "#7477FF", "#7477FF"),
          ("시안", "#BBF3FE", "#0398B5"), ("민트", "#8ED8B0", "#369C65"), ("핑크", "#F4A6C6", "#EA5492")]

ICONS = {
    "i_heart": ("하트", '<path d="M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z"></path>'),
    "i_home": ("집", '<path d="M4 11l8-7 8 7v9H4z"></path>'),
    "i_plane": ("여행", '<path d="M3 13l18-7-7 18-3-8z"></path>'),
    "i_gift": ("선물", '<rect x="4" y="9" width="16" height="11" rx="1"></rect><path d="M12 9v11M4 13h16M12 9c-2-4-6-3-5 0M12 9c2-4 6-3 5 0"></path>'),
    "i_tent": ("캠핑", '<path d="M3 20L12 5l9 15zM12 5v15"></path>'),
    "i_music": ("음악", '<path d="M9 18V6l10-2v12"></path><circle cx="7" cy="18" r="2"></circle><circle cx="17" cy="16" r="2"></circle>'),
    "i_star": ("별", '<path d="M12 4l2.4 5 5.6.8-4 3.9 1 5.5-5-2.7-5 2.7 1-5.5-4-3.9 5.6-.8z"></path>'),
    "i_book": ("책", '<path d="M5 4h10a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z"></path>'),
}

ITEMS = [
    ("소니 WH-1000XM6", "KRW 549,000", "1 / 1", "white", "무신사 · 2일 전 확인", False),
    ("보스 QuietComfort Ultra", "KRW 499,000", "4 / 5", "brown", "보스 공식몰 · 2일 전 확인", False),
    ("애플 AirPods Max", "KRW 769,000", "3 / 4", "beige", "애플 · 1주 전 확인", False),
    ("소니 ULT WEAR", "KRW 279,000", "1 / 1", "white", "11번가 · 2주 전 확인", False),
    ("마샬 MAJOR V", "KRW 229,000", "4 / 5", "green", "29CM · 1주 전 확인", True),
]

POOL = [
    ("젠하이저 MOMENTUM 4", "KRW 389,000", "디지털·IT", "헤드폰", None, "beige"),
    ("뱅앤올룹슨 Beoplay H95", "KRW 1,190,000", "디지털·IT", "헤드폰", None, "brown"),
    ("오디오테크니카 ATH-M50x", "KRW 219,000", "디지털·IT", "헤드폰", None, "white"),
    ("애플 AirPods Pro 3", "KRW 369,000", "디지털·IT", "이어폰", None, "white"),
    ("HHKB Professional HYBRID", "KRW 398,000", "디지털·IT", "키보드", None, "beige"),
    ("로지텍 MX Keys S", "KRW 139,000", "디지털·IT", "키보드", None, "green"),
    ("LG 울트라기어 27GR93U", "KRW 549,000", "디지털·IT", "모니터", None, "brown"),
    ("호카 스피드고트 6", "KRW 209,000", "패션·잡화", "신발", ("가을 트레일 러닝", 1), "beige"),
    ("살로몬 XT-6", "KRW 259,000", "패션·잡화", "신발", None, "green"),
    ("허먼밀러 에어론", "KRW 2,150,000", "가구·인테리어", "의자", ("홈오피스 의자", 2), "brown"),
    ("발뮤다 더 토스터", "KRW 349,000", "생활·주방·가전", "주방 가전", None, "brown"),
    ("헬리녹스 체어 원", "KRW 139,000", "스포츠·아웃도어·여행", "캠핑 용품", ("캠핑 첫 장비", 3), "green"),
]

HOME_PS = [("출퇴근 헤드폰", 5, "후보 5 · 어제 후보 추가", 0, "i_music"), ("가을 트레일 러닝", 3, "후보 3 · 3일 전 후보 추가", 1, "i_star"),
           ("홈오피스 의자", 2, "후보 2 · 1주 전 후보 추가", 2, "i_book"), ("캠핑 첫 장비", 4, "후보 4 · 2주 전 후보 추가", 3, "i_tent"),
           ("거실 조명 바꾸기", 3, "후보 3 · 3주 전 후보 추가", 4, "i_home"), ("엄마 생신 선물", 2, "후보 2 · 1달 전 후보 추가", 5, "i_gift"),
           ("여행 캐리어", 0, "1달 전 만듦", 1, "i_plane")]


def fill(tpl, T, theme, extra=None):
    vals = dict(T)
    vals["red"] = DANGER["A"][theme]
    vals["s"] = theme
    vals["NUM"] = NUM
    vals["X"] = ICON_X
    vals["BACK"] = ICON_BACK
    vals["MORE"] = ICON_MORE
    vals["DOWN"] = ICON_DOWN
    vals["TRASH"] = ICON_TRASH
    vals["PEN"] = ICON_PEN.replace('width="20" height="20"', 'width="18" height="18"')
    vals["CHECK"] = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6"><path d="M5 12l5 5L20 7"></path></svg>'
    vals["PLUS"] = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 5v14M5 12h14"></path></svg>'
    vals["ARCHIVE"] = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="4" width="18" height="5" rx="1.5"></rect><path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9M10 13h4"></path></svg>'
    vals["RIGHT"] = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M9 5l7 7-7 7"></path></svg>'
    vals["scrimDiv"] = f'background: {T["scrim"]}; backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px)'
    if extra:
        vals.update(extra)

    def rep(m):
        k = m.group(1)
        if k.startswith("lbl"):
            size, color = k[3:].split(":")
            return lbl(int(size), vals.get(color, color))
        return str(vals[k])
    return re.sub(r"\[\[([A-Za-z0-9_:#]+)\]\]", rep, tpl)


def thumb_svg_holes(prefix, size="100%"):
    return (f'<svg aria-hidden="true" width="{size}" height="{size}" viewBox="0 0 48 48" fill="none" stroke="{{{{{prefix}.s}}}}" stroke-width="3" stroke-linecap="round"><path d="M10 30v-6a14 14 0 0 1 28 0v6"></path>'
            f'<rect x="7" y="28" width="8" height="12" rx="3" fill="{{{{{prefix}.f}}}}"></rect><rect x="33" y="28" width="8" height="12" rx="3" fill="{{{{{prefix}.f}}}}"></rect></svg>')


def thumb(prefix, px, radius, pad=6):
    return (f'<span style="width: {px}px; height: {px}px; flex-shrink: 0; box-sizing: border-box; padding: {pad}px; border-radius: {radius}px; background: {{{{{prefix}.bg}}}}; display: flex; align-items: center; justify-content: center; overflow: hidden">'
            + thumb_svg_holes(prefix) + "</span>")


def icon_switch(prefix, size, stroke="1.8"):
    hint = lambda k: "true" if k == "i_music" else "false"
    return "".join(f'<sc-if value="{{{{{prefix}.k_{k}}}}}" hint-placeholder-val="{{{{{hint(k)}}}}}"><svg aria-hidden="true" width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="{stroke}">{p}</svg></sc-if>' for k, (_, p) in ICONS.items())


def color_picker():
    return """<div style="display: flex; gap: 12px; flex-wrap: wrap"><sc-for list="{{colors}}" as="c" hint-placeholder-count="6"><button type="button" onClick="{{c.pick}}" aria-label="{{c.label}}" aria-pressed="{{c.on}}" style="all: unset; cursor: pointer; width: 40px; height: 40px; box-sizing: border-box; border-radius: 20px; {{c.style}}"></button></sc-for></div>"""


def icon_picker(on_bg, on_fg, off_bg, off_fg):
    inner = "".join(f'<sc-if value="{{{{ic.k_{k}}}}}" hint-placeholder-val="{{{{false}}}}"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">{p}</svg></sc-if>' for k, (_, p) in ICONS.items())
    btn = lambda key, hint, bg, fg: f'<sc-if value="{{{{ic.{key}}}}}" hint-placeholder-val="{{{{{hint}}}}}"><button type="button" onClick="{{{{ic.pick}}}}" aria-label="{{{{ic.label}}}}" style="all: unset; cursor: pointer; width: 44px; height: 44px; border-radius: 14px; background: {bg}; color: {fg}; display: flex; align-items: center; justify-content: center">{inner}</button></sc-if>'
    return f'<div style="display: flex; flex-wrap: wrap; gap: 8px"><sc-for list="{{{{icons}}}}" as="ic" hint-placeholder-count="8">{btn("on", "false", on_bg, on_fg)}{btn("off", "true", off_bg, off_fg)}</sc-for></div>'


def picker_js(theme, color_default, icon_default):
    ring_on = THEMES[theme]["text"]
    return f"""const palette = {json.dumps([{"label": n, "face": f, "ring": r} for n, f, r in COLORS], ensure_ascii=False)};
const iconKeys = {json.dumps(list(ICONS), ensure_ascii=False)};
const iconLabel = {json.dumps({k: v[0] for k, v in ICONS.items()}, ensure_ascii=False)};
const colors = palette.map((c, i) => ({{ label: c.label, on: s.color === i, style: 'background: ' + c.face + ';' + ({'true' if THEMES[theme]['ring'] else 'false'} ? ' border: 1px solid ' + c.ring + ';' : '') + (s.color === i ? ' outline: 2px solid {ring_on}; outline-offset: 3px;' : ''), pick: () => this.setState({{ color: i, dirty: true }}) }}));
const icons = iconKeys.map((k) => {{ const o = {{ label: iconLabel[k], on: s.icon === k, off: s.icon !== k, pick: () => this.setState({{ icon: k, dirty: true }}) }}; iconKeys.forEach((j) => {{ o['k_' + j] = j === k; }}); return o; }});"""


def photo_entry(kind, **kw):
    bg, s, f = PHOTO[kind]
    d = {"bg": bg, "s": s, "f": f}
    d.update(kw)
    return d


# ---------------------------------------------------------------- 목적 탭 첫 화면

def purpose_home(theme, title, init):
    T = THEMES[theme]
    ps = []
    for name, count, meta, ci, ic in HOME_PS:
        d = {"name": name, "count": count, "meta": meta, "face": COLORS[ci][1], "empty": count == 0}
        d.update({"k_" + k: k == ic for k in ICONS})
        ps.append(d)
    thumbs = "".join(f'<div style="flex: 1 1 0; min-width: 0"><div style="aspect-ratio: 1 / 1; box-sizing: border-box; padding: 10px; border-radius: 14px; background: {PHOTO[k][0]}; display: flex; align-items: center; justify-content: center; overflow: hidden">{photo_svg(k, "100%")}</div></div>' for k in ("white", "brown", "beige", "white"))
    fld = "box-sizing: border-box; width: 100%; height: 56px; padding: 0 18px; border-radius: 20px; border: none; background: [[tile]]; font: inherit; font-size: 16px; color: inherit; outline: none"
    flabel = lambda t: f'<span style="font-size: 13px; color: [[sub]]">{t}</span>'
    tpl = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: [[bg]]; color: [[text]]; font-family: 'IBM Plex Sans KR', sans-serif">
<div style="position: absolute; inset: 0; overflow-y: auto">
<header style="padding: 56px 20px 20px; display: flex; flex-direction: column; gap: 6px"><h1 style="margin: 0; font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 28px; line-height: 1.0">목적</h1><span style="[[lbl13:sub]]">비교 중 7개 · 최근 활동순</span></header>
<div style="padding: 0 16px; display: flex; flex-direction: column">
<sc-for list="{{{{ps}}}}" as="p" hint-placeholder-count="7">
<article style="position: relative; border-radius: 28px; border: 2px solid [[bg]]; color: #1D1D1D; {{{{p.cardStyle}}}}">
<sc-if value="{{{{p.isClosed}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" onClick="{{{{p.pick}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 64px; padding: 14px 20px 30px 14px; display: flex; align-items: center; justify-content: space-between; gap: 10px"><span style="display: flex; align-items: center; gap: 12px"><span style="width: 36px; height: 36px; flex-shrink: 0; border-radius: 12px; background: [[card]]; color: [[text]]; display: flex; align-items: center; justify-content: center">{icon_switch("p", 18)}</span><span style="font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 28px; line-height: 1.0">{{{{p.name}}}}</span></span><span style="font-size: 26px; [[NUM]]">{{{{p.count}}}}</span></button></sc-if>
<sc-if value="{{{{p.isOpen}}}}" hint-placeholder-val="{{{{false}}}}"><a href="FPurposeDetail[[s]].dc.html" aria-label="{{{{p.name}}}} 목적 열기" style="display: block; color: #1D1D1D">
<div style="box-sizing: border-box; width: 100%; min-height: 64px; padding: 14px 20px 30px 14px; display: flex; align-items: center; justify-content: space-between; gap: 10px"><span style="display: flex; align-items: center; gap: 12px"><span style="width: 36px; height: 36px; flex-shrink: 0; border-radius: 12px; background: [[card]]; color: [[text]]; display: flex; align-items: center; justify-content: center">{icon_switch("p", 18)}</span><span style="font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 28px; line-height: 1.0">{{{{p.name}}}}</span></span><span style="font-size: 26px; [[NUM]]">{{{{p.count}}}}</span></div>
<div style="padding: 0 20px 40px; margin-top: -16px; display: flex; flex-direction: column; gap: 14px"><span style="[[lbl12:#1D1D1D]]">{{{{p.meta}}}}</span>
<sc-if value="{{{{p.hasItems}}}}" hint-placeholder-val="{{{{true}}}}"><div style="display: flex; gap: 8px">{thumbs}</div></sc-if></div>
</a></sc-if>
</article>
</sc-for>
</div>
<div style="padding: 20px 20px 0"><button type="button" onClick="{{{{openCreate}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 56px; border-radius: 28px; border: 1.5px dashed [[sub]]; display: flex; align-items: center; justify-content: center; gap: 8px; font-size: 16px; font-weight: 700">[[PLUS]]목적 추가</button></div>
<section style="padding: 32px 20px 140px; display: flex; flex-direction: column; gap: 12px">
<h2 style="margin: 0; font-size: 13px; font-weight: 500; color: [[sub]]">끝난 비교</h2>
<a href="FArchiveList[[s]].dc.html" style="box-sizing: border-box; min-height: 64px; padding: 0 16px; border-radius: 28px; background: [[card]]; display: flex; align-items: center; gap: 12px">
<span style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 14px; background: [[icon_tile]]; display: flex; align-items: center; justify-content: center">[[ARCHIVE]]</span>
<span style="flex-grow: 1; display: flex; flex-direction: column; gap: 3px"><span style="display: flex; align-items: baseline; gap: 6px; font-size: 16px; font-weight: 700">아카이브<span style="font-size: 16px; [[NUM]]">2</span></span><span style="[[lbl12:sub]]">겨울 패딩 · 기계식 키보드</span></span>[[RIGHT]]</a>
</section>
</div>
{nav(T, theme, 'purpose')}
<sc-if value="{{{{createOpen}}}}" hint-placeholder-val="{{{{false}}}}"><div style="position: absolute; inset: 0; z-index: 10; [[scrimDiv]]"></div>
<div style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 11; max-height: 760px; overflow-y: auto; box-sizing: border-box; padding: 10px 20px 36px; border-radius: 36px 36px 0 0; background: [[sheet]]; display: flex; flex-direction: column; gap: 20px">
<div style="align-self: center; width: 40px; height: 5px; border-radius: 3px; background: [[handle]]"></div>
<div style="display: flex; align-items: center; justify-content: space-between"><h2 style="margin: 0; font-size: 20px; font-weight: 700">새 목적</h2><button type="button" onClick="{{{{closeCreate}}}}" aria-label="닫기" style="width: 44px; height: 44px; border-radius: 22px; border: none; background: [[tile]]; display: flex; align-items: center; justify-content: center">[[X]]</button></div>
<label style="display: flex; flex-direction: column; gap: 8px">{flabel("이름 · 필수")}<input type="text" placeholder="예: 출퇴근 헤드폰" style="{fld}"></label>
<label style="display: flex; flex-direction: column; gap: 8px">{flabel("설명 · 선택")}<input type="text" placeholder="예: 지하철에서 쓸 노이즈 캔슬링 헤드폰" style="{fld}"></label>
<div style="display: flex; flex-direction: column; gap: 12px">{flabel("색")}{color_picker()}</div>
<div style="display: flex; flex-direction: column; gap: 10px">{flabel("아이콘 · 필수")}{icon_picker("[[inv]]", "[[inv_text]]", "[[tile]]", "[[text]]")}</div>
<p style="margin: 0; font-size: 13px; line-height: 1.5; word-break: keep-all; color: [[sub]]">후보는 만든 뒤에 추가할 수 있어요. 비어 있는 목적도 그대로 남아요.</p>
<div style="display: flex; gap: 10px"><button type="button" onClick="{{{{closeCreate}}}}" style="flex: 1 1 0; height: 56px; border-radius: 28px; border: none; background: [[tile]]; color: [[text]]; font-size: 16px; font-weight: 500">취소</button><a href="FPurposeDetailEmpty[[s]].dc.html" style="flex: 2 1 0; height: 56px; border-radius: 28px; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 700">만들기</a></div>
</div></sc-if>
</div>
"""
    script = f"""class Component extends DCLogic {{
constructor(props) {{ super(props); this.state = Object.assign({{ open: 0, create: false, color: 0, icon: 'i_heart', dirty: false }}, {init}); }}
renderVals() {{
const s = this.state;
{picker_js(theme, 0, None)}
const ps = {json.dumps(ps, ensure_ascii=False)};
return {{ colors: colors, icons: icons, createOpen: s.create, openCreate: () => this.setState({{ create: true }}), closeCreate: () => this.setState({{ create: false }}),
ps: ps.map((p, i) => ({{ ...p, isOpen: s.open === i, isClosed: s.open !== i, hasItems: !p.empty, cardStyle: 'background: ' + p.face + (i ? '; margin-top: -22px' : ''), pick: () => this.setState({{ open: i }}) }})) }};
}}
}}"""
    return head(title, T) + fill(tpl, T, theme) + tail(script)


# ---------------------------------------------------------------- 목적 상세

def purpose_detail(theme, title, init, empty=False):
    T = THEMES[theme]
    if empty:
        pname, pdesc = "여행 캐리어", "다음 달 출장 때 쓸 기내용 캐리어"
        items = []
    else:
        pname, pdesc = "출퇴근 헤드폰", "지하철에서 쓸 노이즈 캔슬링 헤드폰"
        items = [photo_entry(k, name=n, price=p, ratio=f"aspect-ratio: {r}; background: {PHOTO[k][0]}", meta=m, pending=pd, hasDot=False, dotStyle="")
                 for n, p, r, k, m, pd in ITEMS]
    pool = [photo_entry(k, name=n, price=p, top=t, cat=c, purpose=(pp[0] if pp else ""), hasOther=bool(pp),
                        dotStyle=("background: " + COLORS[pp[1]][1] + ";" + (" border: 1px solid " + COLORS[pp[1]][2] + ";" if T["ring"] else "")) if pp else "")
            for n, p, t, c, pp, k in POOL]
    card = """<a href="FProductDetail[[s]].dc.html" style="display: flex; flex-direction: column; gap: 8px">
<div style="{{p.ratio}}; position: relative; box-sizing: border-box; border-radius: 20px; display: flex; align-items: center; justify-content: center; overflow: hidden"><svg aria-hidden="true" width="56%" height="56%" viewBox="0 0 48 48" fill="none" stroke="{{p.s}}" stroke-width="3" stroke-linecap="round"><path d="M10 30v-6a14 14 0 0 1 28 0v6"></path><rect x="7" y="28" width="8" height="12" rx="3" fill="{{p.f}}"></rect><rect x="33" y="28" width="8" height="12" rx="3" fill="{{p.f}}"></rect></svg>
<sc-if value="{{p.pending}}" hint-placeholder-val="{{false}}"><span aria-label="분류·목적 미확정" style="position: absolute; left: 8px; top: 8px; width: 32px; height: 32px; border-radius: 10px; background: #FFFFFF; color: #1D1D1D; display: flex; align-items: center; justify-content: center"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 4v16M4 12h16M6.3 6.3l11.4 11.4M17.7 6.3L6.3 17.7"></path></svg></span></sc-if></div>
<span style="display: flex; flex-direction: column; gap: 4px; padding: 0 2px"><span style="font-size: 14px; line-height: 1.35; word-break: keep-all">{{p.name}}</span><span style="font-size: 18px; [[NUM]]">{{p.price}}</span><span style="[[lbl12:sub]] overflow: hidden; text-overflow: ellipsis">{{p.meta}}</span></span></a>"""
    col = lambda key, n: f'<div style="flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 20px"><sc-for list="{{{{{key}}}}}" as="p" hint-placeholder-count="{n}">{card}</sc-for></div>'
    white_btn = "border: none; width: 44px; height: 44px; flex-shrink: 0; border-radius: 22px; background: [[card]]; color: [[text]]; display: flex; align-items: center; justify-content: center"
    pill = "height: 44px; padding: 0 18px 0 14px; border-radius: 22px; border: none; display: flex; align-items: center; gap: 6px; font-size: 15px; font-weight: 500"
    # 보기와 편집에서 이름·설명 칸의 크기와 여백을 같게 두고, 편집일 때만 반투명 면을 깐다
    # 한글 글자 아래 끝에서 밑줄까지 3px. 도현 28px은 한글 아래에 5px이 남아 줄 높이를 24px로 줄이고(위아래 2px씩 줄어듦), 위 패딩 3px로 글자 윗부분이 잘리지 않게 했다.
    name_box = "box-sizing: border-box; width: 100%; height: 28px; margin: 0; padding: 3px 0 0; border: none; border-bottom: 1px solid transparent; border-radius: 0; background: transparent; font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 28px; line-height: 24px; color: #1D1D1D; outline: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis"
    desc_box = "box-sizing: border-box; width: 100%; height: 21.75px; margin: 8px 0 0; padding: 3px 0 2.75px; border: none; border-bottom: 1px solid transparent; border-radius: 0; background: transparent; font-family: 'IBM Plex Sans KR', sans-serif; font-size: 15px; line-height: 15px; color: #1D1D1D; outline: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis"
    edit_bg = "border-bottom-color: rgba(29,29,29,0.35)"
    radio_on = '<span style="width: 24px; height: 24px; flex-shrink: 0; border-radius: 12px; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; justify-content: center">[[CHECK]]</span>'
    radio_off = '<span style="width: 24px; height: 24px; flex-shrink: 0; box-sizing: border-box; border-radius: 12px; border: 2px solid [[dash]]"></span>'
    chip = lambda on: ("all: unset; cursor: pointer; flex-shrink: 0; height: 40px; padding: 0 14px; border-radius: 20px; display: flex; align-items: center; gap: 4px; font-size: 14px; white-space: nowrap; "
                       + ("background: [[inv]]; color: [[inv_text]]; font-weight: 700" if on else "background: [[tile]]; color: [[text]]"))
    small_down = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M6 9l6 6 6-6"></path></svg>'
    group = lambda label, toggle, open_key, arrow, list_html: f"""<div style="border-radius: 20px; background: [[tile]]; overflow: hidden"><button type="button" onClick="{{{{{toggle}}}}}" aria-expanded="{{{{{open_key}}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 48px; padding: 0 14px 0 16px; display: flex; align-items: center; justify-content: space-between; font-size: 15px">{label}<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" style="{{{{{arrow}}}}}"><path d="M6 9l6 6 6-6"></path></svg></button>
<sc-if value="{{{{{open_key}}}}}" hint-placeholder-val="{{{{false}}}}"><div style="max-height: 200px; overflow-y: auto; padding: 2px 16px 14px; display: flex; flex-direction: column; gap: 10px">{list_html}</div></sc-if></div>"""
    item_line = lambda with_price: f'<sc-for list="{{{{all}}}}" as="a" hint-placeholder-count="5"><div style="display: flex; align-items: center; gap: 12px; font-size: 14px">{thumb("a", 36, 10, 4)}<span style="flex-grow: 1; min-width: 0">{{{{a.name}}}}</span>' + ('<span style="font-size: 13px; [[NUM]] color: [[sub]]">{{a.price}}</span>' if with_price else "") + "</div></sc-for>"
    tpl = f"""<div data-root="1" style="width: 390px; height: 844px; position: relative; overflow: hidden; background: [[bg]]; color: [[text]]; font-family: 'IBM Plex Sans KR', sans-serif">
<div data-scroll="1" onScroll="{{{{onScroll}}}}" style="position: absolute; inset: 0; overflow-y: auto; {{{{headStyle}}}}">
<section style="padding: 52px 20px 28px; display: flex; flex-direction: column; gap: 16px; color: #1D1D1D">
<div style="display: flex; justify-content: space-between; min-height: 44px">
<sc-if value="{{{{view}}}}" hint-placeholder-val="{{{{true}}}}"><a href="FPurposeHome[[s]].dc.html" aria-label="뒤로" style="{white_btn}">[[BACK]]</a><button type="button" onClick="{{{{toggleMenu}}}}" aria-label="더보기" style="{white_btn}">[[MORE]]</button></sc-if>
<sc-if value="{{{{edit}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{cancel}}}}" aria-label="뒤로" style="{white_btn}">[[BACK]]</button></sc-if>
</div>
<div style="display: flex; flex-direction: column; gap: 2px">
<div style="display: flex; align-items: center; gap: 12px">
<span style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 14px; background: [[card]]; color: [[text]]; display: flex; align-items: center; justify-content: center">{icon_switch("hi", 22)}</span>
<sc-if value="{{{{view}}}}" hint-placeholder-val="{{{{true}}}}"><h1 style="flex-grow: 1; min-width: 0; {name_box}">{pname}</h1></sc-if>
<sc-if value="{{{{edit}}}}" hint-placeholder-val="{{{{false}}}}"><label style="flex-grow: 1; min-width: 0; display: block"><span style="position: absolute; left: -9999px">목적 이름</span><input type="text" defaultValue="{pname}" onInput="{{{{markDirty}}}}" class="ed" style="{name_box}; {edit_bg}"></label></sc-if>
</div>
<sc-if value="{{{{view}}}}" hint-placeholder-val="{{{{true}}}}"><p style="{desc_box}">{pdesc}</p></sc-if>
<sc-if value="{{{{edit}}}}" hint-placeholder-val="{{{{false}}}}"><label style="display: block"><span style="position: absolute; left: -9999px">목적 설명</span><input type="text" defaultValue="{pdesc}" placeholder="설명 · 선택" onInput="{{{{markDirty}}}}" class="ed" style="{desc_box}; {edit_bg}"></label></sc-if>
</div>
<sc-if value="{{{{view}}}}" hint-placeholder-val="{{{{true}}}}"><div style="display: flex; gap: 8px"><button type="button" onClick="{{{{openAdd}}}}" style="{pill}; background: [[card]]; color: [[text]]">[[PLUS]]후보 추가</button>
<sc-if value="{{{{hasItems}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" onClick="{{{{end}}}}" style="{pill}; background: [[inv]]; color: [[inv_text]]">[[ARCHIVE]]비교 끝내기</button></sc-if></div></sc-if>
<sc-if value="{{{{edit}}}}" hint-placeholder-val="{{{{false}}}}"><div style="display: flex; flex-direction: column; gap: 14px; padding: 16px; border-radius: 28px; background: [[card]]; color: [[text]]">
<span style="font-size: 13px; color: [[sub]]">색</span>{color_picker()}
<span style="font-size: 13px; color: [[sub]]">아이콘 · 필수</span>{icon_picker("[[inv]]", "[[inv_text]]", "[[tile]]", "[[text]]")}</div>
<div style="display: flex; gap: 8px"><button type="button" onClick="{{{{cancel}}}}" style="{pill}; padding: 0 22px; background: [[card]]; color: [[text]]">취소</button><button type="button" onClick="{{{{save}}}}" style="{pill}; padding: 0 24px; background: [[inv]]; color: [[inv_text]]; font-weight: 700">저장</button></div></sc-if>
</section>
<section data-sheet="1" style="min-height: 740px; box-sizing: border-box; border-radius: 36px 36px 0 0; background: [[sheet]]; color: [[text]]; padding: 0 16px 140px; display: flex; flex-direction: column; gap: 12px">
<button type="button" onClick="{{{{toggleCollapse}}}}" aria-label="{{{{handleLabel}}}}" style="all: unset; cursor: grab; align-self: stretch; height: 28px; display: flex; align-items: center; justify-content: center"><span style="width: 40px; height: 5px; border-radius: 3px; background: [[handle]]"></span></button>
<sc-if value="{{{{hasItems}}}}" hint-placeholder-val="{{{{true}}}}"><h2 style="margin: 0; padding: 0 4px; display: flex; align-items: baseline; gap: 6px; font-size: 15px; font-weight: 700">후보<span style="font-size: 18px; [[NUM]]">{{{{count}}}}</span></h2>
<div style="display: flex; gap: 12px; align-items: flex-start">{col("left", 3)}{col("right", 2)}</div></sc-if>
<sc-if value="{{{{isEmpty}}}}" hint-placeholder-val="{{{{false}}}}"><div style="height: 400px; padding: 0 20px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; text-align: center">
<span style="width: 56px; height: 56px; border-radius: 20px; background: [[tile]]; display: flex; align-items: center; justify-content: center"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="4" y="8" width="16" height="12" rx="3"></rect><path d="M7 5h10"></path></svg></span>
<h2 style="margin: 0; font-size: 18px; font-weight: 700">아직 후보가 없어요</h2>
<p style="margin: 0; font-size: 14px; line-height: 1.5; word-break: keep-all; color: [[sub]]">저장해 둔 상품 중에서 이 목적으로 비교할 후보를 골라 주세요.</p>
</div></sc-if>
</section>
</div>
<div aria-label="접힌 헤더" style="position: absolute; left: 0; right: 0; top: 0; z-index: 4; box-sizing: border-box; padding: 52px 20px 12px; display: flex; align-items: center; gap: 10px; color: #1D1D1D; {{{{barStyle}}}}">
<a href="FPurposeHome[[s]].dc.html" aria-label="뒤로" style="{white_btn}">[[BACK]]</a>
<span style="flex-grow: 1; min-width: 0; display: flex; align-items: center; gap: 8px"><span style="width: 36px; height: 36px; flex-shrink: 0; border-radius: 12px; background: [[card]]; color: [[text]]; display: flex; align-items: center; justify-content: center">{icon_switch("hi", 18)}</span><span style="min-width: 0; font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 20px; line-height: 1.0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis">{pname}</span></span>
<button type="button" onClick="{{{{openAdd}}}}" aria-label="후보 추가" style="{white_btn}">[[PLUS]]</button>
<button type="button" onClick="{{{{toggleMenu}}}}" aria-label="더보기" style="{white_btn}">[[MORE]]</button>
<span aria-hidden="true" style="position: absolute; left: 0; right: 0; top: 100%; height: 24px; {{{{barBg}}}}"><span style="position: absolute; inset: 0; border-radius: 36px 36px 0 0; background: [[sheet]]"></span></span>
</div>
{nav(T, theme, 'purpose')}
<sc-if value="{{{{menuOpen}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{closeMenu}}}}" aria-label="메뉴 닫기" style="all: unset; position: absolute; inset: 0; z-index: 8"></button>
<div role="menu" style="position: absolute; right: 20px; top: 104px; z-index: 9; width: 200px; box-sizing: border-box; padding: 6px 0; border-radius: 20px; border: 1px solid [[line]]; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column"><button type="button" onClick="{{{{startEdit}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 52px; padding: 0 18px; display: flex; align-items: center; gap: 12px; font-size: 15px">[[PEN]]편집</button><button type="button" onClick="{{{{openDelete}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 52px; padding: 0 18px; display: flex; align-items: center; gap: 12px; font-size: 15px">[[TRASH]]삭제</button></div></sc-if>
<sc-if value="{{{{addOpen}}}}" hint-placeholder-val="{{{{false}}}}"><div style="position: absolute; inset: 0; z-index: 10; [[scrimDiv]]"></div>
<div style="position: absolute; left: 0; right: 0; top: 96px; bottom: 0; z-index: 11; border-radius: 36px 36px 0 0; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column">
<div style="align-self: center; width: 40px; height: 5px; margin-top: 10px; border-radius: 3px; background: [[handle]]"></div>
<div style="padding: 8px 12px 4px 20px; display: flex; align-items: center; gap: 8px"><h2 style="flex-grow: 1; margin: 0; font-size: 20px; font-weight: 700">후보 추가</h2><button type="button" onClick="{{{{closeAdd}}}}" aria-label="닫기" style="width: 44px; height: 44px; border-radius: 22px; border: none; background: [[tile]]; display: flex; align-items: center; justify-content: center">[[X]]</button></div>
<div style="flex-shrink: 0; padding: 8px 20px 12px; display: flex; gap: 8px; overflow-x: auto">
<sc-if value="{{{{fAll}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{pickAll}}}}" style="{chip(True)}">전체</button></sc-if><sc-if value="{{{{fAllOff}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" onClick="{{{{pickAll}}}}" style="{chip(False)}">전체</button></sc-if>
<sc-if value="{{{{fCustom}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{openCatMode}}}}" style="{chip(True)}">{{{{customLabel}}}}{small_down}</button></sc-if><sc-if value="{{{{fCustomOff}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" onClick="{{{{openCatMode}}}}" style="{chip(False)}">{{{{customLabel}}}}{small_down}</button></sc-if></div>
<sc-if value="{{{{listMode}}}}" hint-placeholder-val="{{{{true}}}}"><div style="flex-grow: 1; min-height: 0; overflow-y: auto; padding: 0 20px 120px; display: flex; flex-direction: column; gap: 4px">
<sc-for list="{{{{pool}}}}" as="p" hint-placeholder-count="5"><button type="button" onClick="{{{{p.toggle}}}}" aria-pressed="{{{{p.on}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 76px; padding: 8px 0; display: flex; align-items: center; gap: 12px">
{thumb("p", 56, 10, 8)}
<span style="flex-grow: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px"><span style="font-size: 15px; word-break: keep-all">{{{{p.name}}}}</span><span style="display: flex; align-items: center; gap: 6px; font-size: 13px; color: [[sub]]"><span style="[[NUM]] font-size: 13px">{{{{p.price}}}}</span>· {{{{p.cat}}}}</span>
<sc-if value="{{{{p.hasOther}}}}" hint-placeholder-val="{{{{false}}}}"><span style="display: flex; align-items: center; gap: 6px; font-size: 12px; color: [[sub]]"><span aria-hidden="true" style="width: 8px; height: 8px; flex-shrink: 0; box-sizing: border-box; border-radius: 4px; {{{{p.dotStyle}}}}"></span>지금 목적 · {{{{p.purpose}}}}</span></sc-if>
<sc-if value="{{{{p.moving}}}}" hint-placeholder-val="{{{{false}}}}"><span style="font-size: 12px; font-weight: 700">이 목적으로 옮겨져요</span></sc-if></span>
<sc-if value="{{{{p.on}}}}" hint-placeholder-val="{{{{false}}}}"><span style="width: 26px; height: 26px; flex-shrink: 0; border-radius: 8px; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; justify-content: center">[[CHECK]]</span></sc-if><sc-if value="{{{{p.off}}}}" hint-placeholder-val="{{{{true}}}}"><span style="width: 26px; height: 26px; flex-shrink: 0; box-sizing: border-box; border-radius: 8px; border: 2px solid [[dash]]"></span></sc-if>
</button></sc-for>
<sc-if value="{{{{listEmpty}}}}" hint-placeholder-val="{{{{false}}}}"><p style="margin: 32px 0; text-align: center; font-size: 14px; color: [[sub]]">이 카테고리에는 추가할 상품이 없어요</p></sc-if>
</div></sc-if>
<sc-if value="{{{{catMode}}}}" hint-placeholder-val="{{{{false}}}}"><div style="flex-grow: 1; min-height: 0; display: flex; border-top: 1px solid [[line]]">
<nav aria-label="상위 카테고리" style="width: 120px; flex-shrink: 0; padding: 6px 0 120px; overflow-y: auto; display: flex; flex-direction: column"><sc-for list="{{{{rail}}}}" as="r" hint-placeholder-count="6"><sc-if value="{{{{r.on}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{r.pick}}}}" aria-current="true" style="all: unset; cursor: pointer; box-sizing: border-box; min-height: 52px; padding: 6px 8px 6px 17px; border-left: 3px solid [[text]]; display: flex; align-items: center; font-size: 14px; line-height: 1.3; font-weight: 700; word-break: keep-all">{{{{r.label}}}}</button></sc-if><sc-if value="{{{{r.off}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" onClick="{{{{r.pick}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; min-height: 52px; padding: 6px 8px 6px 20px; display: flex; align-items: center; font-size: 14px; line-height: 1.3; color: [[sub]]; word-break: keep-all">{{{{r.label}}}}</button></sc-if></sc-for></nav>
<div style="flex-grow: 1; min-width: 0; padding: 10px 16px 120px 8px; overflow-y: auto; display: flex; flex-wrap: wrap; align-content: flex-start; gap: 8px">
<button type="button" onClick="{{{{pickTopAll}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; height: 40px; padding: 0 14px; border-radius: 20px; background: [[tile]]; display: flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 700; white-space: nowrap">{{{{railTopName}}}} 전체<span style="font-size: 14px; [[NUM]] color: [[sub]]">{{{{railTopCount}}}}</span></button>
<sc-for list="{{{{subChips}}}}" as="c" hint-placeholder-count="4"><button type="button" onClick="{{{{c.pick}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; height: 40px; padding: 0 14px; border-radius: 20px; background: [[tile]]; display: flex; align-items: center; gap: 7px; font-size: 14px; white-space: nowrap">{{{{c.name}}}}<span style="font-size: 14px; [[NUM]] color: [[sub]]">{{{{c.count}}}}</span></button></sc-for>
</div></div></sc-if>
<div style="position: absolute; left: 0; right: 0; bottom: 0; padding: 12px 20px 36px; background: [[sheet]]">
<sc-if value="{{{{canAdd}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{closeAdd}}}}" style="width: 100%; height: 56px; border-radius: 28px; border: none; background: [[inv]]; color: [[inv_text]]; font-size: 16px; font-weight: 700">{{{{addLabel}}}}</button></sc-if>
<sc-if value="{{{{cannotAdd}}}}" hint-placeholder-val="{{{{true}}}}"><button type="button" disabled="disabled" style="width: 100%; height: 56px; border-radius: 28px; border: none; background: [[tile]]; color: [[sub]]; font-size: 16px">추가할 상품을 골라 주세요</button></sc-if>
</div>
</div></sc-if>
<sc-if value="{{{{sheetOpen}}}}" hint-placeholder-val="{{{{false}}}}"><div style="position: absolute; inset: 0; z-index: 10; [[scrimDiv]]"></div>
<div style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 11; max-height: 760px; overflow-y: auto; box-sizing: border-box; padding: 10px 20px 36px; border-radius: 36px 36px 0 0; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column; gap: 16px">
<div style="align-self: center; width: 40px; height: 5px; border-radius: 3px; background: [[handle]]"></div>
<sc-if value="{{{{isPick}}}}" hint-placeholder-val="{{{{true}}}}"><div style="display: flex; flex-direction: column; gap: 4px"><h2 style="margin: 0; font-size: 20px; font-weight: 700">구매한 상품이 있나요?</h2><p style="margin: 0; font-size: 14px; color: [[sub]]">선택 사항이에요. 기록에 구매 상품으로 남아요.</p></div>
<div style="display: flex; flex-direction: column">
<sc-for list="{{{{choices}}}}" as="c" hint-placeholder-count="5"><button type="button" onClick="{{{{c.choose}}}}" aria-pressed="{{{{c.on}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 60px; padding: 6px 0; display: flex; align-items: center; gap: 12px"><sc-if value="{{{{c.on}}}}" hint-placeholder-val="{{{{false}}}}">{radio_on}</sc-if><sc-if value="{{{{c.off}}}}" hint-placeholder-val="{{{{true}}}}">{radio_off}</sc-if>{thumb("c", 44, 10, 5)}<span style="flex-grow: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px"><span style="font-size: 15px">{{{{c.name}}}}</span><span style="font-size: 13px; [[NUM]] color: [[sub]]">{{{{c.price}}}}</span></span></button></sc-for>
<button type="button" onClick="{{{{chooseNone}}}}" aria-pressed="{{{{noneOn}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 60px; padding: 6px 0; display: flex; align-items: center; gap: 12px"><sc-if value="{{{{noneOn}}}}" hint-placeholder-val="{{{{true}}}}">{radio_on}</sc-if><sc-if value="{{{{noneOff}}}}" hint-placeholder-val="{{{{false}}}}">{radio_off}</sc-if><span style="font-size: 15px">구매하지 않았어요</span></button>
</div>
<div style="display: flex; gap: 10px"><button type="button" onClick="{{{{close}}}}" style="flex: 1 1 0; height: 56px; border-radius: 28px; border: none; background: [[tile]]; color: [[text]]; font-size: 16px; font-weight: 500">취소</button><button type="button" onClick="{{{{next}}}}" style="flex: 2 1 0; height: 56px; border-radius: 28px; border: none; background: [[inv]]; color: [[inv_text]]; font-size: 16px; font-weight: 700">다음</button></div></sc-if>
<sc-if value="{{{{isConfirm}}}}" hint-placeholder-val="{{{{false}}}}"><div style="display: flex; flex-direction: column; gap: 4px"><h2 style="margin: 0; font-size: 20px; font-weight: 700">비교를 끝낼까요?</h2><p style="margin: 0; font-size: 14px; line-height: 1.5; word-break: keep-all; color: [[sub]]">목적과 후보 5개를 함께 아카이브해요. 기록 상세에서 전체를 되돌릴 수 있어요.</p></div>
<sc-if value="{{{{hasPicked}}}}" hint-placeholder-val="{{{{false}}}}"><div style="display: flex; align-items: center; gap: 12px; padding: 12px; border-radius: 20px; background: [[tile]]">{thumb("pk", 44, 10, 5)}<span style="display: flex; flex-direction: column; gap: 2px"><span style="font-size: 12px; color: [[sub]]">구매 상품</span><span style="font-size: 15px; font-weight: 700">{{{{pk.name}}}}</span></span></div></sc-if>
{group("함께 아카이브할 후보 5개", "toggleList", "listOpen", "listArrow", item_line(True))}
<div style="display: flex; gap: 10px"><button type="button" onClick="{{{{back}}}}" style="flex: 1 1 0; height: 56px; border-radius: 28px; border: none; background: [[tile]]; color: [[text]]; font-size: 16px; font-weight: 500">이전</button><button type="button" onClick="{{{{archive}}}}" style="flex: 2 1 0; height: 56px; border-radius: 28px; border: none; background: [[inv]]; color: [[inv_text]]; font-size: 16px; font-weight: 700">아카이브</button></div></sc-if>
<sc-if value="{{{{isDone}}}}" hint-placeholder-val="{{{{false}}}}"><div style="padding: 20px 0 8px; display: flex; flex-direction: column; align-items: center; gap: 14px; text-align: center">
<span style="width: 64px; height: 64px; border-radius: 28px; background: {{{{doneFace}}}}; color: #1D1D1D; display: flex; align-items: center; justify-content: center"><svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M5 12l5 5L20 7"></path></svg></span>
<h2 style="margin: 0; font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 28px; line-height: 1.0">아카이브했어요</h2>
<p style="margin: 0; font-size: 14px; color: [[sub]]">목적 탭 맨 아래 끝난 비교에서 볼 수 있어요</p>
<a href="FPurposeHome[[s]].dc.html" style="margin-top: 8px; height: 52px; padding: 0 28px; border-radius: 26px; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; font-size: 16px; font-weight: 700">목적으로</a>
<button type="button" onClick="{{{{close}}}}" style="all: unset; cursor: pointer; min-height: 44px; font-size: 14px; color: [[sub]]">처음부터 다시 보기</button>
</div></sc-if>
</div></sc-if>
<sc-if value="{{{{isDelete}}}}" hint-placeholder-val="{{{{false}}}}"><div style="position: absolute; inset: 0; z-index: 12; [[scrimDiv]]"></div>
<div role="dialog" aria-label="목적 삭제 확인" style="position: absolute; left: 24px; right: 24px; top: 50%; transform: translateY(-50%); z-index: 13; box-sizing: border-box; padding: 24px 20px 20px; border-radius: 36px; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column; gap: 12px">
<h2 style="margin: 0; font-size: 20px; font-weight: 700; word-break: keep-all">‘{pname}’ 목적을 삭제할까요?</h2>
<ul style="margin: 0; padding-left: 20px; font-size: 15px; line-height: 1.6; word-break: keep-all; display: flex; flex-direction: column; gap: 2px"><li>후보 5개는 ‘목적 미지정’이 돼요</li><li>상품은 삭제되지 않아요</li><li>되돌릴 수 없어요</li></ul>
{group("목적 미지정이 될 후보 5개", "toggleDelList", "delList", "delArrow", item_line(False))}
<div style="display: flex; gap: 10px; margin-top: 4px"><button type="button" onClick="{{{{closeDelete}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: [[tile]]; color: [[text]]; font-size: 16px; font-weight: 500">취소</button><a href="FPurposeHome[[s]].dc.html" style="flex: 1 1 0; height: 52px; border-radius: 26px; background: [[red]]; color: #FFFFFF; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 700">삭제</a></div>
</div></sc-if>
<sc-if value="{{{{isDiscard}}}}" hint-placeholder-val="{{{{false}}}}"><div style="position: absolute; inset: 0; z-index: 12; [[scrimDiv]]"></div>
<div role="dialog" aria-label="변경 사항 버리기 확인" style="position: absolute; left: 24px; right: 24px; top: 50%; transform: translateY(-50%); z-index: 13; box-sizing: border-box; padding: 24px 20px 20px; border-radius: 36px; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column; gap: 12px">
<h2 style="margin: 0; font-size: 20px; font-weight: 700">변경 사항을 버릴까요?</h2>
<ul style="margin: 0; padding-left: 20px; font-size: 15px; line-height: 1.6"><li>편집한 내용이 저장되지 않아요</li></ul>
<div style="display: flex; gap: 10px; margin-top: 4px"><button type="button" onClick="{{{{keepEditing}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: [[tile]]; color: [[text]]; font-size: 16px; font-weight: 500">계속 편집</button><button type="button" onClick="{{{{discardAll}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: [[inv]]; color: [[inv_text]]; font-size: 16px; font-weight: 700">버리기</button></div>
</div></sc-if>
</div>
"""
    color0 = 1 if empty else 0
    script = f"""class Component extends DCLogic {{
constructor(props) {{ super(props); this.state = Object.assign({{ sheet: null, picked: -1, listOpen: false, menu: false, edit: false, del: false, delList: false, add: false, fk: 'custom', ftop: '디지털·IT', fcat: '헤드폰', catMode: false, railTop: '디지털·IT', sel: [], color: {color0}, icon: '{'i_plane' if empty else 'i_music'}', dirty: false, discard: false }}, {init}); }}
renderVals() {{
const v = this._rv();
const c = !!this.state.collapsed;
return Object.assign(v, {{
barStyle: v.headStyle + '; transition: opacity 220ms ease, transform 220ms ease;' + (c ? 'opacity: 1; transform: none;' : 'opacity: 0; transform: translateY(-8px); pointer-events: none;'),
barBg: v.headStyle,
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
}});
}}
_rv() {{
const s = this.state;
{picker_js(theme, color0, None)}
const items = {json.dumps(items, ensure_ascii=False)};
const pool = {json.dumps(pool, ensure_ascii=False)};
const pickedItem = s.picked >= 0 ? items[s.picked] : null;
const inF = (p) => s.fk === 'all' || (p.top === s.ftop && (!s.fcat || p.cat === s.fcat));
const shownPool = pool.map((p, i) => ({{ ...p, i }})).filter(inF);
const tops = [];
pool.forEach((p) => {{ let t = tops.find((x) => x.name === p.top); if (!t) {{ t = {{ name: p.top, count: 0, cats: [] }}; tops.push(t); }} t.count++; let cc = t.cats.find((x) => x.name === p.cat); if (!cc) {{ cc = {{ name: p.cat, count: 0 }}; t.cats.push(cc); }} cc.count++; }});
const rt = tops.find((t) => t.name === s.railTop) || tops[0];
const customLabel = s.fk === 'custom' ? (s.fcat ? s.fcat : s.ftop + ' 전체') : '카테고리 선택';
const rot = (o) => 'transition: transform 200ms ease; transform: rotate(' + (o ? 180 : 0) + 'deg)';
return {{
hasItems: items.length > 0, isEmpty: items.length === 0, count: items.length,
left: items.filter((_, i) => i % 2 === 0), right: items.filter((_, i) => i % 2 === 1),
headStyle: 'background: ' + palette[s.color].face, doneFace: palette[s.color].face,
view: !s.edit, edit: s.edit,
menuOpen: s.menu, toggleMenu: () => this.setState({{ menu: !s.menu }}), closeMenu: () => this.setState({{ menu: false }}),
startEdit: (e) => {{ const root = e.currentTarget.closest('[data-root]'); const sc = root && root.querySelector('[data-scroll]'); if (sc && sc.scrollTop > 0) {{ this._snap = true; sc.scrollTo({{ top: 0, behavior: 'smooth' }}); clearTimeout(this._st); this._st = setTimeout(() => {{ this._snap = false; this._last = 0; }}, 450); }} this.setState({{ edit: true, menu: false, dirty: false, collapsed: false }}); }},
hi: Object.fromEntries(iconKeys.map((k) => ['k_' + k, s.icon === k])),
cancel: () => s.dirty ? this.setState({{ discard: true }}) : this.setState({{ edit: false }}),
save: () => this.setState({{ edit: false, dirty: false }}),
markDirty: () => {{ if (!s.dirty) this.setState({{ dirty: true }}); }},
isDiscard: s.discard, keepEditing: () => this.setState({{ discard: false }}), discardAll: () => this.setState({{ discard: false, edit: false, dirty: false, color: {color0}, icon: '{'i_plane' if empty else 'i_music'}' }}),
colors: colors, icons: icons,
isDelete: s.del, openDelete: () => this.setState({{ del: true, menu: false, delList: false }}), closeDelete: () => this.setState({{ del: false }}),
delList: s.delList, toggleDelList: () => this.setState({{ delList: !s.delList }}), delArrow: rot(s.delList),
addOpen: s.add, openAdd: () => this.setState({{ add: true, sel: [] }}), closeAdd: () => this.setState({{ add: false }}),
fAll: s.fk === 'all' && !s.catMode, fAllOff: !(s.fk === 'all' && !s.catMode), pickAll: () => this.setState({{ fk: 'all', catMode: false }}),
fCustom: s.fk === 'custom' || s.catMode, fCustomOff: !(s.fk === 'custom' || s.catMode), customLabel: customLabel,
openCatMode: () => this.setState({{ catMode: true, railTop: s.ftop || s.railTop }}),
catMode: s.catMode, listMode: !s.catMode,
rail: tops.map((t) => ({{ label: t.name.replace(/·/g, '·\\u200b'), on: t.name === rt.name, off: t.name !== rt.name, pick: () => this.setState({{ railTop: t.name }}) }})),
railTopName: rt.name, railTopCount: rt.count, pickTopAll: () => this.setState({{ fk: 'custom', ftop: rt.name, fcat: null, catMode: false }}),
subChips: rt.cats.map((cc) => ({{ ...cc, pick: () => this.setState({{ fk: 'custom', ftop: rt.name, fcat: cc.name, catMode: false }}) }})),
listEmpty: shownPool.length === 0,
pool: shownPool.map((p) => ({{ ...p, on: s.sel.includes(p.i), off: !s.sel.includes(p.i), moving: s.sel.includes(p.i) && p.hasOther, toggle: () => this.setState({{ sel: s.sel.includes(p.i) ? s.sel.filter((x) => x !== p.i) : s.sel.concat([p.i]) }}) }})),
canAdd: s.sel.length > 0, cannotAdd: s.sel.length === 0, addLabel: s.sel.length + '개 추가',
sheetOpen: !!s.sheet, isPick: s.sheet === 'pick', isConfirm: s.sheet === 'confirm', isDone: s.sheet === 'done',
choices: items.map((it, i) => ({{ ...it, on: s.picked === i, off: s.picked !== i, choose: () => this.setState({{ picked: i }}) }})),
noneOn: s.picked === -1, noneOff: s.picked !== -1, chooseNone: () => this.setState({{ picked: -1 }}),
hasPicked: !!pickedItem, pk: pickedItem || {{ name: '', bg: '', s: '', f: '' }},
listOpen: s.listOpen, toggleList: () => this.setState({{ listOpen: !s.listOpen }}), listArrow: rot(s.listOpen), all: items,
end: () => this.setState({{ sheet: 'pick' }}), next: () => this.setState({{ sheet: 'confirm' }}),
back: () => this.setState({{ sheet: 'pick' }}), close: () => this.setState({{ sheet: null, picked: -1, listOpen: false }}),
archive: () => this.setState({{ sheet: 'done' }})
}};
}}
}}"""
    return head(title, T) + fill(tpl, T, theme) + tail(script)


BOARDS = [
    ("FPurposeHome", "목적 · 첫 화면"),
    ("FPurposeCreate", "목적 · 새 목적 만들기 시트"),
    ("FPurposeDetail", "목적 · 상세"),
    ("FPurposeDetailEmpty", "목적 · 빈 목적 상세"),
    ("FPurposeEditInPlace", "목적 · 그 자리에서 편집"),
    ("FPurposeDeleteConfirm", "목적 · 삭제 확인"),
    ("FPurposeAddCandidates", "목적 · 후보 추가 시트"),
    ("FPurposeAddCategoryFilter", "목적 · 후보 추가 · 카테고리로 거르기"),
    ("FPurposeFinishPick", "목적 · 비교 끝내기 · 구매 상품 고르기"),
    ("FPurposeFinishConfirm", "목적 · 비교 끝내기 · 아카이브 확인"),
    ("FPurposeFinishDone", "목적 · 비교 끝내기 · 완료"),
]


def main():
    out = os.path.join(sys.argv[1], "project")
    os.makedirs(out, exist_ok=True)
    boards = {}
    for th in "LD":
        nm = THEMES[th]["name"]
        boards[f"FPurposeHome{th}"] = purpose_home(th, f"{nm} 목적 탭 첫 화면", "{}")
        boards[f"FPurposeCreate{th}"] = purpose_home(th, f"{nm} 새 목적 만들기", "{ create: true }")
        boards[f"FPurposeDetail{th}"] = purpose_detail(th, f"{nm} 목적 상세", "{}")
        boards[f"FPurposeDetailEmpty{th}"] = purpose_detail(th, f"{nm} 빈 목적 상세", "{}", empty=True)
        boards[f"FPurposeEditInPlace{th}"] = purpose_detail(th, f"{nm} 목적 편집", "{ edit: true }")
        boards[f"FPurposeDeleteConfirm{th}"] = purpose_detail(th, f"{nm} 목적 삭제 확인", "{ del: true, delList: true }")
        boards[f"FPurposeAddCandidates{th}"] = purpose_detail(th, f"{nm} 후보 추가 시트", "{ add: true, sel: [0, 7], fk: 'all' }")
        boards[f"FPurposeAddCategoryFilter{th}"] = purpose_detail(th, f"{nm} 후보 추가 카테고리로 거르기", "{ add: true, catMode: true }")
        boards[f"FPurposeFinishPick{th}"] = purpose_detail(th, f"{nm} 비교 끝내기 구매 상품 고르기", "{ sheet: 'pick', picked: 0 }")
        boards[f"FPurposeFinishConfirm{th}"] = purpose_detail(th, f"{nm} 비교 끝내기 아카이브 확인", "{ sheet: 'confirm', picked: 0 }")
        boards[f"FPurposeFinishDone{th}"] = purpose_detail(th, f"{nm} 비교 끝내기 완료", "{ sheet: 'done', picked: 0 }")
    for name, html in boards.items():
        with open(os.path.join(out, name + ".dc.html"), "w") as f:
            f.write(html)
    print(" ".join(sorted(boards)))


if __name__ == "__main__":
    main()
