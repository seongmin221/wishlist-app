# PurposeDetail.dc.html: confirmed purpose-detail component (EP-D + PF-5 + LM-A + PD-FINAL sheets)
import json, os, re, sys
SRC = os.environ.get('CANVAS_SRC', 'source/project') + '/'
OUT = sys.argv[1]
sys.argv = [sys.argv[0], OUT]
_lm = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'gen_longpress.py')).read()
exec(_lm[:_lm.index('\ndef build(')])        # also pulls gen_pg helpers

B = json.load(open(os.environ.get('PD_BLOCKS', 'build/pd_blocks.json')))
pf5 = open(SRC + 'PF-5.dc.html').read()
_tok = '<div class="scroll" style="position: absolute; left: 0; right: 0; bottom: 86px;'
_a = pf5.index(_tok); CHIPS = pf5[_a:pf5.index('</sc-for></div>', _a) + len('</sc-for></div>')]

def rep(s, a, b, n=1):
    assert s.count(a) == n, (a[:90], s.count(a)); return s.replace(a, b)

# ---------- finish: pick card rows -> sc-for
fp = B['isFinishPick']
lo = '<div style="display: flex; flex-direction: column; max-height: 420px; overflow-y: auto">'
i = fp.index(lo) + len(lo); j = fp.index('</div><button onClick="{{ toFinish }}"')
ROWP = ('<sc-for list="{{ fRows }}" as="r" hint-placeholder-count="3"><button onClick="{{ r.pick }}" role="radio" aria-checked="{{ r.checked }}" style="min-height: 76px; border: 0; border-top: {{ r.bt }}; background: transparent; color: #111111; display: flex; align-items: center; gap: 14px; padding: 0; text-align: left; width: 100%">'
        '<span style="width: 56px; height: 56px; border-radius: 12px; overflow: hidden; background: #FFFFFF; display: inline-flex; flex-shrink: 0"><img src="{{ r.img }}" alt="" style="width: 100%; height: 100%; object-fit: cover; display: block"></span>'
        '<span style="display: flex; flex-direction: column; gap: 4px; flex-grow: 1; min-width: 0"><span style="font-size: 16px; font-weight: 700">{{ r.n }}</span><span style="font-size: 14px; color: rgba(17,17,17,.62)">{{ r.p }} · {{ r.s }}</span></span>'
        '<span style="width: 24px; height: 24px; border-radius: 999px; box-sizing: border-box; border: {{ r.border }}; background: #FFFFFF; flex-shrink: 0"></span></button></sc-for>')
FINISH_PICK = fp[:i] + ROWP + fp[j:]

# ---------- finish: confirm card avatars / counts
fn = B['isFinish']
fn = re.sub(r'목적과 후보 \d+개를 함께', '목적과 후보 {{ n }}개를 함께', fn)
a = fn.index('<span style="display: flex"><span style="display: inline-flex; margin-left: 0px;')
b = fn.index('</span><span style="font-size: 14px; font-weight: 700">후보 ')
AV = ('<span style="display: flex"><sc-for list="{{ heads }}" as="h" hint-placeholder-count="3"><span style="display: inline-flex; margin-left: {{ h.ml }}; border-radius: 999px; box-shadow: 0 0 0 2px #FFFFFF"><span style="width: 40px; height: 40px; border-radius: 999px; overflow: hidden; background: #FFFFFF; display: inline-flex; flex-shrink: 0"><img src="{{ h.img }}" alt="" style="width: 100%; height: 100%; object-fit: cover; display: block"></span></span></sc-for>'
      '<sc-if value="{{ hasMore }}" hint-placeholder-val="{{ false }}"><span style="margin-left: -10px; height: 40px; min-width: 40px; padding: 0 10px; box-sizing: border-box; border-radius: 999px; background: #F2F2EE; box-shadow: 0 0 0 2px #FFFFFF; font-family: \'Space Grotesk\', Pretendard, sans-serif; font-size: 13px; display: inline-flex; align-items: center; justify-content: center">+{{ more }}</span></sc-if>')
fn = fn[:a] + AV + fn[b:]
fn = re.sub(r'<span style="font-size: 14px; font-weight: 700">후보 \d+개</span>', '<span style="font-size: 14px; font-weight: 700">후보 {{ n }}개</span>', fn)
FINISH = fn

