# 후보 추가 선택 창·필터·시트 등 공통 조각. 다른 생성기가 exec로 불러 쓴다.
import re, json
import os
SRC = os.environ.get('CANVAS_SRC', 'source/project') + '/'
src = open(SRC + 'EP-D.dc.html').read()
pd = open(SRC + 'PD-FINAL.dc.html').read()
helmet = src[src.index('<helmet>'):src.index('</helmet>')+len('</helmet>')]
nav = re.search(r'<nav aria-label="주요 메뉴".*?</nav>', src, re.S).group(0)
topbar = re.search(r'<div style="height: 44px; display: flex; align-items: center; justify-content: space-between">.*?</button></div>', pd, re.S).group(0).replace(' onClick="{{ openMenu }}"','')
I = json.loads(re.search(r'const I = (\[.*?\]);', pd).group(1))
H = {img:int(h) for h,img in re.findall(r'width: 187px; height: (\d+)px;[^>]*><img src="([^"]+)"',pd)}
CAT = ['신발','하의','신발','하의','패션 소품','가방','패션 소품','하의','신발','아우터','상의','패션 소품']
SHOP = ['Mizuno','EQL','Goodrunner','8Division','Goodrunner','Kaptain Sunshine','Goodrunner','KREAM','Goodrunner','KREAM','Goodrunner','Goodrunner']
for k,it in enumerate(I): it.update(h=H[it['img']], c=CAT[k], s=SHOP[k], i=k)
CATS = ['아우터','상의','하의','신발','가방','패션 소품']
COLOR = {'신발':'#D9EC9A','하의':'#BFD3F2','상의':'#F6C9B8','아우터':'#D9CCF2','가방':'#BFE3D0','패션 소품':'#F2C4DD'}
def _hx(c): return tuple(int(c[i:i+2],16) for i in (1,3,5))
def _mix(a,b,t): return '#%02X%02X%02X'%tuple(round(x*t+y*(1-t)) for x,y in zip(_hx(a),_hx(b)))
COLOR['전체'] = '#E9E9E3'
TONE = {k:_mix(v,'#111111',0.45) for k,v in COLOR.items()}
TINT = {k:_mix(v,'#F7F7F3',0.45) for k,v in COLOR.items()}
SHOPS = ['Goodrunner','KREAM','EQL','Mizuno','8Division','Kaptain Sunshine']

NAME="font-size: 13px; font-weight: 700; color: #111111; flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap"
PRICE="font-family: 'Space Grotesk', Pretendard, sans-serif; font-size: 13px; font-weight: 500; flex-shrink: 0; white-space: nowrap; color: #111111"
MONO="font-family: 'JetBrains Mono', Pretendard, monospace; font-size: 10px; letter-spacing: .14em; color: rgba(17,17,17,.62)"
CHECK='<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"></path></svg>'
CHECK_S='<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"></path></svg>'
PLUS='<svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14"></path><path d="M5 12h14"></path></svg>'
CLOSE='<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l12 12"></path><path d="M18 6L6 18"></path></svg>'
XS='<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l12 12"></path><path d="M18 6L6 18"></path></svg>'
CHEV='<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 5l7 7-7 7"></path></svg>'
ARCH='<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="5" rx="1.5"></rect><path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9"></path><path d="M10 13h4"></path></svg>'

def h1(a,b):
    return ('<h1 style="margin: 0; font-size: 22px; line-height: 30px; font-weight: 700; letter-spacing: -0.035em; color: #111111">'
            f'<span data-fit="1" style="display: block; white-space: nowrap; overflow: hidden">{a}</span>'
            f'<span data-fit="1" style="display: block; white-space: nowrap; overflow: hidden; color: rgba(17,17,17,.5)">{b}</span></h1>')
def label_row(left, right, pad='20px 16px 0'):
    return (f'<div style="padding: {pad}; display: flex; justify-content: space-between; align-items: baseline">'
            '<span style="display: flex; align-items: center; gap: 8px"><span style="width: 8px; height: 8px; border-radius: 999px; background: #DCD6F0; box-shadow: 0 0 0 1px rgba(17,17,17,.2)"></span>'
            f'<span style="{MONO}">{left}</span></span>'
            f'<span style="font-family: \'Space Grotesk\', Pretendard, sans-serif; font-size: 13px; color: rgba(17,17,17,.62)">{right}</span></div>')
