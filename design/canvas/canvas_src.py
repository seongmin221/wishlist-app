# 캔버스에서 내려받은 원본 보드(source/project/)에서 공용 조각을 꺼낸다. 상품 정보는 저장소에 두지 않고 여기서 읽는다.
import json, os, re
SRC = os.environ.get('CANVAS_SRC', 'source/project')

def board(name):
    return open(os.path.join(SRC, name + '.dc.html')).read()

def helmet(name='P-D'):
    s = board(name)
    return s[s.index('<helmet>'):s.index('</helmet>') + len('</helmet>')]

def tab_nav(name):
    # 하단 탭(현재 탭 표시 포함). 원본의 이벤트 연결은 뺀다
    s = board(name); i = s.index('<nav aria-label="주요 메뉴"'); j = s.index('</nav>', i) + len('</nav>')
    return re.sub(r' onClick="\{\{[^}]*\}\}"', '', s[i:j])

def purpose_data():
    # PurposeDetail 보드의 상품(D)·목적(P) 데이터
    s = board('PurposeDetail')
    m = re.search(r'data\(\) \{ return \{ D: (\[.*?\]), P: (\{.*?\}), U: ', s, re.S)
    return json.loads(m.group(1)), json.loads(m.group(2))