# ---------- delete sheet rows -> sc-for
dl = B['isDelete']
dl = re.sub(r'후보 \d+개가 목적 미지정으로', '후보 {{ n }}개가 목적 미지정으로', dl)
lo = '<div style="display: flex; flex-direction: column; max-height: 300px; overflow-y: auto">'
i = dl.index(lo) + len(lo); j = dl.index('</div></div>', dl.rindex('목적 미지정으로 바뀌어요')) + 6
ROWD = ('<sc-for list="{{ dRows }}" as="r" hint-placeholder-count="3"><div style="display: flex; align-items: center; gap: 14px; min-height: 72px; border-top: {{ r.bt }}">'
        '<span style="width: 48px; height: 48px; border-radius: 12px; overflow: hidden; background: #FFFFFF; display: inline-flex; flex-shrink: 0"><img src="{{ r.img }}" alt="" style="width: 100%; height: 100%; object-fit: cover; display: block"></span>'
        '<span style="display: flex; flex-direction: column; gap: 4px"><span style="font-size: 15px; font-weight: 700">{{ r.n }}</span><span style="font-size: 13px; color: rgba(17,17,17,.62)">목적 미지정으로 바뀌어요</span></span></div></sc-for>')
DELETE = dl[:i] + ROWD + dl[j:]
MENU = B['isMenu']; TITLE = B['isTitleSheet']

# ---------- nav with tab callbacks
NAV = nav
NAV = rep(NAV, '<button aria-label="홈"', '<button onClick="{{ tabHome }}" aria-label="홈"')
NAV = rep(NAV, '<button aria-label="카테고리"', '<button onClick="{{ tabCat }}" aria-label="카테고리"')
NAV = rep(NAV, '<button aria-current="page"', '<button onClick="{{ tabPurpose }}" aria-current="page"')

TOPBAR = topbar.replace('<button style="height: 44px; padding: 0 10px 0 0;', '<button onClick="{{ back }}" style="height: 44px; padding: 0 10px 0 0;', 1)
TOPBAR = TOPBAR.replace('<button aria-label="목적 메뉴"', '<button onClick="{{ openMenu }}" aria-label="목적 메뉴"', 1)
assert '{{ back }}' in TOPBAR and '{{ openMenu }}' in TOPBAR, TOPBAR[:400]

FINISH_BTN = ('<sc-if value="{{ hasCands }}" hint-placeholder-val="{{ true }}"><div style="position: absolute; left: 20px; right: 20px; bottom: 96px; z-index: 3"><button onClick="{{ startFinish }}" style="border-radius: 999px; border: 0; background: #111111; color: #FFFFFF; font-size: 13px; font-weight: 700; display: flex; align-items: center; justify-content: center; gap: 8px; box-shadow: 0 8px 24px -8px rgba(17,17,17,.4); margin-left: auto; margin-right: auto; padding: 12px 16px; min-height: 44px; box-sizing: border-box">' + ARCH + '비교 끝내기</button></div></sc-if>')

DETAIL = ('<div style="height: 844px; display: flex; flex-direction: column">'
  '<div style="padding: 58px 20px 0; display: flex; flex-direction: column; gap: 18px; flex-shrink: 0">' + TOPBAR + h1('{{ pname }} 후보 {{ count }}개', '{{ line2 }}') + '</div>'
  '<div style="flex-shrink: 0">' + label_row('최근 저장순', '{{ count }}') + '</div>'
  '<div class="scroll" style="flex: 1 1 auto; min-height: 0; overflow-y: auto; overscroll-behavior: contain"><div style="padding: 14px 6px 200px"><div style="display: flex; gap: 4px; align-items: flex-start">'
  + dcol('dL', 'addL').replace('{{ addTap }}', '{{ openPicker }}') + dcol('dR', 'addR').replace('{{ addTap }}', '{{ openPicker }}') + '</div></div></div></div>'
  + FINISH_BTN + NAV)