def text_row(n,p):
    return f'<span style="display: flex; align-items: baseline; gap: 6px; min-width: 0; padding: 0 4px"><span style="{NAME}">{n}</span><span style="{PRICE}">{p}</span></span>'
def photo(inner=''):
    return ('<span style="width: 187px; height: {{ it.hpx }}; border-radius: 16px; overflow: hidden; background: #FFFFFF; display: inline-flex; flex-shrink: 0; position: relative">'
            '<img src="{{ it.img }}" alt="" style="width: 100%; height: 100%; object-fit: cover; display: block">'+inner+'</span>')

ADD = ('<button onClick="{{ openPicker }}" style="border: 0; padding: 0; background: transparent; color: rgba(17,17,17,.62); width: 187px; height: 187px; border-radius: 16px; box-sizing: border-box; border: 1.5px dashed rgba(17,17,17,.28); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px">'
       + PLUS + '<span style="font-size: 13px; font-weight: 700">후보 추가</span></button>')

def col(list_name, add_flag=None):
    cell = f'<div style="display: flex; flex-direction: column; gap: 6px">{photo()}{text_row("{{ it.n }}","{{ it.p }}")}</div>'
    out = f'<div style="flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 10px"><sc-for list="{{{{ {list_name} }}}}" as="it" hint-placeholder-count="3">{cell}</sc-for>'
    if add_flag: out += f'<sc-if value="{{{{ {add_flag} }}}}" hint-placeholder-val="{{{{ true }}}}">{ADD}</sc-if>'
    return out + '</div>'

def pcol(list_name):
    ring = '<span style="position: absolute; inset: 0; border-radius: 16px; box-shadow: {{ it.ring }}; pointer-events: none"></span>'
    badge = ('<span style="position: absolute; top: 10px; right: 10px; width: 26px; height: 26px; border-radius: 999px; box-sizing: border-box; border: {{ it.badgeBorder }}; background: {{ it.badgeBg }}; color: #FFFFFF; display: flex; align-items: center; justify-content: center">'
             f'<sc-if value="{{{{ it.on }}}}" hint-placeholder-val="{{{{ false }}}}">{CHECK}</sc-if></span>')
    cell = (f'<button onClick="{{{{ it.toggle }}}}" aria-pressed="{{{{ it.pressed }}}}" style="border: 0; padding: 0; background: transparent; color: #111111; display: flex; flex-direction: column; gap: 6px; text-align: left; width: 100%">'
            + photo(ring+badge) + text_row('{{ it.n }}','{{ it.p }}') + '</button>')
    return f'<div style="flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 10px"><sc-for list="{{{{ {list_name} }}}}" as="it" hint-placeholder-count="3">{cell}</sc-for></div>'

# ---------- filter variants ----------
def pill(bind, label_html, h=40, pad=14, fs=14):
    return (f'<button onClick="{{{{ f.go }}}}" aria-pressed="{{{{ f.pressed }}}}" style="height: {h}px; padding: 0 {pad}px; border-radius: 999px; border: 0; flex-shrink: 0; background: {{{{ f.bg }}}}; color: {{{{ f.fg }}}}; box-shadow: {{{{ f.ring }}}}; font-size: {fs}px; font-weight: 700; display: flex; align-items: center; gap: 6px; white-space: nowrap">{label_html}</button>')
