# 길게 누르기 메뉴 조각(LM-A)과 LM-A 보드 생성기. 사용: python3 gen_longpress.py <출력 폴더>
import json, os, re, sys
src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'lib_pg.py')).read()
exec(src[:src.index('def build(')])   # helpers: helmet, nav, topbar, I, photo, text_row, h1, label_row, ADD, MONO, ...

OUT = sys.argv[1]
ICON = {
 'open':  '<path d="M14 4h6v6"></path><path d="M20 4l-9 9"></path><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"></path>',
 'edit':  '<path d="M4 20h4L19 9l-4-4L4 16z"></path><path d="M13.5 6.5l4 4"></path>',
 'move':  '<path d="M4 8h13"></path><path d="M13 4l4 4-4 4"></path><path d="M20 16H7"></path><path d="M11 12l-4 4 4 4"></path>',
 'out':   '<circle cx="12" cy="12" r="8.5"></circle><path d="M8 12h8"></path>',
 'trash': '<path d="M4 7h16"></path><path d="M9 7V4.5h6V7"></path><path d="M6 7l1 13h10l1-13"></path>',
}
def svg(k, size=20):
    return f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">{ICON[k]}</svg>'
RED = '#C23B2A'
ACTIONS = [('open','원본 페이지 열기'),('edit','상품 정보 수정'),('move','다른 목적으로 옮기기'),('out','목적에서 빼기'),('trash','삭제')]
MENU_CARD = 'background: #FFFFFF; border-radius: 20px; box-shadow: 0 16px 48px -12px rgba(17,17,17,.3)'

def row(k, label, first=False):
    col = RED if k == 'trash' else '#111111'
    bt = '0' if first else '1px solid rgba(17,17,17,.06)'
    return (f'<button onClick="{{{{ act_{k} }}}}" style="height: 52px; border: 0; border-top: {bt}; background: transparent; color: {col}; display: flex; align-items: center; gap: 14px; padding: 0 18px; width: 100%; text-align: left; font-size: 15px; font-weight: 700">'
            f'{svg(k)}{label}</button>')

HEADER = ('<div style="padding: 14px 18px 12px; display: flex; flex-direction: column; gap: 3px; border-bottom: 1px solid rgba(17,17,17,.08)">'
          '<span style="font-size: 13px; font-weight: 700; line-height: 18px">{{ m.n }}</span>'
          "<span style=\"font-family: 'Space Grotesk', Pretendard, sans-serif; font-size: 13px; color: rgba(17,17,17,.62)\">{{ m.p }}</span></div>")

def menu_list(keys, header=True, width='232px'):
    rows = ''.join(row(k, dict(ACTIONS)[k], i == 0 and not header) for i, k in enumerate(keys))
    return f'<div style="{MENU_CARD}; width: {width}; overflow: hidden; display: flex; flex-direction: column">' + (HEADER if header else '') + rows + '</div>'

QUICK = ('<div style="' + MENU_CARD + '; border-radius: 999px; display: flex; padding: 6px; gap: 2px">'
         + ''.join(f'<button onClick="{{{{ act_{k} }}}}" aria-label="{t}" style="width: 72px; height: 56px; border: 0; border-radius: 999px; background: transparent; color: {RED if k=="trash" else "#111111"}; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; font-size: 11px; font-weight: 700">{svg(k, 20)}{s}</button>'
                   for k, t, s in [('open','원본 페이지 열기','원본'),('edit','상품 정보 수정','수정'),('trash','삭제','삭제')])
         + '</div>')

LIFT = ('<div style="position: absolute; left: {{ m.cx }}; top: {{ m.cy }}; width: {{ m.cw }}; z-index: 11; display: flex; flex-direction: column; gap: 6px; transform: scale({{ m.scale }}); transform-origin: {{ m.origin }}; pointer-events: none">'
        '<span style="width: 100%; height: {{ m.ch }}; border-radius: 16px; overflow: hidden; background: #FFFFFF; display: inline-flex; box-shadow: 0 18px 48px -14px rgba(17,17,17,.45)"><img src="{{ m.img }}" alt="" style="width: 100%; height: 100%; object-fit: cover; display: block"></span>'
        '<sc-if value="{{ m.showText }}" hint-placeholder-val="{{ true }}">' + text_row('{{ m.n }}', '{{ m.p }}') + '</sc-if></div>')

def overlay(variant):
    parts = ['<sc-if value="{{ isMenu }}" hint-placeholder-val="{{ false }}">'
             '<button onClick="{{ closeMenu }}" aria-label="메뉴 닫기" style="position: absolute; inset: 0; z-index: 10; border: 0; padding: 0; background: rgba(247,247,243,.4); backdrop-filter: blur(6px); -webkit-backdrop-filter: blur(6px)"></button>',
             LIFT]
    keys = [k for k, _ in ACTIONS]
    if variant == 'C':
        parts.append('<div style="position: absolute; left: {{ m.qx }}; top: {{ m.qy }}; z-index: 12">' + QUICK + '</div>')
        keys = ['move', 'out']
    parts.append('<div role="menu" style="position: absolute; left: {{ m.mx }}; top: {{ m.my }}; z-index: 12">' + menu_list(keys, header=(variant != 'C')) + '</div>')
    parts.append('</sc-if>')
    return ''.join(parts)

