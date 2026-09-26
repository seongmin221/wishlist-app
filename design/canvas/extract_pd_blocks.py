# PD-FINAL 보드에서 목적 상세 시트(메뉴·구매한 상품·확인·이름 바꾸기·목적 지우기)를 꺼내 build/pd_blocks.json으로 저장한다.
import json, os, re
SRC = os.environ.get('CANVAS_SRC', 'source/project') + '/'
s = open(SRC + 'PD-FINAL.dc.html').read()
def block(name):
    i = s.index('<sc-if value="{{ %s }}"' % name); depth = 0
    for m in re.finditer(r'<sc-if\b|</sc-if>', s[i:]):
        depth += 1 if m.group(0) == '<sc-if' else -1
        if depth == 0: return s[i:i + m.end()]
os.makedirs('build', exist_ok=True)
json.dump({n: block(n) for n in ['isMenu', 'isFinishPick', 'isFinish', 'isTitleSheet', 'isDelete']},
          open('build/pd_blocks.json', 'w'), ensure_ascii=False)
print('build/pd_blocks.json')