COUNT = '<span style="font-family: \'Space Grotesk\', Pretendard, sans-serif; font-size: 12px; font-weight: 500; opacity: .6">{{ f.count }}</span>'
DOT = '<sc-if value="{{ f.hasDot }}" hint-placeholder-val="{{ true }}"><span style="width: 8px; height: 8px; border-radius: 999px; background: {{ f.dot }}; box-shadow: 0 0 0 1px rgba(17,17,17,.15)"></span></sc-if>'
ROW = 'display: flex; gap: 6px; overflow-x: auto; scrollbar-width: none'
FILTERS = {
 'A': ('<div class="scroll" role="group" aria-label="카테고리" style="'+ROW+'; padding: 16px 20px 0"><sc-for list="{{ catPills }}" as="f" hint-placeholder-count="5">'+pill('f', DOT+'{{ f.label }}'+COUNT)+'</sc-for></div>'),
 'B': ('<div style="padding: 16px 0 0; display: flex; flex-direction: column">'
       '<div style="display: flex; align-items: center; gap: 4px; padding: 0 0 10px 20px; border-bottom: 1px solid rgba(17,17,17,.08)"><span style="font-size: 13px; font-weight: 700; display: flex; align-items: center; gap: 2px; flex-shrink: 0; width: 72px">카테고리'+CHEV+'</span>'
       '<div class="scroll" role="group" aria-label="카테고리" style="'+ROW+'; padding-right: 20px"><sc-for list="{{ catPills }}" as="f" hint-placeholder-count="5">'+pill('f', DOT+'{{ f.label }}', 34, 12, 13)+'</sc-for></div></div>'
       '<div style="display: flex; align-items: center; gap: 4px; padding: 10px 0 0 20px"><span style="font-size: 13px; font-weight: 700; display: flex; align-items: center; gap: 2px; flex-shrink: 0; width: 72px">판매처'+CHEV+'</span>'
       '<div class="scroll" role="group" aria-label="판매처" style="'+ROW+'; padding-right: 20px"><sc-for list="{{ shopPills }}" as="f" hint-placeholder-count="4">'+pill('f', '{{ f.label }}', 34, 12, 13)+'</sc-for></div></div></div>'),
 'C': ('<div class="scroll" role="group" aria-label="카테고리" style="'+ROW+'; padding: 16px 20px 0"><sc-for list="{{ catPills }}" as="f" hint-placeholder-count="5">'
       +pill('f', '<sc-if value="{{ f.on }}" hint-placeholder-val="{{ false }}">'+CHECK_S+'</sc-if>{{ f.label }}'+COUNT)+'</sc-for></div>'),
}
FILTERS['D'] = ('<div style="padding: 16px 0 0; display: flex; align-items: center; gap: 4px; padding-left: 20px">'
   '<span style="font-size: 13px; font-weight: 700; display: flex; align-items: center; gap: 2px; flex-shrink: 0; width: 72px">카테고리'+CHEV+'</span>'
   '<div class="scroll" role="group" aria-label="카테고리" style="'+ROW+'; padding-right: 20px"><sc-for list="{{ catPills }}" as="f" hint-placeholder-count="5">'+pill('f', '{{ f.label }}', 34, 12, 13)+'</sc-for></div></div>')
CHK = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"></path></svg>'
FILTERS['E'] = ('<div style="padding: 16px 0 0; display: flex; align-items: center; gap: 4px; padding-left: 20px">'
   '<button onClick="{{ openCats }}" aria-label="전체 카테고리 보기" style="height: 44px; margin: -5px 0; padding: 0; border: 0; background: transparent; color: #111111; font-size: 13px; font-weight: 700; display: flex; align-items: center; gap: 2px; flex-shrink: 0; width: 72px">카테고리' + CHEV + '</button>'
   '<div class="scroll" role="group" aria-label="카테고리" style="' + ROW + '; padding-right: 20px"><sc-for list="{{ catPills }}" as="f" hint-placeholder-count="5">' + pill('f', '{{ f.label }}', 34, 12, 13) + '</sc-for></div></div>')
