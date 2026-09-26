# 기능 흐름 뷰(Flow-Confirmed) 생성기. FLOW 보드를 바탕으로 목적 상세를 PurposeDetail 부품으로 바꾼다.
import os, re, sys
SRC = os.environ.get('CANVAS_SRC', 'source/project') + '/'
OUT = sys.argv[1]
s = open(SRC + 'FLOW.dc.html').read()
def block_span(s, name):
    i = s.index('<sc-if value="{{ %s }}"' % name); depth = 0
    for m in re.finditer(r'<sc-if\b|</sc-if>', s[i:]):
        depth += 1 if m.group(0) == '<sc-if' else -1
        if depth == 0: return i, i + m.end()
for key in ['closet', 'run', 'lamp']:
    a, b = block_span(s, 'p_is_' + key)
    new = ('<sc-if value="{{ p_is_%s }}" hint-placeholder-val="{{ false }}"><div style="position: absolute; inset: 0; z-index: 6">'
           '<dc-import name="PurposeDetail" pkey="%s" start="detail" on-back="{{ p_back }}" on-tab="{{ goTab }}" on-done="{{ p_done }}" hint-size="390px,844px"></dc-import>'
           '</div></sc-if>') % (key, key)
    s = s[:a] + new + s[b:]
s = s.replace('height: 1000px', 'height: 844px').replace('"height": 1000', '"height": 844').replace('blur(2px)', 'blur(6px)')
s = re.sub(r'<title>[^<]*</title>', '<title>기능 흐름 뷰</title>', s, count=1)
js_anchor = "  v.p_go_outer_live = () => this.flash('다시 시작한 목적이에요');"
assert s.count(js_anchor) == 1
s = s.replace(js_anchor, js_anchor + """
  v.goTab = (t) => this.setState({ tab: t, ccat: null, pcat: null, step: null });
  v.p_done = () => this.setState({ pcat: null, pmode: 'live', step: null });""")
open(OUT + '/Flow-Confirmed.dc.html', 'w').write(s)
print('ok', len(s), s.count('dc-import name='), re.search(r'<title>[^<]*', s).group(0))
