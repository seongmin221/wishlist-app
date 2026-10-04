"""전체 화면 · 로그인·설정·공유 수신·웹뷰 묶음 보드를 만든다.

python3 gen_account.py <out_root>
템플릿 안의 [[키]]는 테마 값으로, {{키}}는 보드 런타임 값으로 채워진다.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen_full_home import THEMES, head, tail
from gen_category import nav
from gen_purpose import fill


def svg(paths, size=20, sw="1.8"):
    return f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="{sw}">{paths}</svg>'


I = {
    "heart": '<path d="M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z"></path>',
    "check": '<path d="M5 12l5 5L20 7"></path>',
    "clock": '<circle cx="12" cy="12" r="9"></circle><path d="M12 7v5l3 2"></path>',
    "cloudoff": '<path d="M7 18h10a4 4 0 0 0 1.6-.3M20.5 13.5A4 4 0 0 0 17.5 10 6 6 0 0 0 9 5.6M5.7 9.7A4.5 4.5 0 0 0 7 18M3 3l18 18"></path>',
    "user": '<circle cx="12" cy="8" r="4"></circle><path d="M4 20c1.5-4 4.5-6 8-6s6.5 2 8 6"></path>',
    "gear": '<circle cx="12" cy="12" r="3"></circle><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1"></path>',
    "lock": '<rect x="5" y="11" width="14" height="9" rx="2"></rect><path d="M8 11V8a4 4 0 0 1 8 0v3"></path>',
    "back": '<path d="M15 5l-7 7 7 7"></path>',
    "fwd": '<path d="M9 5l7 7-7 7"></path>',
    "refresh": '<path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"></path>',
    "share": '<path d="M12 3v12M7 8l5-5 5 5M5 14v5a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-5"></path>',
    "ext": '<path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"></path>',
    "copy": '<rect x="8" y="8" width="12" height="12" rx="2"></rect><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"></path>',
    "apps": '<rect x="4" y="4" width="7" height="7" rx="2"></rect><rect x="13" y="4" width="7" height="7" rx="2"></rect><rect x="4" y="13" width="7" height="7" rx="2"></rect><rect x="13" y="13" width="7" height="7" rx="2"></rect>',
    "phone": '<rect x="7" y="3" width="10" height="18" rx="2"></rect><path d="M11 18h2"></path>',
    "x": '<path d="M6 6l12 12M18 6L6 18"></path>',
    "globe": '<circle cx="12" cy="12" r="9"></circle><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"></path>',
    "trash": '<path d="M5 7h14M10 7V5h4v2M7 7l1 13h8l1-13"></path>',
    "info": '<circle cx="12" cy="12" r="9"></circle><path d="M12 11v5M12 8h.01"></path>',
    "pen": '<path d="M4 20h4L19 9l-4-4L4 16z"></path><path d="M13 7l4 4"></path>',
}

def on_attr(on):
    return ' onClick="{{' + on + '}}"' if on else ''


def dis_attr(d):
    return ' disabled="disabled"' if d else ''


TILE = lambda icon, size=44, r=14, bg="[[icon_tile]]": f'<span style="width: {size}px; height: {size}px; flex-shrink: 0; border-radius: {r}px; background: {bg}; color: [[text]]; display: flex; align-items: center; justify-content: center">{svg(I[icon])}</span>'


def dialog(key, label, title, bullets, cancel_on, ok_html, icon=None, status="external"):
    lis = "".join(f"<li>{b}</li>" for b in bullets)
    head_icon = (f'<span style="width: 48px; height: 48px; flex-shrink: 0; border-radius: 14px; background: [[st_{status}_bg]]; color: [[st_{status}_fg]]; display: flex; align-items: center; justify-content: center">{svg(I[icon])}</span>') if icon else ""
    return f"""<sc-if value="{{{{{key}}}}}" hint-placeholder-val="{{{{false}}}}"><div style="position: absolute; inset: 0; z-index: 20; [[scrimDiv]]"></div>