def dcol(list_name, add_flag):
    cell = ('<button onPointerDown="{{ it.press }}" onPointerUp="{{ it.release }}" onPointerLeave="{{ it.release }}" onContextMenu="{{ it.ctx }}" onClick="{{ it.tap }}" '
            'style="border: 0; padding: 0; background: transparent; color: #111111; display: flex; flex-direction: column; gap: 6px; text-align: left; width: 100%; -webkit-touch-callout: none; user-select: none; -webkit-user-select: none">'
            + photo() + text_row('{{ it.n }}', '{{ it.p }}') + '</button>')
    return (f'<div style="flex: 1 1 0; min-width: 0; display: flex; flex-direction: column; gap: 10px"><sc-for list="{{{{ {list_name} }}}}" as="it" hint-placeholder-count="3">{cell}</sc-for>'
            f'<sc-if value="{{{{ {add_flag} }}}}" hint-placeholder-val="{{{{ true }}}}">' + ADD.replace('{{ openPicker }}', '{{ addTap }}') + '</sc-if></div>')

ARCH = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="5" rx="1.5"></rect><path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9"></path><path d="M10 13h4"></path></svg>'

def build(variant, title):
    body = ('<div style="height: 844px; display: flex; flex-direction: column">'
      '<div style="padding: 58px 20px 0; display: flex; flex-direction: column; gap: 18px; flex-shrink: 0">' + topbar + h1('{{ pname }} 후보 {{ count }}개', '옷이랑 러닝 용품 한꺼번에') + '</div>'
      '<div style="flex-shrink: 0">' + label_row('최근 저장순', '{{ count }}') + '</div>'
      '<div class="scroll" style="flex: 1 1 auto; min-height: 0; overflow-y: auto; overscroll-behavior: contain"><div style="padding: 14px 6px 200px"><div style="display: flex; gap: 4px; align-items: flex-start">'
      + dcol('dL', 'addL') + dcol('dR', 'addR') + '</div></div></div></div>'
      '<div style="position: absolute; left: 20px; right: 20px; bottom: 96px; z-index: 3"><button style="border-radius: 999px; border: 0; background: #111111; color: #FFFFFF; font-size: 13px; font-weight: 700; display: flex; align-items: center; justify-content: center; gap: 8px; box-shadow: 0 8px 24px -8px rgba(17,17,17,.4); margin-left: auto; margin-right: auto; padding: 12px 16px; min-height: 44px; box-sizing: border-box">' + ARCH + '비교 끝내기</button></div>'
      + nav + overlay(variant) +
      '<sc-if value="{{ showToast }}" hint-placeholder-val="{{ false }}"><div role="status" style="position: absolute; left: 20px; right: 20px; bottom: 96px; z-index: 20; height: 52px; border-radius: 999px; background: #111111; color: #FFFFFF; display: flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 700">{{ toastText }}</div></sc-if>')
    data = json.dumps([{k: it[k] for k in ('i', 'n', 'p', 'img', 'h')} for it in I], ensure_ascii=False)
    js = '''
class Component extends DCLogic {
componentDidMount() { this.fit(); }
componentDidUpdate() { this.fit(); }
fit() {
  const run = () => document.querySelectorAll('[data-fit]').forEach((el) => {
    el.style.fontSize = ''; let size = parseFloat(getComputedStyle(el).fontSize);
    while (el.scrollWidth > el.clientWidth + 0.5 && size > 12) { size -= 0.5; el.style.fontSize = size + 'px'; }
  });
  run(); if (document.fonts && document.fonts.ready) document.fonts.ready.then(run);
}
state = { menu: null, toast: null, gone: [] };
flash(text) { this.setState({ menu: null, toast: text }); clearTimeout(this._t); this._t = setTimeout(() => this.setState({ toast: null }), 1800); }
split(list) { const L = [], R = []; let hl = 0, hr = 0; list.forEach((it) => { if (hl <= hr) { L.push(it); hl += it.h + 40; } else { R.push(it); hr += it.h + 40; } }); return { L, R, addL: hl <= hr }; }
open(it, el) {
  const root = el.closest('[data-root]'); if (!root) return;
  const rr = root.getBoundingClientRect(); const k = rr.width / 390; const r = el.getBoundingClientRect();
  this._justOpened = true; setTimeout(() => { this._justOpened = false; }, 400);
  this.setState({ menu: { i: it.i, x: (r.left - rr.left) / k, y: (r.top - rr.top) / k, w: r.width / k, ph: it.h } });
}
renderVals() {
  const s = this.state; const D = ''' + data + '''; const V = "''' + variant + '''";
  const W = 390, HH = 844, SAFE_T = 54, SAFE_B = 24;
  const v = { pname: '가을 준비' };
  const list = D.filter((it) => s.gone.indexOf(it.i) === -1).map((it) => Object.assign({}, it, {
    hpx: it.h + 'px',
    press: (e) => { const el = e.currentTarget; clearTimeout(this._lp); this._lp = setTimeout(() => this.open(it, el), 450); },
    release: () => clearTimeout(this._lp),
    ctx: (e) => { e.preventDefault(); clearTimeout(this._lp); this.open(it, e.currentTarget); },
    tap: () => { if (this._justOpened) return; this.flash('상품 화면으로 이동해요 (길게 누르면 메뉴)'); } }));
  const d = this.split(list); v.dL = d.L; v.dR = d.R; v.addL = d.addL; v.addR = !d.addL; v.count = list.length;
  v.addTap = () => this.flash('후보 추가 창이 열려요');
  v.showToast = !!s.toast; v.toastText = s.toast || '';
  v.back = () => this.flash('목적 목록으로 돌아가요');
  v.isMenu = !!s.menu; v.closeMenu = () => this.setState({ menu: null });
  const say = { open: '원본 페이지를 열어요', edit: '상품 정보 수정 화면을 열어요', move: '옮길 목적을 고르는 창이 열려요', out: '목적에서 뺐어요. 목적 미지정으로 옮겼어요', trash: '삭제 확인 창이 열려요' };
  ['open', 'edit', 'move', 'out', 'trash'].forEach((k) => { v['act_' + k] = () => {
    if (k === 'out' && s.menu) { const i = s.menu.i; this.setState({ gone: s.gone.concat([i]) }); }
    this.flash(say[k]); }; });
  if (s.menu) {
    const it = D[s.menu.i]; const x = s.menu.x, w = s.menu.w, ph = s.menu.ph;
    const textH = 24; const cardH = ph + 6 + textH;
    const left = x < W / 2; const MW = 232;
    const menuH = V === 'C' ? 2 * 52 : 64 + 5 * 52; const gap = 10;
    let cx = x, cy = s.menu.y, scale = 1.03, cw = w, ch = ph, showText = true;
    if (V === 'B') {
      cw = 200; ch = Math.round(ph * 200 / w); cx = (W - cw) / 2; showText = false; scale = 1;
      const total = ch + gap + menuH; cy = Math.max(SAFE_T + 20, (HH - total) / 2 - 20);
    }
    if (V === 'A') { cy = 150; scale = 1; showText = false; }
    const quickH = 68; const topPad = V === 'C' ? quickH + gap : 0;
    let visH = (V === 'B' || V === 'A') ? ch : cardH;
    let my = cy + visH + gap;
    if (V !== 'B' && V !== 'A') {
      if (my + menuH > HH - SAFE_B) {
        const above = cy - gap - menuH;
        if (above >= SAFE_T + topPad && V !== 'C') { my = above; }
        else { cy = Math.max(SAFE_T + topPad, HH - SAFE_B - menuH - gap - visH); my = cy + visH + gap; }
      }
      if (cy < SAFE_T + topPad) { cy = SAFE_T + topPad; my = cy + visH + gap; }
    }
    const mx = V === 'B' ? (W - MW) / 2 : (left ? x : x + w - MW);
    const QW = 3 * 72 + 12 + 4; const qx = left ? x : x + w - QW; const qy = cy - gap - quickH + 8;
    v.m = { n: it.n, p: it.p, img: it.img, cx: cx + 'px', cy: cy + 'px', cw: cw + 'px', ch: ch + 'px', scale: scale, origin: left ? 'top left' : 'top right', showText,
            mx: mx + 'px', my: my + 'px', qx: qx + 'px', qy: qy + 'px' };
  } else { v.m = { n: '', p: '', img: '', cx: '0px', cy: '0px', cw: '0px', ch: '0px', scale: 1, origin: 'top left', showText: false, mx: '0px', my: '0px', qx: '0px', qy: '0px' }; }
  return v;
}
}
'''
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
<div data-root="1" style="width: 390px; height: 844px; position: relative; overflow: hidden; background: #F7F7F3; color: #111111">{body}</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview": {{"width": 390, "height": 844}}}}'>{js}</script>
</body>
</html>
'''

if __name__ == '__main__':
    BOARDS = [('LM-A.dc.html', 'A', 'LM-A 길게 누르기 · 좌우는 제자리, 높이는 일정하게')]
    for f, var, t in BOARDS:
        open(OUT + '/' + f, 'w').write(build(var, t))
    print('ok')