PICKER = ('<sc-if value="{{ isPicker }}" hint-placeholder-val="{{ false }}"><div style="position: absolute; inset: 0; z-index: 7; background: #F7F7F3; display: flex; flex-direction: column">'
  '<div style="padding: 58px 20px 0; display: flex; flex-direction: column; gap: 18px; flex-shrink: 0">'
  '<div style="height: 44px; display: flex; align-items: center; justify-content: space-between"><span style="font-size: 14px; font-weight: 700">후보 추가</span>'
  f'<button onClick="{{{{ closePicker }}}}" aria-label="닫기" style="width: 36px; height: 36px; border-radius: 999px; border: 0; background: #ECECE6; color: #111111; display: flex; align-items: center; justify-content: center">{CLOSE}</button></div>'
  + h1('{{ pname }}에 넣을 상품을', '골라 주세요') + '</div>'
  '<div style="flex-shrink: 0">' + FILTERS['E'] + label_row('최근 저장순', '{{ shown }}', '16px 16px 0') + '</div>'
  '<div class="scroll" style="flex: 1 1 auto; min-height: 0; overflow-y: auto; overscroll-behavior: contain"><div style="padding: 14px 6px 220px">'
  '<sc-if value="{{ noResult }}" hint-placeholder-val="{{ false }}"><div style="padding: 40px 14px; font-size: 14px; color: rgba(17,17,17,.62)">이 조건에 맞는 상품이 없어요.</div></sc-if>'
  '<div style="display: flex; gap: 4px; align-items: flex-start">' + pcol('pL') + pcol('pR') + '</div></div></div>'
  '<sc-if value="{{ hasDraft }}" hint-placeholder-val="{{ false }}">' + CHIPS + '</sc-if>'
  '<div style="position: absolute; left: 20px; right: 20px; bottom: 28px; z-index: 3"><button onClick="{{ addPicked }}" aria-disabled="{{ addOff }}" style="height: 54px; width: 100%; border-radius: 999px; border: 0; background: {{ addBg }}; color: {{ addFg }}; font-size: 15px; font-weight: 700">{{ addLabel }}</button></div>'
  + SHEET + '</div></sc-if>')

BODY = (DETAIL + overlay('A').replace('{{ isMenu }}', '{{ isLp }}') + MENU + FINISH_PICK + FINISH + TITLE + DELETE + PICKER +
  '<sc-if value="{{ showToast }}" hint-placeholder-val="{{ false }}"><div role="status" style="position: absolute; left: 20px; right: 20px; bottom: 96px; z-index: 20; height: 52px; border-radius: 999px; background: #111111; color: #FFFFFF; display: flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 700">{{ toastText }}</div></sc-if>')
BODY = BODY.replace('blur(2px)', 'blur(6px)')

ITEMS = [{k: it[k] for k in ('i', 'n', 'p', 'img', 'h', 'c', 's')} for it in I]
PURPOSES = {
  'closet': {'name': '가을 옷장', 'desc': '출근할 때 입을 옷', 'items': [1, 9, 5, 7, 3]},
  'run':    {'name': '가을 트레일 러닝', 'desc': '주말 산길용', 'items': [0, 4]},
  'lamp':   {'name': '거실 조명', 'desc': '', 'items': []},
}
UNASSIGNED = [2, 6, 8, 10, 11]