<div role="dialog" aria-label="{label}" style="position: absolute; left: 24px; right: 24px; top: 50%; transform: translateY(-50%); z-index: 21; box-sizing: border-box; padding: 24px 20px 20px; border-radius: 36px; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column; gap: 12px">
{head_icon}<h2 style="margin: 0; font-size: 20px; font-weight: 700">{title}</h2>
<ul style="margin: 0; padding-left: 20px; font-size: 15px; line-height: 1.6; word-break: keep-all; display: flex; flex-direction: column; gap: 2px">{lis}</ul>
<div style="display: flex; gap: 10px; margin-top: 4px"><button type="button" onClick="{{{{{cancel_on}}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; background: [[tile]]; color: [[text]]; font-size: 16px; font-weight: 500">취소</button>{ok_html}</div>
</div></sc-if>"""


def ok_btn(label, on, red=False):
    look = "background: [[red]]; color: #FFFFFF" if red else "background: [[inv]]; color: [[inv_text]]"
    return f'<button type="button" onClick="{{{{{on}}}}}" style="flex: 1 1 0; height: 52px; border-radius: 26px; border: none; {look}; font-size: 16px; font-weight: 700">{label}</button>'


# ---------------------------------------------------------------- 로그인 안내

def login_board(theme):
    T = THEMES[theme]
    li = lambda t: f'<li style="display: flex; align-items: center; gap: 12px"><span style="width: 32px; height: 32px; flex-shrink: 0; border-radius: 10px; background: #F96857; color: #1D1D1D; display: flex; align-items: center; justify-content: center">{svg(I["check"])}</span><span style="word-break: keep-all">{t}</span></li>'
    tpl = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: [[bg]]; color: [[text]]; font-family: 'IBM Plex Sans KR', sans-serif">
<div style="position: absolute; inset: 0; padding: 0 24px 40px; display: flex; flex-direction: column">
<div style="flex-grow: 1; display: flex; flex-direction: column; justify-content: center; gap: 24px">
<span style="width: 64px; height: 64px; border-radius: 20px; background: #F96857; color: #1D1D1D; display: flex; align-items: center; justify-content: center">{svg(I["heart"], 30)}</span>
<h1 style="margin: 0; font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 28px; line-height: 1.2">사고 싶은 상품을<br>링크 하나로 모아 두세요</h1>
<ul style="margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 12px; font-size: 15px; line-height: 1.5">{li("공유하면 바로 저장돼요")}{li("로그인하면 상품 정보를 알아서 가져와요")}{li("다른 기기에서도 같은 목록을 볼 수 있어요")}</ul>
</div>
<div style="display: flex; flex-direction: column; gap: 10px">
<a href="FHome[[s]].dc.html" style="height: 56px; border-radius: 28px; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 700">Apple로 계속하기</a>
<a href="FHome[[s]].dc.html" style="height: 56px; border-radius: 28px; background: [[card]]; color: [[text]]; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 500">Google로 계속하기</a>
<a href="FHomeLoggedOut[[s]].dc.html" style="min-height: 48px; display: flex; align-items: center; justify-content: center; font-size: 15px; color: [[sub]]">나중에 하기</a>
</div>
</div>
</div>
"""
    return head(f"{T['name']} 로그인 안내", T) + fill(tpl, T, theme) + tail("class Component extends DCLogic {\nrenderVals() { return {}; }\n}")


# ---------------------------------------------------------------- 홈 로그인 전

def home_logged_out(theme):
    T = THEMES[theme]
    rows = "".join(f"""<div style="display: flex; align-items: center; gap: 12px; padding: 10px; border-radius: 20px; background: [[tile]]">{TILE("clock", 44, 14, "[[card]]")}<span style="flex-grow: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px"><span style="font-size: 14px; font-weight: 500">{d}</span><span style="[[lbl12:sub]]">{w} · 이 기기에만 있어요</span></span><a href="#" style="min-height: 44px; padding: 0 6px; display: flex; align-items: center; gap: 4px; font-size: 13px">원본{svg(I["ext"], 14)}</a></div>"""
                   for d, w in (("musinsa.com", "2일 전 저장"), ("coupang.com", "어제 저장"), ("ohou.se", "방금 저장")))
    tpl = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: [[bg]]; color: [[text]]; font-family: 'IBM Plex Sans KR', sans-serif">