CARD = 'background: #FFFFFF; border-radius: 28px; box-shadow: 0 12px 40px -12px rgba(17,17,17,.25)'
SHEET = ('<sc-if value="{{ isCats }}" hint-placeholder-val="{{ false }}"><div style="position: absolute; inset: 0; z-index: 8; background: rgba(247,247,243,.4); backdrop-filter: blur(2px); -webkit-backdrop-filter: blur(2px)"></div>'
  '<div role="dialog" aria-label="카테고리" style="position: absolute; left: 12px; right: 12px; bottom: 16px; z-index: 9; display: flex; flex-direction: column; gap: 8px">'
  '<div style="' + CARD + '; padding: 14px 14px 18px; display: flex; flex-direction: column; align-items: center; gap: 8px">'
  '<div style="width: 100%; display: flex; justify-content: space-between; align-items: center"><span style="width: 36px"></span>'
  '<span style="font-size: 17px; font-weight: 700; letter-spacing: -0.02em">카테고리</span>'
  '<button onClick="{{ closeCats }}" aria-label="닫기" style="width: 36px; height: 36px; border-radius: 999px; border: 0; background: #F2F2EE; color: #111111; display: flex; align-items: center; justify-content: center; flex-shrink: 0">' + CLOSE + '</button></div>'
  '<span style="font-size: 13px; line-height: 19px; color: rgba(17,17,17,.62); text-align: center">보고 싶은 카테고리를 골라 주세요. 고르지 않으면 모두 보여드려요.</span></div>'
  '<div style="' + CARD + '; padding: 16px 18px 18px; display: flex; flex-direction: column; gap: 14px">'
  '<div class="scroll" style="display: flex; flex-direction: column; max-height: 380px; overflow-y: auto">'
  '<sc-for list="{{ catGroups }}" as="g" hint-placeholder-count="1"><span style="' + MONO + '; padding: 4px 0 6px">{{ g.name }}</span>'
  '<sc-for list="{{ g.items }}" as="r" hint-placeholder-count="4">'
  '<button onClick="{{ r.toggle }}" role="checkbox" aria-checked="{{ r.checked }}" style="min-height: 52px; border: 0; border-top: 1px solid rgba(17,17,17,.08); background: transparent; color: #111111; display: flex; align-items: center; gap: 12px; padding: 0; text-align: left; width: 100%">'
  '<span style="font-size: 15px; font-weight: 700; flex-grow: 1">{{ r.label }}</span>'
  "<span style=\"font-family: 'Space Grotesk', Pretendard, sans-serif; font-size: 13px; color: rgba(17,17,17,.62)\">{{ r.count }}</span>"
  '<span style="width: 24px; height: 24px; border-radius: 999px; box-sizing: border-box; border: {{ r.border }}; background: {{ r.bg }}; color: #FFFFFF; display: flex; align-items: center; justify-content: center; flex-shrink: 0"><sc-if value="{{ r.on }}" hint-placeholder-val="{{ false }}">' + CHK + '</sc-if></span></button>'
  '</sc-for></sc-for></div>'
  '<div style="display: flex; gap: 8px"><button onClick="{{ clearCats }}" style="height: 52px; padding: 0 20px; border-radius: 999px; border: 0; background: #F2F2EE; color: #111111; font-size: 15px; font-weight: 700; flex-shrink: 0">초기화</button>'
  '<button onClick="{{ applyCats }}" style="height: 52px; flex-grow: 1; border-radius: 999px; border: 0; background: #111111; color: #FFFFFF; font-size: 15px; font-weight: 700">{{ applyLabel }}</button></div>'
  '</div></div></sc-if>')
FILTER_DESC = {'A':'카테고리 한 줄 · 고르면 그 색으로','B':'29cm식 두 줄 · 카테고리 + 판매처','C':'색 면 pill · 여러 개 고르기'}

