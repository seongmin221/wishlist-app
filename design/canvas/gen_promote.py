# UI 피드백 적용 보드(UI-*)를 확정 보드로 올린다. 보드 이름·제목은 확정 보드 것을 쓴다.
# 사용: python3 gen_promote.py <UI 보드 폴더> <확정 보드 원본 폴더> <출력 폴더>
import os, re, sys
UI, SRC, OUT = sys.argv[1], sys.argv[2], sys.argv[3]; os.makedirs(OUT, exist_ok=True)
APP = ['Flow-Confirmed', 'PurposeDetail', 'P-D', 'CT-D', 'PT-A8', 'A8-AR2', 'AD-F2', 'ARCH-ACT2', 'EH-A', 'EH-B', 'EH-C', 'Flow-Map']

def title(name):
    return re.search(r'<title>[^<]*</title>', open(os.path.join(SRC, name + '.dc.html')).read()).group(0)

for name in APP:
    s = open(os.path.join(UI, 'UI-%s.dc.html' % name)).read()
    s = s.replace('name="UI-', 'name="')
    s = re.sub(r'<title>[^<]*</title>', title(name), s, count=1)
    if name == 'Flow-Map':
        s = s.replace('>UI 피드백 적용 흐름도</h1>', '>확정 디자인 흐름도</h1>')
        s = s.replace('UI 피드백을 적용한 복제 보드를 절반 크기로 불러와 이었어요. 확정 디자인 페이지와 나란히 비교해 보세요.',
                      '확정한 화면을 실제 보드 그대로 절반 크기로 불러와 이었어요. 보드를 고치면 여기도 함께 바뀌어요.')
    assert 'UI-' not in re.sub(r'<title>[^<]*</title>', '', s), name
    open(os.path.join(OUT, name + '.dc.html'), 'w').write(s)
    print(name, 'ok')