<div style="position: absolute; inset: 0; overflow-y: auto">
<div style="padding: 56px 20px 140px; display: flex; flex-direction: column; gap: 32px">
<header style="display: flex; align-items: flex-start; justify-content: space-between">
<div style="display: flex; flex-direction: column; gap: 6px"><h1 style="margin: 0; font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 28px; line-height: 1.0">홈</h1><span style="[[lbl13:sub]]">로그인 전</span></div>
<a href="FSettingsLoggedOut[[s]].dc.html" aria-label="설정" style="width: 44px; height: 44px; border-radius: 22px; background: [[card]]; display: flex; align-items: center; justify-content: center">{svg(I["gear"])}</a>
</header>
<section style="padding: 20px; border-radius: 28px; background: [[card]]; display: flex; flex-direction: column; gap: 14px">
<div style="display: flex; align-items: center; gap: 12px">{TILE("user")}<h2 style="margin: 0; font-size: 17px; font-weight: 700">로그인하면 정보를 가져와요</h2></div>
<ul style="margin: 0; padding-left: 20px; font-size: 14px; line-height: 1.6; word-break: keep-all; color: [[sub]]"><li>저장한 링크의 이름·사진·가격을 채워요</li><li>다른 기기에서도 볼 수 있어요</li></ul>
<a href="FLogin[[s]].dc.html" style="height: 52px; border-radius: 26px; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 700">로그인</a>
</section>
<section style="display: flex; flex-direction: column; gap: 12px">
<h2 style="margin: 0; font-size: 13px; font-weight: 500; color: [[sub]]">할 일</h2>
<div style="border-radius: 28px; background: [[card]]; overflow: hidden">
<div style="padding: 16px 6px 16px 16px; display: flex; align-items: center; gap: 4px">
<button type="button" onClick="{{{{toggle}}}}" style="all: unset; cursor: pointer; flex-grow: 1; min-width: 0; min-height: 44px; display: flex; align-items: center; gap: 14px">{TILE("clock")}<span style="flex-grow: 1; display: flex; flex-direction: column; gap: 2px; min-width: 0"><span style="font-size: 17px; font-weight: 700">분석 대기</span><span style="font-size: 13px; color: [[sub]]; word-break: keep-all">로그인하면 바로 정보를 가져와요</span></span><span style="font-size: 26px; [[NUM]]">3</span></button>
<button type="button" onClick="{{{{toggle}}}}" aria-label="{{{{lbl}}}}" aria-expanded="{{{{open}}}}" style="width: 44px; height: 44px; flex-shrink: 0; border: none; border-radius: 22px; background: transparent; color: [[text]]; display: flex; align-items: center; justify-content: center"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" style="{{{{rot}}}}"><path d="M6 9l6 6 6-6"></path></svg></button>
</div>
<sc-if value="{{{{open}}}}" hint-placeholder-val="{{{{false}}}}"><div style="padding: 0 12px 12px; display: flex; flex-direction: column; gap: 8px">{rows}</div></sc-if>
</div>
</section>
</div>
</div>
{nav(T, theme, 'home')}
</div>
"""
    script = """class Component extends DCLogic {
constructor(props) { super(props); this.state = { open: false }; }
renderVals() { const o = this.state.open; return { open: o, lbl: o ? '접기' : '펼치기', rot: 'transition: transform 200ms ease; transform: rotate(' + (o ? 180 : 0) + 'deg)', toggle: () => this.setState({ open: !o }) }; }
}"""
    return head(f"{T['name']} 홈 로그인 전", T) + fill(tpl, T, theme) + tail(script)


# ---------------------------------------------------------------- 설정

def settings_board(theme, title, init):
    T = THEMES[theme]
    row = lambda label, sub="", right=f'{svg(I["fwd"], 16)}', on=None: (
        f'<button type="button"{on_attr(on)} style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 56px; padding: 8px 14px 8px 16px; display: flex; align-items: center; gap: 12px">'
        f'<span style="flex-grow: 1; display: flex; flex-direction: column; gap: 2px"><span style="font-size: 16px">{label}</span>{sub}</span>{right}</button>')
    group = lambda h, inner: f'<section style="display: flex; flex-direction: column; gap: 8px"><h2 style="margin: 0; padding: 0 4px; font-size: 13px; font-weight: 500; color: [[sub]]">{h}</h2><div style="border-radius: 28px; background: [[card]]; padding: 4px 0; display: flex; flex-direction: column">{inner}</div></section>'
    account = f"""<sc-if value="{{{{logged}}}}" hint-placeholder-val="{{{{true}}}}"><div style="min-height: 72px; padding: 8px 16px; display: flex; align-items: center; gap: 12px">{TILE("user", 44, 22)}<span style="flex-grow: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px"><span style="font-size: 16px; font-weight: 700">user@example.com</span><span style="[[lbl12:sub]]">Google로 로그인됨</span></span></div>{row("로그아웃", on="openOut")}</sc-if>