# ---------- tray variants ----------
TRAY_WRAP = 'position: absolute; left: 20px; right: 20px; bottom: 90px; z-index: 2; background: #FFFFFF; border-radius: 20px; box-shadow: 0 12px 40px -12px rgba(17,17,17,.3)'
TRAYS = {
 'A': ('<div style="'+TRAY_WRAP+'; padding: 12px 0 12px 14px; display: flex; align-items: center; gap: 12px">'
       f'<span style="{MONO}; flex-shrink: 0; width: 34px">선택<br><span style="font-family: \'Space Grotesk\', Pretendard, sans-serif; font-size: 18px; letter-spacing: 0; color: #111111">{{{{ n }}}}</span></span>'
       '<div class="scroll" style="display: flex; gap: 8px; overflow-x: auto; padding: 6px 14px 6px 0"><sc-for list="{{ picked }}" as="p" hint-placeholder-count="3">'
       '<button onClick="{{ p.remove }}" aria-label="{{ p.n }} 빼기" style="position: relative; width: 48px; height: 48px; padding: 0; border: 0; border-radius: 12px; background: #FFFFFF; flex-shrink: 0; overflow: visible">'
       '<img src="{{ p.img }}" alt="" style="width: 48px; height: 48px; border-radius: 12px; object-fit: cover; display: block; box-shadow: 0 0 0 1px rgba(17,17,17,.08)">'
       f'<span style="position: absolute; top: -5px; right: -5px; width: 18px; height: 18px; border-radius: 999px; background: #111111; color: #FFFFFF; display: flex; align-items: center; justify-content: center; box-shadow: 0 0 0 2px #FFFFFF">{XS}</span></button>'
       '</sc-for></div></div>'),
 'B': ('<div style="'+TRAY_WRAP+'; padding: 10px 10px 10px 12px; display: flex; align-items: center; gap: 10px">'
       '<span style="display: flex; flex-shrink: 0"><sc-for list="{{ pickedHead }}" as="p" hint-placeholder-count="3">'
       '<span style="width: 36px; height: 36px; border-radius: 999px; overflow: hidden; background: #FFFFFF; margin-left: {{ p.ml }}; box-shadow: 0 0 0 2px #FFFFFF; flex-shrink: 0; display: inline-flex"><img src="{{ p.img }}" alt="" style="width: 100%; height: 100%; object-fit: cover"></span></sc-for>'
       '<sc-if value="{{ hasMore }}" hint-placeholder-val="{{ false }}"><span style="margin-left: -10px; height: 36px; min-width: 36px; padding: 0 8px; box-sizing: border-box; border-radius: 999px; background: #F2F2EE; box-shadow: 0 0 0 2px #FFFFFF; font-family: \'Space Grotesk\', Pretendard, sans-serif; font-size: 12px; display: inline-flex; align-items: center; justify-content: center">+{{ more }}</span></sc-if></span>'
       '<span style="display: flex; flex-direction: column; gap: 2px; flex: 1 1 auto; min-width: 0"><span style="font-size: 13px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{{ summary }}</span>'
       '<span style="font-size: 12px; color: rgba(17,17,17,.62)">{{ n }}개 골랐어요</span></span>'
       '<button onClick="{{ clearAll }}" style="height: 36px; padding: 0 12px; border-radius: 999px; border: 0; background: #F2F2EE; color: #111111; font-size: 12px; font-weight: 700; flex-shrink: 0">모두 빼기</button></div>'),
 'C': ('<div class="scroll" style="position: absolute; left: 0; right: 0; bottom: 86px; z-index: 2; display: flex; gap: 6px; overflow-x: auto; padding: 8px 20px">'
       '<sc-for list="{{ picked }}" as="p" hint-placeholder-count="3">'
       '<button onClick="{{ p.remove }}" aria-label="{{ p.n }} 빼기" style="height: 40px; padding: 0 10px 0 4px; border-radius: 999px; border: 0; background: {{ p.color }}; color: #111111; display: flex; align-items: center; gap: 6px; flex-shrink: 0; box-shadow: 0 6px 18px -8px rgba(17,17,17,.35)">'
       '<img src="{{ p.img }}" alt="" style="width: 32px; height: 32px; border-radius: 999px; object-fit: cover; background: #FFFFFF">'
       '<span style="font-size: 13px; font-weight: 700; max-width: 104px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{{ p.n }}</span>'
       f'<span style="width: 18px; height: 18px; border-radius: 999px; background: rgba(17,17,17,.12); display: flex; align-items: center; justify-content: center">{XS}</span></button>'
       '</sc-for></div>'),
}
TRAYS['S'] = ('<div style="'+TRAY_WRAP+'; padding: 8px 0 8px 14px; display: flex; align-items: center; gap: 10px">'
   f'<span style="{MONO}; flex-shrink: 0">선택 <span style="font-family: \'Space Grotesk\', Pretendard, sans-serif; font-size: 13px; letter-spacing: 0; color: #111111">{{{{ n }}}}</span></span>'
   '<div class="scroll" style="display: flex; gap: 6px; overflow-x: auto; padding: 5px 14px 5px 0"><sc-for list="{{ picked }}" as="p" hint-placeholder-count="3">'
   '<button onClick="{{ p.remove }}" aria-label="{{ p.n }} 빼기" style="position: relative; width: 44px; height: 44px; padding: 4px; margin: -4px; box-sizing: border-box; border: 0; background: transparent; flex-shrink: 0">'
   '<img src="{{ p.img }}" alt="" style="width: 36px; height: 36px; border-radius: 10px; object-fit: cover; display: block; box-shadow: 0 0 0 1px rgba(17,17,17,.08)">'
   '<span style="position: absolute; top: 0; right: 0; width: 15px; height: 15px; border-radius: 999px; background: #111111; color: #FFFFFF; display: flex; align-items: center; justify-content: center; box-shadow: 0 0 0 1.5px #FFFFFF"><svg width="7" height="7" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l12 12"></path><path d="M18 6L6 18"></path></svg></span></button>'
   '</sc-for></div></div>')
TRAY_DESC = {'A':'사진 줄 · 눌러서 빼기','B':'겹친 원 + 요약 · 모두 빼기','C':'이름 칩 · 카테고리 색'}