JS = '''
class Component extends DCLogic {
componentDidMount() { this.fit(); this.applyStart(); }
componentDidUpdate(pp) { this.fit(); if (pp && (pp.pkey !== this.props.pkey || pp.start !== this.props.start)) this.applyStart(); }
fit() {
  const run = () => document.querySelectorAll('[data-fit]').forEach((el) => {
    el.style.fontSize = ''; let size = parseFloat(getComputedStyle(el).fontSize);
    while (el.scrollWidth > el.clientWidth + 0.5 && size > 12) { size -= 0.5; el.style.fontSize = size + 'px'; }
  });
  run(); if (document.fonts && document.fonts.ready) document.fonts.ready.then(run);
}
state = { key: null, pname: '', desc: '', members: [], pool: [], step: null, menu: null, pickDraft: null, picker: false, draft: [], cats: [], catsOpen: false, catDraft: [], nameDraft: '', descDraft: '', toast: null };
data() { return { D: ''' + json.dumps(ITEMS, ensure_ascii=False) + ''', P: ''' + json.dumps(PURPOSES, ensure_ascii=False) + ''', U: ''' + json.dumps(UNASSIGNED) + ''' }; }
applyStart() {
  const { D, P, U } = this.data(); const key = P[this.props.pkey] ? this.props.pkey : 'closet'; const p = P[key];
  const st = { key, pname: p.name, desc: p.desc, members: p.items.slice(), pool: U.slice(), step: null, menu: null, picker: false, draft: [], cats: [], catsOpen: false, pickDraft: null };
  const start = this.props.start || 'detail';
  if (start === 'picker') { st.picker = true; st.draft = U.slice(0, 2); }
  if (start === 'menu' && p.items.length) { const it = D[p.items[0]]; st.menu = { i: it.i, x: 6, y: 150, w: 187, ph: it.h }; }
  if (start === 'finish' && p.items.length) { st.step = 'finishPick'; }
  this.setState(st);
}
flash(text) { this.setState({ step: null, menu: null, toast: text }); clearTimeout(this._t); this._t = setTimeout(() => this.setState({ toast: null }), 1800); }
componentWillUnmount() { clearTimeout(this._t); clearTimeout(this._lp); }
split(list) { const L = [], R = []; let hl = 0, hr = 0; list.forEach((it) => { if (hl <= hr) { L.push(it); hl += it.h + 40; } else { R.push(it); hr += it.h + 40; } }); return { L, R, addL: hl <= hr }; }
open(it, el) {
  const root = el.closest('[data-root]'); if (!root) return;
  const rr = root.getBoundingClientRect(); const k = rr.width / 390; const r = el.getBoundingClientRect();
  this._justOpened = true; setTimeout(() => { this._justOpened = false; }, 400);
  this.setState({ menu: { i: it.i, x: (r.left - rr.left) / k, y: (r.top - rr.top) / k, w: r.width / k, ph: it.h } });
}
renderVals() {
  const s = this.state; const { D } = this.data(); const p = this.props;
  const CATS = ''' + json.dumps(CATS, ensure_ascii=False) + ''';
  const v = {}; const withPx = (it) => Object.assign({}, it, { hpx: it.h + 'px' });
  v.pname = s.pname; v.line2 = s.desc || '후보를 골라 넣어 볼까요?';
  const mem = s.members.map((i) => D[i]);
  const list = mem.map((it) => Object.assign(withPx(it), {
    press: (e) => { const el = e.currentTarget; clearTimeout(this._lp); this._lp = setTimeout(() => this.open(it, el), 450); },
    release: () => clearTimeout(this._lp),
    ctx: (e) => { e.preventDefault(); clearTimeout(this._lp); this.open(it, e.currentTarget); },
    tap: () => { if (this._justOpened) return; this.flash('상품 화면으로 이동해요'); } }));
  const d = this.split(list); v.dL = d.L; v.dR = d.R; v.addL = d.addL; v.addR = !d.addL;
  v.count = mem.length; v.n = mem.length; v.hasCands = mem.length > 0;
  v.back = () => { if (p.onBack) p.onBack(); else this.flash('목적 목록으로 돌아가요'); };
  v.tabHome = () => p.onTab && p.onTab('home'); v.tabCat = () => p.onTab && p.onTab('cat'); v.tabPurpose = () => p.onTab && p.onTab('purpose');
  v.showToast = !!s.toast; v.toastText = s.toast || '';
  v.closeAll = () => this.setState({ step: null });
  // long-press menu (LM-A: fixed height 150, left/right follows column)
  v.isLp = !!s.menu && !s.step; v.closeMenu = () => this.setState({ menu: null });
  const say = { open: '원본 페이지를 열어요', edit: '상품 정보 수정 화면을 열어요', move: '옮길 목적을 고르는 창이 열려요', out: '목적에서 뺐어요. 목적 미지정으로 옮겼어요', trash: '삭제 확인 창이 열려요' };
  ['open', 'edit', 'move', 'out', 'trash'].forEach((k) => { v['act_' + k] = () => {
    if (k === 'out' && s.menu) { const i = s.menu.i; this.setState({ members: s.members.filter((x) => x !== i), pool: s.pool.concat([i]) }); }
    this.flash(say[k]); }; });
  if (s.menu) { const it = D[s.menu.i]; const left = s.menu.x < 195; const MW = 232; const cy = 150;
    v.m = { n: it.n, p: it.p, img: it.img, cx: s.menu.x + 'px', cy: cy + 'px', cw: s.menu.w + 'px', ch: it.h + 'px', scale: 1, origin: 'top left', showText: false,
            mx: (left ? s.menu.x : s.menu.x + s.menu.w - MW) + 'px', my: (cy + it.h + 10) + 'px', qx: '0px', qy: '0px' }; }
  else { v.m = { n: '', p: '', img: '', cx: '0px', cy: '0px', cw: '0px', ch: '0px', scale: 1, origin: 'top left', showText: false, mx: '0px', my: '0px', qx: '0px', qy: '0px' }; }
  // purpose menu, edit, delete (PD-FINAL sheets)
  v.isMenu = s.step === 'menu'; v.openMenu = () => this.setState({ step: 'menu', menu: null });
  v.isTitleSheet = s.step === 'edit'; v.isDelete = s.step === 'delete';
  v.openEdit = () => this.setState({ step: 'edit', nameDraft: s.pname, descDraft: s.desc });
  v.draft = s.nameDraft; v.ddraft = s.descDraft; v.draftLen = (s.nameDraft || '').length; v.ddraftLen = (s.descDraft || '').length;
  v.typeDraft = (e) => this.setState({ nameDraft: e.target.value.slice(0, 40) }); v.typeDesc = (e) => this.setState({ descDraft: e.target.value.slice(0, 200) });
  v.cancelEdit = () => this.setState({ step: null });
  v.saveEdit = () => { const t = (s.nameDraft || '').trim(); if (!t) return; this.setState({ pname: t, desc: (s.descDraft || '').trim() }); this.flash('이름과 설명을 바꿨어요'); };
  v.askDelete = () => this.setState({ step: 'delete' });
  v.dRows = mem.map((it, k) => Object.assign({}, it, { bt: k === 0 ? '0' : '1px solid rgba(17,17,17,.08)' }));
  v.doDelete = () => { this.flash('목적을 지웠어요. 후보는 목적 미지정이 됐어요'); if (p.onBack) setTimeout(() => p.onBack(), 900); };
  // finish flow
  v.isFinishPick = s.step === 'finishPick'; v.isFinish = s.step === 'finish';
  v.startFinish = () => this.setState({ step: 'finishPick', pickDraft: null, menu: null });
  v.fRows = mem.map((it, k) => { const on = s.pickDraft === it.i; return Object.assign({}, it, { bt: k === 0 ? '0' : '1px solid rgba(17,17,17,.08)', checked: on ? 'true' : 'false',
    border: on ? '7px solid #111111' : '2px solid rgba(17,17,17,.25)', pick: () => this.setState({ pickDraft: it.i }) }); });
  const noDraft = s.pickDraft === null || s.pickDraft === -1;
  v.nextOff = noDraft ? 'true' : 'false'; v.nextBg = noDraft ? '#E4E4DE' : '#111111'; v.nextFg = noDraft ? '#9C9C95' : '#FFFFFF';
  v.toFinish = () => { if (noDraft) return; this.setState({ step: 'finish' }); };
  v.skipFinish = () => this.setState({ step: 'finish', pickDraft: -1 });
  v.finishBack = () => this.setState({ step: 'finishPick' });
  v.pickBack = () => this.setState({ step: null });
  const has = !noDraft; v.hasPick = has; v.hasDraft = s.draft.length > 0; v.noPick = !has;
  const pk = has ? D[s.pickDraft] : null; v.pickName = pk ? pk.n : ''; v.pickPrice = pk ? pk.p : ''; v.pickImg = pk ? pk.img : '';
  v.heads = mem.slice(0, 4).map((it, k) => ({ img: it.img, ml: k === 0 ? '0px' : '-10px' })); v.more = Math.max(0, mem.length - 4); v.hasMore = v.more > 0;
  v.doFinish = () => { this.flash('아카이브에 보관했어요'); if (p.onDone) setTimeout(() => p.onDone(), 900); };
  // picker (PF-5)
  v.isPicker = s.picker;
  v.openPicker = () => this.setState({ picker: true, draft: [], cats: [], catsOpen: false, menu: null });
  v.closePicker = () => this.setState({ picker: false });
  const pool = s.pool.map((i) => D[i]);
  const pass = (it) => s.cats.length === 0 || s.cats.indexOf(it.c) !== -1;
  const shownList = pool.filter(pass).map((it) => { const on = s.draft.indexOf(it.i) !== -1; return Object.assign(withPx(it), {
    on, pressed: on ? 'true' : 'false', ring: 'none', badgeBg: on ? '#111111' : 'rgba(255,255,255,.85)', badgeBorder: on ? '0' : '1.5px solid rgba(17,17,17,.25)',
    toggle: () => this.setState({ draft: on ? s.draft.filter((x) => x !== it.i) : s.draft.concat([it.i]) }) }); });
  const pp = this.split(shownList); v.pL = pp.L; v.pR = pp.R; v.shown = shownList.length; v.noResult = shownList.length === 0;
  const cnt = (c) => pool.filter((it) => it.c === c).length;
  v.catPills = CATS.filter((c) => cnt(c) > 0).map((c) => { const on = s.cats.indexOf(c) !== -1;
    return { label: c, on, pressed: on ? 'true' : 'false', bg: on ? '#111111' : '#E9E9E3', fg: on ? '#FFFFFF' : '#111111', ring: 'none',
      go: () => this.setState({ cats: on ? s.cats.filter((x) => x !== c) : s.cats.concat([c]) }) }; });
  v.isCats = s.catsOpen; v.openCats = () => this.setState({ catsOpen: true, catDraft: s.cats.slice() });
  v.closeCats = () => this.setState({ catsOpen: false }); v.clearCats = () => this.setState({ catDraft: [] });
  v.applyCats = () => this.setState({ catsOpen: false, cats: s.catDraft.slice() });
  v.catGroups = [{ name: '패션·잡화', items: CATS.filter((c) => cnt(c) > 0).map((c) => { const on = s.catDraft.indexOf(c) !== -1;
    return { label: c, count: cnt(c), on, checked: on ? 'true' : 'false', bg: on ? '#111111' : '#FFFFFF', border: on ? '0' : '2px solid rgba(17,17,17,.25)',
      toggle: () => this.setState({ catDraft: on ? s.catDraft.filter((x) => x !== c) : s.catDraft.concat([c]) }) }; }) }];
  v.applyLabel = pool.filter((it) => s.catDraft.length === 0 || s.catDraft.indexOf(it.c) !== -1).length + '개 보기';
  v.picked = s.draft.map((i) => Object.assign({}, D[i], { remove: () => this.setState({ draft: s.draft.filter((x) => x !== i) }) }));
  const nn = s.draft.length; const off = nn === 0;
  v.addOff = off ? 'true' : 'false'; v.addBg = off ? '#E4E4DE' : '#111111'; v.addFg = off ? '#9C9C95' : '#FFFFFF';
  v.addLabel = off ? '후보로 넣을 상품을 골라 주세요' : nn + '개 후보로 넣기';
  v.addPicked = () => { if (off) return; this.setState({ picker: false, members: s.members.concat(s.draft), pool: s.pool.filter((x) => s.draft.indexOf(x) === -1), draft: [] }); this.flash(nn + '개를 후보로 넣었어요'); };
  return v;
}
}
'''
PROPS = {"pkey": {"editor": "enum", "options": ["closet", "run", "lamp"], "default": "closet"},
         "start": {"editor": "enum", "options": ["detail", "picker", "menu", "finish"], "default": "detail"},
         "onBack": {"editor": None}, "onTab": {"editor": None}, "onDone": {"editor": None},
         "$preview": {"width": 390, "height": 844}}
html = f'''<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>목적 상세 (확정)</title>
<script src="./support.js"></script>
</head>
<body>
<x-dc>
{helmet}
<div data-root="1" style="width: 390px; height: 844px; position: relative; overflow: hidden; background: #F7F7F3; color: #111111">{BODY}</div>
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{json.dumps(PROPS, ensure_ascii=False)}'>{JS}</script>
</body>
</html>
'''
open(OUT + '/PurposeDetail.dc.html', 'w').write(html)
print('ok', len(html))