<sc-if value="{{{{loggedOut}}}}" hint-placeholder-val="{{{{false}}}}"><div style="padding: 14px 16px 16px; display: flex; flex-direction: column; gap: 14px"><span style="font-size: 15px; line-height: 1.5; word-break: keep-all">로그인하면 저장한 상품의 정보를 가져오고 다른 기기에서도 볼 수 있어요</span><a href="FLogin[[s]].dc.html" style="height: 52px; border-radius: 26px; background: [[inv]]; color: [[inv_text]]; display: flex; align-items: center; justify-content: center; font-size: 16px; font-weight: 700">로그인</a></div></sc-if>"""
    wv = row("웹뷰 데이터 삭제", '<span style="font-size: 13px; color: [[sub]]">{{wvSub}}</span>', on="openWv")
    info = row("버전", right='<span style="font-size: 14px; [[NUM]] color: [[sub]]">1.0.0</span>') + row("오픈소스 라이선스")
    tpl = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: [[bg]]; color: [[text]]; font-family: 'IBM Plex Sans KR', sans-serif">
<div style="position: absolute; inset: 0; overflow-y: auto">
<div style="height: 44px"></div>
<header style="position: sticky; top: 0; z-index: 1; background: [[bg]]; padding: 12px 20px 14px; display: flex; align-items: center; gap: 12px"><a href="{{{{homeHref}}}}" aria-label="뒤로" style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 22px; background: [[card]]; display: flex; align-items: center; justify-content: center">{svg(I["back"])}</a><h1 style="margin: 0; font-size: 22px; font-weight: 700">설정</h1></header>
<div style="padding: 8px 20px 60px; display: flex; flex-direction: column; gap: 24px">
{group("계정", account)}{group("원본 링크", wv)}{group("앱 정보", info)}
</div>
</div>
{dialog("isOut", "로그아웃할까요?", "로그아웃할까요?", ["이 기기의 로그인만 풀려요", "저장한 상품은 계정에 남아요", "다시 로그인하면 이어서 볼 수 있어요"], "closeOut", ok_btn("로그아웃", "doOut"))}
{dialog("isWv", "웹뷰 데이터를 삭제할까요?", "웹뷰 데이터를 삭제할까요?", ["쇼핑몰 로그인이 풀려요", "방문 기록과 쿠키가 지워져요", "저장한 상품은 그대로예요", "되돌릴 수 없어요"], "closeWv", ok_btn("삭제", "doWv", red=True))}
</div>
"""
    s = theme
    script = f"""class Component extends DCLogic {{
constructor(props) {{ super(props); this.state = Object.assign({{ logged: true, out: false, wv: false, wvDone: false }}, {init}); }}
renderVals() {{
const s = this.state;
return {{
logged: s.logged, loggedOut: !s.logged, homeHref: s.logged ? 'FHome{s}.dc.html' : 'FHomeLoggedOut{s}.dc.html',
isOut: s.out, openOut: () => this.setState({{ out: true }}), closeOut: () => this.setState({{ out: false }}), doOut: () => this.setState({{ out: false, logged: false }}),
isWv: s.wv, openWv: () => this.setState({{ wv: true }}), closeWv: () => this.setState({{ wv: false }}), doWv: () => this.setState({{ wv: false, wvDone: true }}),
wvSub: s.wvDone ? '방금 삭제했어요' : '쇼핑몰 로그인과 방문 기록'
}};
}}
}}"""
    return head(title, T) + fill(tpl, T, theme) + tail(script)