def build(fv, tv, title, mode='tone'):
    detail = ('<div style="height: 1000px; display: flex; flex-direction: column">'
      '<div style="padding: 58px 20px 0; display: flex; flex-direction: column; gap: 18px">'+topbar+h1('{{ pname }} 후보 {{ count }}개','옷이랑 러닝 용품 한꺼번에')+'</div>'
      + label_row('최근 저장순','{{ count }}') +
      '<div class="scroll" style="flex: 1 1 auto; min-height: 0; overflow-y: auto; overscroll-behavior: contain"><div style="padding: 14px 6px 200px"><div style="display: flex; gap: 4px; align-items: flex-start">'
      + col('dL','addL') + col('dR','addR') + '</div></div></div></div>'
      '<sc-if value="{{ isFilled }}" hint-placeholder-val="{{ false }}"><div style="position: absolute; left: 20px; right: 20px; bottom: 96px; z-index: 3"><button style="border-radius: 999px; border: 0; background: #111111; color: #FFFFFF; font-size: 13px; font-weight: 700; display: flex; align-items: center; justify-content: center; gap: 8px; box-shadow: 0 8px 24px -8px rgba(17,17,17,.4); margin-left: auto; margin-right: auto; padding: 12px 16px; min-height: 44px; box-sizing: border-box">'+ARCH+'비교 끝내기</button></div></sc-if>'
      '<sc-if value="{{ showToast }}" hint-placeholder-val="{{ false }}"><div role="status" style="position: absolute; left: 20px; right: 20px; bottom: 96px; z-index: 4; height: 52px; border-radius: 999px; background: #111111; color: #FFFFFF; display: flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 700">{{ toastText }}</div></sc-if>'
      + nav)
    picker = ('<sc-if value="{{ isPicker }}" hint-placeholder-val="{{ true }}"><div style="position: absolute; inset: 0; z-index: 7; background: #F7F7F3; display: flex; flex-direction: column">'
      '<div style="padding: 58px 20px 0; display: flex; flex-direction: column; gap: 18px; flex-shrink: 0">'
      '<div style="height: 44px; display: flex; align-items: center; justify-content: space-between"><span style="font-size: 14px; font-weight: 700">후보 추가</span>'
      f'<button onClick="{{{{ closePicker }}}}" aria-label="닫기" style="width: 36px; height: 36px; border-radius: 999px; border: 0; background: #ECECE6; color: #111111; display: flex; align-items: center; justify-content: center">{CLOSE}</button></div>'
      + h1('{{ pname }}에 넣을 상품을','골라 주세요') + '</div>'
      + '<div style="flex-shrink: 0">' + FILTERS[fv] + label_row('최근 저장순','{{ shown }}', '16px 16px 0') + '</div>' +
      '<div class="scroll" style="flex: 1 1 auto; min-height: 0; overflow-y: auto; overscroll-behavior: contain"><div style="padding: 14px 6px 220px">'
      '<sc-if value="{{ noResult }}" hint-placeholder-val="{{ false }}"><div style="padding: 40px 14px; font-size: 14px; color: rgba(17,17,17,.62)">이 조건에 맞는 상품이 없어요.</div></sc-if>'
      '<div style="display: flex; gap: 4px; align-items: flex-start">' + pcol('pL') + pcol('pR') + '</div></div></div>'
      '<sc-if value="{{ hasPick }}" hint-placeholder-val="{{ true }}">' + TRAYS[tv] + '</sc-if>'
      '<div style="position: absolute; left: 20px; right: 20px; bottom: 28px; z-index: 3"><button onClick="{{ addPicked }}" aria-disabled="{{ addOff }}" style="height: 54px; width: 100%; border-radius: 999px; border: 0; background: {{ addBg }}; color: {{ addFg }}; font-size: 15px; font-weight: 700">{{ addLabel }}</button></div>'
      + (SHEET if fv == 'E' else '') +
      '</div></sc-if>')
    multi = 'true' if fv in ('C','D','E') else 'false'
    data = json.dumps([{k:it[k] for k in ('i','n','p','img','h','c','s')} for it in I], ensure_ascii=False)
    script = f'''<script type="text/x-dc" data-dc-script data-props='{{"$preview": {{"width": 390, "height": 1000}}}}'>
class Component extends DCLogic {{
componentDidMount() {{ this.fit(); }}
componentDidUpdate() {{ this.fit(); }}
fit() {{
  const run = () => document.querySelectorAll('[data-fit]').forEach((el) => {{
    el.style.fontSize = ''; let size = parseFloat(getComputedStyle(el).fontSize);
    while (el.scrollWidth > el.clientWidth + 0.5 && size > 12) {{ size -= 0.5; el.style.fontSize = size + 'px'; }}
  }});
  run(); if (document.fonts && document.fonts.ready) document.fonts.ready.then(run);
}}
state = {{ picker: true, chosen: [], draft: [0, 7, 4], cats: [], shop: null, toast: null, catsOpen: false, catDraft: [] }};
flash(text) {{ this.setState({{ toast: text }}); clearTimeout(this._t); this._t = setTimeout(() => this.setState({{ toast: null }}), 1800); }}
split(list, extra) {{ const L = [], R = []; let hl = 0, hr = 0; list.forEach((it) => {{ if (hl <= hr) {{ L.push(it); hl += it.h + 40; }} else {{ R.push(it); hr += it.h + 40; }} }}); return {{ L, R, addL: hl <= hr }}; }}
renderVals() {{
  const s = this.state; const D = {data}; const CATS = {json.dumps(CATS, ensure_ascii=False)}; const COLOR = {json.dumps(COLOR, ensure_ascii=False)}; const SHOPS = {json.dumps(SHOPS)}; const MULTI = {multi}; const MODE = '{mode}'; const TONE = {json.dumps(TONE, ensure_ascii=False)}; const TINT = {json.dumps(TINT, ensure_ascii=False)};
  const v = {{}}; v.pname = '가을 준비';
  const withPx = (it) => Object.assign({{}}, it, {{ hpx: it.h + 'px' }});
  const inP = D.filter((it) => s.chosen.indexOf(it.i) !== -1).map(withPx);
  const d = this.split(inP); v.dL = d.L; v.dR = d.R; v.addL = d.addL; v.addR = !d.addL;
  v.count = inP.length; v.isFilled = inP.length > 0; v.isPicker = s.picker;
  v.showToast = !!s.toast; v.toastText = s.toast || '';
  v.back = () => this.setState({{ picker: false, chosen: [], draft: [] }});
  v.openPicker = () => this.setState({{ picker: true, draft: [], cats: [], shop: null }});
  v.closePicker = () => this.setState({{ picker: false }});
  const pool = D.filter((it) => s.chosen.indexOf(it.i) === -1);
  const pass = (it) => (s.cats.length === 0 || s.cats.indexOf(it.c) !== -1) && (s.shop === null || it.s === s.shop);
  const shownList = pool.filter(pass).map((it) => {{ const on = s.draft.indexOf(it.i) !== -1; return Object.assign(withPx(it), {{
    on, pressed: on ? 'true' : 'false', ring: 'none',
    badgeBg: on ? '#111111' : 'rgba(255,255,255,.85)', badgeBorder: on ? '0' : '1.5px solid rgba(17,17,17,.25)',
    toggle: () => this.setState({{ draft: on ? s.draft.filter((x) => x !== it.i) : s.draft.concat([it.i]) }}) }}); }});
  const p = this.split(shownList); v.pL = p.L; v.pR = p.R; v.shown = shownList.length; v.noResult = shownList.length === 0;
  const cnt = (c) => pool.filter((it) => it.c === c).length;
  const allOn = s.cats.length === 0;
  v.catPills = [{{ label: '전체', count: pool.length, hasDot: false, dot: '', on: allOn, pressed: allOn ? 'true' : 'false', bg: allOn ? '#111111' : '#E9E9E3', fg: allOn ? '#FFFFFF' : '#111111', ring: 'none', go: () => this.setState({{ cats: [] }}) }}]
    .concat(CATS.filter((c) => cnt(c) > 0).map((c) => {{ const on = s.cats.indexOf(c) !== -1; const col = COLOR[c];
      const bg = MULTI ? col : (on ? col : '#E9E9E3');
      return {{ label: c, count: cnt(c), hasDot: !on, dot: col, on, pressed: on ? 'true' : 'false', bg, fg: '#111111', ring: on ? 'inset 0 0 0 2px #111111' : 'none',
        go: () => this.setState({{ cats: MULTI ? (on ? s.cats.filter((x) => x !== c) : s.cats.concat([c])) : (on ? [] : [c]) }}) }}; }}));
  if (MODE !== 'none' && MODE !== 'plain') v.catPills = v.catPills.map((f) => {{ const key = f.label; const on = f.on;
    const off = MODE === 'bg' ? '#F7F7F3' : TONE[key];
    return Object.assign({{}}, f, {{ bg: MODE === 'tint' ? (on ? COLOR[key] : TINT[key]) : COLOR[key], fg: on ? '#111111' : off, ring: 'none', hasDot: false }}); }});
  if (MODE === 'plain') {{
    v.catPills = CATS.filter((c) => cnt(c) > 0).map((c) => {{ const on = s.cats.indexOf(c) !== -1;
      return {{ label: c, count: cnt(c), on, pressed: on ? 'true' : 'false', bg: on ? '#111111' : '#E9E9E3', fg: on ? '#FFFFFF' : '#111111', ring: 'none', hasDot: false,
        go: () => this.setState({{ cats: on ? s.cats.filter((x) => x !== c) : s.cats.concat([c]) }}) }}; }});
  }}
  v.isCats = s.catsOpen;
  v.openCats = () => this.setState({{ catsOpen: true, catDraft: s.cats.slice() }});
  v.closeCats = () => this.setState({{ catsOpen: false }});
  v.clearCats = () => this.setState({{ catDraft: [] }});
  v.applyCats = () => this.setState({{ catsOpen: false, cats: s.catDraft.slice() }});
  v.catGroups = [{{ name: '패션·잡화', items: CATS.filter((c) => cnt(c) > 0).map((c) => {{ const on = s.catDraft.indexOf(c) !== -1;
    return {{ label: c, count: cnt(c), on, checked: on ? 'true' : 'false', bg: on ? '#111111' : '#FFFFFF', border: on ? '0' : '2px solid rgba(17,17,17,.25)',
      toggle: () => this.setState({{ catDraft: on ? s.catDraft.filter((x) => x !== c) : s.catDraft.concat([c]) }}) }}; }}) }}];
  v.applyLabel = pool.filter((it) => s.catDraft.length === 0 || s.catDraft.indexOf(it.c) !== -1).length + '개 보기';
  const shopOn = (x) => s.shop === x;
  v.shopPills = [{{ label: '전체', on: s.shop === null, pressed: s.shop === null ? 'true' : 'false', bg: s.shop === null ? '#111111' : '#E9E9E3', fg: s.shop === null ? '#FFFFFF' : '#111111', ring: 'none', go: () => this.setState({{ shop: null }}) }}]
    .concat(SHOPS.filter((x) => pool.some((it) => it.s === x)).map((x) => ({{ label: x, on: shopOn(x), pressed: shopOn(x) ? 'true' : 'false', bg: shopOn(x) ? '#111111' : '#E9E9E3', fg: shopOn(x) ? '#FFFFFF' : '#111111', ring: 'none', go: () => this.setState({{ shop: shopOn(x) ? null : x }}) }})));
  const picked = s.draft.map((i) => D[i]).map((it) => Object.assign({{}}, it, {{ color: COLOR[it.c], remove: () => this.setState({{ draft: s.draft.filter((x) => x !== it.i) }}) }}));
  v.picked = picked; v.n = picked.length; v.hasPick = picked.length > 0;
  v.pickedHead = picked.slice(0, 4).map((it, k) => Object.assign({{}}, it, {{ ml: k === 0 ? '0' : '-10px' }})); v.more = Math.max(0, picked.length - 4); v.hasMore = v.more > 0;
  v.summary = picked.length ? picked[0].n + (picked.length > 1 ? ' 외 ' + (picked.length - 1) + '개' : '') : '';
  v.clearAll = () => this.setState({{ draft: [] }});
  const n = picked.length; const off = n === 0;
  v.addOff = off ? 'true' : 'false'; v.addBg = off ? '#E4E4DE' : '#111111'; v.addFg = off ? '#9C9C95' : '#FFFFFF';
  v.addLabel = off ? '후보로 넣을 상품을 골라 주세요' : n + '개 후보로 넣기';
  v.addPicked = () => {{ if (off) return; this.setState({{ picker: false, chosen: s.chosen.concat(s.draft), draft: [] }}); this.flash(n + '개를 후보로 넣었어요'); }};
  return v;
}}
}}
</script>'''
    return f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
{helmet}
<div style="width: 390px; height: 1000px; position: relative; overflow: hidden; background: #F7F7F3; color: #111111">{detail}{picker}</div>
</x-dc>
{script}
</body>
</html>
'''

