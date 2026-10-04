"""`검토 · 정보 아이콘 색` 페이지의 후보 보드를 만든다.

python3 gen_infocolor.py <out_root>
정보성 아이콘 타일(외부 앱 확인, 공유 수신, 정보 보완 실패, 분석 중, 로그인 안내)에 색을 넣는 세 방식을
라이트·다크 두 열로 한 보드에 그린다.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen_full_home import THEMES, DANGER
from gen_account import svg, I

I = dict(I)
I["warn"] = '<path d="M12 4l9 16H3z"></path><path d="M12 10v4M12 17h.01"></path>'
I["spark"] = '<path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1"></path>'

# 의미: done(완료·저장), wait(대기·로그인 전), offline, external(외부로 나감), fail(실패·주의), busy(분석 중), brand(앱 소개)
OPTIONS = {
    "A": dict(name="A · 목적 6색을 의미별로", note="목적 팔레트에서 의미마다 한 색을 정해요. 면은 밝은 목적 색, 아이콘은 먹색이에요. 라이트·다크 같은 값.",
              L={"done": ("#8ED8B0", "#1D1D1D"), "wait": ("#F9CD61", "#1D1D1D"), "offline": ("#7477FF", "#1D1D1D"), "external": ("#F4A6C6", "#1D1D1D"),
                 "fail": ("#F96857", "#1D1D1D"), "busy": ("#BBF3FE", "#1D1D1D"), "brand": ("#F96857", "#1D1D1D")},
              D=None),
    "B": dict(name="B · 목적과 다른 상태 색(옅은 면 + 짙은 아이콘)", note="목적 6색과 겹치지 않게 채도를 낮춘 상태 색이에요. 면은 옅게, 아이콘을 짙게 칠해 색 면이 목적 카드로 읽히지 않아요.",
              L={"done": ("#DCF1E4", "#1F7A4D"), "wait": ("#FBEFCB", "#8A6200"), "offline": ("#E2E5FA", "#3E48B8"), "external": ("#E2E5FA", "#3E48B8"),
                 "fail": ("#FBE1DB", "#B03A21"), "busy": ("#DDF0F5", "#1C6E82"), "brand": ("#F96857", "#1D1D1D")},
              D={"done": ("#1E3A2B", "#7DD3A5"), "wait": ("#3D3216", "#F2C95A"), "offline": ("#262B4D", "#A3ABF5"), "external": ("#262B4D", "#A3ABF5"),
                 "fail": ("#432420", "#F59A86"), "busy": ("#1C3740", "#86D3E6"), "brand": ("#F96857", "#1D1D1D")}),
    "C": dict(name="C · 앱 색(코랄) 하나로", note="앱 아이콘과 같은 코랄 면 하나로 모든 정보 아이콘을 칠해요. 의미는 아이콘 모양과 문구가 맡아요.",
              L={k: ("#F96857", "#1D1D1D") for k in ("done", "wait", "offline", "external", "fail", "busy", "brand")},
              D=None),
}


def column(theme, pal):
    T = THEMES[theme]
    tile = lambda key, icon, size=44, r=14, isz=20: f'<span style="width: {size}px; height: {size}px; flex-shrink: 0; border-radius: {r}px; background: {pal[key][0]}; color: {pal[key][1]}; display: flex; align-items: center; justify-content: center">{svg(I[icon], isz, "2")}</span>'
    lab = lambda t: f'<span style="font-size: 12px; font-weight: 500; color: {T["sub"]}">{t}</span>'
    share = lambda key, icon, title, sub: f'''<div style="padding: 16px; border-radius: 28px; background: {T["card"]}; display: flex; align-items: center; gap: 14px">{tile(key, icon, 48)}<span style="display: flex; flex-direction: column; gap: 6px"><span style="font-family: 'Do Hyeon', sans-serif; font-size: 20px; line-height: 1.0">{title}</span><span style="font-size: 13px; color: {T["sub"]}">{sub}</span></span></div>'''
    check = "".join(f'<li style="display: flex; align-items: center; gap: 12px">{tile("brand", "check", 32, 10, 16)}<span>{t}</span></li>' for t in ("공유하면 바로 저장돼요", "로그인하면 정보를 가져와요"))
    return f'''<div style="flex: 1 1 0; min-width: 0; box-sizing: border-box; padding: 28px 24px; background: {T["bg"]}; color: {T["text"]}; display: flex; flex-direction: column; gap: 14px">
<span style="font-size: 14px; font-weight: 700">{T["name"]}</span>
{lab("외부 앱 열기 확인")}
<div style="padding: 24px 20px 20px; border-radius: 36px; background: {T["sheet"]}; display: flex; flex-direction: column; gap: 12px">{tile("external", "phone", 48)}<span style="font-size: 20px; font-weight: 700">외부 앱을 열까요?</span><ul style="margin: 0; padding-left: 20px; font-size: 15px; line-height: 1.6"><li>결제 앱이 열려요</li><li>직접 누르지 않았다면 취소해 주세요</li></ul>
<div style="display: flex; gap: 10px"><span style="flex: 1 1 0; height: 48px; border-radius: 24px; background: {T["tile"]}; display: flex; align-items: center; justify-content: center; font-size: 15px">취소</span><span style="flex: 1 1 0; height: 48px; border-radius: 24px; background: {T["inv"]}; color: {T["inv_text"]}; display: flex; align-items: center; justify-content: center; font-size: 15px; font-weight: 700">열기</span></div></div>
{lab("공유 수신")}
{share("done", "check", "위시리스트에 저장했어요", "정보를 가져오는 중이에요")}
{share("wait", "clock", "이 기기에 저장했어요", "로그인하면 정보를 가져와요")}
{share("offline", "cloudoff", "이 기기에 저장했어요", "다음에 앱을 열면 보내요")}
{lab("상품 · 정보 보완 실패 / 분석 중")}
<div style="padding: 16px; border-radius: 28px; background: {T["card"]}; display: flex; align-items: center; gap: 12px">{tile("fail", "warn")}<span style="display: flex; flex-direction: column; gap: 4px"><span style="font-size: 15px; font-weight: 700">상품 정보를 가져오지 못했어요</span><span style="font-size: 12px; color: {T["sub"]}">coupang.com</span></span></div>
<div style="padding: 16px; border-radius: 28px; background: {T["card"]}; display: flex; align-items: center; gap: 12px">{tile("busy", "spark")}<span style="display: flex; flex-direction: column; gap: 4px"><span style="font-size: 15px; font-weight: 700">상품 정보 추출 중</span><span style="font-size: 12px; color: {T["sub"]}">29cm.co.kr</span></span></div>
{lab("로그인 안내")}
<div style="display: flex; align-items: center; gap: 14px">{tile("brand", "heart", 56, 18, 26)}<ul style="margin: 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 10px; font-size: 14px">{check}</ul></div>
</div>'''


def board(key):
    o = OPTIONS[key]
    left = column("L", o["L"])
    right = column("D", o["D"] or o["L"])
    return f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>정보 아이콘 색 후보 {key}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Do+Hyeon&amp;family=IBM+Plex+Sans+KR:wght@400;500;700&amp;display=swap">
<style>
body{{margin:0}}
</style>
</helmet>
<div style="width: 860px; height: 1240px; box-sizing: border-box; background: #E7E5DF; color: #1D1D1D; font-family: 'IBM Plex Sans KR', sans-serif; display: flex; flex-direction: column">
<div style="padding: 28px 28px 20px; display: flex; flex-direction: column; gap: 6px"><span style="font-size: 22px; font-weight: 700">{o["name"]}</span><span style="font-size: 14px; line-height: 1.5; color: #5C5B57">{o["note"]}</span></div>
<div style="flex-grow: 1; display: flex">{left}{right}</div>
</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":860,"height":1240}}}}'>
class Component extends DCLogic {{
renderVals() {{ return {{}}; }}
}}
</script>
</body>
</html>
'''


def main():
    out = os.path.join(sys.argv[1], "project")
    os.makedirs(out, exist_ok=True)
    for k in OPTIONS:
        with open(os.path.join(out, f"PickInfo{k}.dc.html"), "w") as f:
            f.write(board(k))
    print("PickInfoA PickInfoB PickInfoC")


if __name__ == "__main__":
    main()