# ---------------------------------------------------------------- 공유 수신

def share_board(theme, title, icon, msg, sub, status):
    T = THEMES[theme]
    shop = {"L": ("#E9E9E9", "#DADADA", "#D2D2D2", "#C8C8C8"), "D": ("#2A2A2A", "#333333", "#3B3B3B", "#444444")}[theme]
    tpl = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: {shop[0]}; color: [[text]]; font-family: 'IBM Plex Sans KR', sans-serif">
<div aria-hidden="true" style="position: absolute; inset: 0">
<div style="height: 100px; background: {shop[1]}; display: flex; align-items: flex-end; padding: 0 16px 12px; font-size: 13px; color: [[sub]]">다른 앱 · 쇼핑몰 상품 페이지</div>
<div style="padding: 16px; display: flex; flex-direction: column; gap: 12px"><div style="height: 300px; border-radius: 12px; background: {shop[2]}"></div><div style="width: 70%; height: 18px; border-radius: 6px; background: {shop[2]}"></div><div style="width: 40%; height: 22px; border-radius: 6px; background: {shop[3]}"></div></div>
</div>
<div role="status" style="position: absolute; left: 16px; right: 16px; bottom: 40px; z-index: 5; box-sizing: border-box; padding: 16px; border-radius: 28px; background: [[card]]; display: flex; align-items: center; gap: 14px">
<span style="width: 48px; height: 48px; flex-shrink: 0; border-radius: 14px; background: [[st_{status}_bg]]; color: [[st_{status}_fg]]; display: flex; align-items: center; justify-content: center">{svg(I[icon], 22, "2")}</span>
<span style="flex-grow: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px"><span style="font-family: 'Do Hyeon', sans-serif; font-weight: 400; font-size: 20px; line-height: 1.0">{msg}</span><span style="font-size: 13px; line-height: 1.45; word-break: keep-all; color: [[sub]]">{sub}</span></span>
</div>
</div>
"""
    return head(title, T) + fill(tpl, T, theme) + tail("class Component extends DCLogic {\nrenderVals() { return {}; }\n}")


# ---------------------------------------------------------------- 원본 링크 웹뷰

def webview_board(theme, title, init):
    T = THEMES[theme]
    page = {"L": ("#FAFAFA", "#D9D9D9", "#E4E4E4", "#CFCFCF"), "D": ("#151515", "#2E2E2E", "#262626", "#333333")}[theme]
    ib = lambda icon, label, on=None, href=None, disabled=False: (
        f'<a href="{href}" aria-label="{label}" style="width: 44px; height: 44px; border-radius: 22px; display: flex; align-items: center; justify-content: center; color: [[text]]">{svg(I[icon])}</a>' if href else
        f'<button type="button"{on_attr(on)}{dis_attr(disabled)} aria-label="{label}" style="all: unset; cursor: pointer; width: 44px; height: 44px; border-radius: 22px; display: flex; align-items: center; justify-content: center; color: {"[[dash]]" if disabled else "[[text]]"}">{svg(I[icon])}</button>')
    sheet_row = lambda icon, label, on="closeSheet": f'<button type="button" onClick="{{{{{on}}}}}" style="all: unset; cursor: pointer; box-sizing: border-box; width: 100%; min-height: 60px; display: flex; align-items: center; gap: 14px; font-size: 16px">{TILE(icon, 40, 14, "[[tile]]")}{label}</button>'
    tpl = f"""<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background: [[bg]]; color: [[text]]; font-family: 'IBM Plex Sans KR', sans-serif">
<header style="position: absolute; left: 0; right: 0; top: 0; z-index: 4; padding: 50px 12px 8px; background: [[bg]]; display: flex; align-items: center; gap: 8px">
<a href="FProductDetail[[s]].dc.html" aria-label="닫기" style="width: 44px; height: 44px; flex-shrink: 0; border-radius: 22px; background: [[card]]; display: flex; align-items: center; justify-content: center">{svg(I["x"])}</a>
<div style="flex-grow: 1; min-width: 0; display: flex; flex-direction: column; align-items: center; gap: 2px; margin-right: 52px">
<span style="display: flex; align-items: center; gap: 4px; font-size: 14px; font-weight: 700">{svg(I["lock"], 13)}www.musinsa.com</span>
<span style="[[lbl12:sub]]">{{{{pageName}}}}</span>
</div>
</header>
<div aria-hidden="true" style="position: absolute; left: 0; right: 0; top: 102px; z-index: 5; height: 2px; background: [[line]]"><div style="width: 62%; height: 2px; background: [[text]]"></div></div>
<div style="position: absolute; left: 0; right: 0; top: 104px; bottom: 84px; overflow-y: auto; background: {page[0]}">
<div style="padding: 0 0 24px; display: flex; flex-direction: column; gap: 14px">
<div style="height: 360px; background: {page[1]}; display: flex; align-items: center; justify-content: center; font-size: 13px; color: [[sub]]">쇼핑몰 웹페이지 · {{{{pageName}}}}</div>
<div style="padding: 0 16px; display: flex; flex-direction: column; gap: 10px">
<div style="width: 40%; height: 12px; border-radius: 6px; background: {page[2]}"></div><div style="width: 85%; height: 18px; border-radius: 6px; background: {page[2]}"></div><div style="width: 60%; height: 18px; border-radius: 6px; background: {page[2]}"></div><div style="width: 35%; height: 22px; border-radius: 6px; background: {page[3]}"></div>
<div style="height: 8px"></div><div style="width: 100%; height: 48px; border-radius: 10px; background: {page[3]}"></div>
<button type="button" onClick="{{{{go}}}}" style="all: unset; cursor: pointer; margin-top: 8px; min-height: 44px; display: flex; align-items: center; justify-content: space-between; font-size: 14px; color: [[sub]]">페이지 안의 다른 링크{svg(I["fwd"], 16)}</button>
</div>
</div>
</div>
<nav aria-label="웹 탐색" style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 4; padding: 8px 12px 32px; background: [[bg]]; display: flex; align-items: center; justify-content: space-between">
<div style="display: flex; gap: 4px">
<sc-if value="{{{{canBack}}}}" hint-placeholder-val="{{{{false}}}}">{ib("back", "뒤로", on="back")}</sc-if><sc-if value="{{{{noBack}}}}" hint-placeholder-val="{{{{true}}}}">{ib("back", "뒤로 (이전 페이지가 없으면 닫기)", href="FProductDetail[[s]].dc.html")}</sc-if>
<sc-if value="{{{{canFwd}}}}" hint-placeholder-val="{{{{false}}}}">{ib("fwd", "앞으로", on="forward")}</sc-if><sc-if value="{{{{noFwd}}}}" hint-placeholder-val="{{{{true}}}}">{ib("fwd", "앞으로", disabled=True)}</sc-if>
</div>
<div style="display: flex; gap: 4px">{ib("refresh", "새로고침")}{ib("share", "공유", on="openSheet")}</div>
</nav>
<sc-if value="{{{{sheetOpen}}}}" hint-placeholder-val="{{{{false}}}}"><button type="button" onClick="{{{{closeSheet}}}}" aria-label="닫기" style="all: unset; position: absolute; inset: 0; z-index: 10; [[scrimDiv]]"></button>
<div style="position: absolute; left: 0; right: 0; bottom: 0; z-index: 11; box-sizing: border-box; padding: 10px 20px 36px; border-radius: 36px 36px 0 0; background: [[sheet]]; color: [[text]]; display: flex; flex-direction: column; gap: 4px">
<div style="align-self: center; width: 40px; height: 5px; margin-bottom: 8px; border-radius: 3px; background: [[handle]]"></div>
{sheet_row("globe", "외부 브라우저로 열기")}{sheet_row("copy", "링크 복사")}{sheet_row("apps", "다른 앱으로 공유")}
</div></sc-if>
{dialog("extOpen", "외부 앱 열기 확인", "외부 앱을 열까요?", ["결제 앱이 열려요", "직접 누르지 않았다면 취소해 주세요"], "closeExt", ok_btn("열기", "closeExt"), icon="phone")}
</div>
"""
    script = f"""class Component extends DCLogic {{
constructor(props) {{ super(props); this.state = Object.assign({{ hist: 0, fwd: 0, sheet: false, ext: false }}, {init}); }}
renderVals() {{
const s = this.state;
const pages = ['상품 페이지', '다른 상품 페이지', '리뷰 페이지', '장바구니 페이지'];
return {{
pageName: pages[Math.min(s.hist, pages.length - 1)],
canBack: s.hist > 0, noBack: s.hist === 0, canFwd: s.fwd > 0, noFwd: s.fwd === 0,
go: () => this.setState({{ hist: s.hist + 1, fwd: 0 }}),
back: () => this.setState({{ hist: s.hist - 1, fwd: s.fwd + 1 }}),
forward: () => this.setState({{ hist: s.hist + 1, fwd: s.fwd - 1 }}),
sheetOpen: s.sheet, openSheet: () => this.setState({{ sheet: true }}), closeSheet: () => this.setState({{ sheet: false }}),
extOpen: s.ext, closeExt: () => this.setState({{ ext: false }})
}};
}}
}}"""
    return head(title, T) + fill(tpl, T, theme) + tail(script)


BOARDS = [
    ("FLogin", "로그인 안내 (첫 실행)"),
    ("FHomeLoggedOut", "홈 · 로그인 전"),
    ("FSettings", "설정"),
    ("FSettingsLoggedOut", "설정 · 로그인 전"),
    ("FSettingsLogout", "설정 · 로그아웃 확인"),
    ("FSettingsWebviewClear", "설정 · 웹뷰 데이터 삭제 확인"),
    ("FShareSaved", "공유 수신 · 저장 완료"),
    ("FShareSavedLocal", "공유 수신 · 로그인 전"),
    ("FShareSavedOffline", "공유 수신 · 오프라인"),
    ("FWebView", "웹뷰 · 원본 링크"),
    ("FWebViewShare", "웹뷰 · 공유 시트"),
    ("FWebViewExternal", "웹뷰 · 외부 앱 열기 확인"),
]


def main():
    out = os.path.join(sys.argv[1], "project")
    os.makedirs(out, exist_ok=True)
    b = {}
    for th in "LD":
        nm = THEMES[th]["name"]
        b[f"FLogin{th}"] = login_board(th)
        b[f"FHomeLoggedOut{th}"] = home_logged_out(th)
        b[f"FSettings{th}"] = settings_board(th, f"{nm} 설정", "{}")
        b[f"FSettingsLoggedOut{th}"] = settings_board(th, f"{nm} 설정 로그인 전", "{ logged: false }")
        b[f"FSettingsLogout{th}"] = settings_board(th, f"{nm} 로그아웃 확인", "{ out: true }")
        b[f"FSettingsWebviewClear{th}"] = settings_board(th, f"{nm} 웹뷰 데이터 삭제 확인", "{ wv: true }")
        b[f"FShareSaved{th}"] = share_board(th, f"{nm} 공유 수신 저장 완료", "check", "위시리스트에 저장했어요", "정보를 가져오는 중이에요", "done")
        b[f"FShareSavedLocal{th}"] = share_board(th, f"{nm} 공유 수신 로그인 전", "clock", "이 기기에 저장했어요", "로그인하면 정보를 가져와요", "wait")
        b[f"FShareSavedOffline{th}"] = share_board(th, f"{nm} 공유 수신 오프라인", "cloudoff", "이 기기에 저장했어요", "다음에 앱을 열면 보내요", "offline")
        b[f"FWebView{th}"] = webview_board(th, f"{nm} 원본 링크 웹뷰", "{}")
        b[f"FWebViewShare{th}"] = webview_board(th, f"{nm} 웹뷰 공유 시트", "{ sheet: true }")
        b[f"FWebViewExternal{th}"] = webview_board(th, f"{nm} 웹뷰 외부 앱 열기 확인", "{ ext: true }")
    for name, html in b.items():
        with open(os.path.join(out, name + ".dc.html"), "w") as f:
            f.write(html)
    print(" ".join(sorted(b)))


if __name__ == "__main__":
    main()
