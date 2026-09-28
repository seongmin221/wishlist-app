# 컬러풀 팔레트 후보: 중간(M)·강하게(S) 세트 + 짙은 색. 글자색은 면 밝기로 자동 결정.
from oklch import hex2oklch, oklch2hex, contrast
INK, WHITE = '#111111', '#FFFFFF'
M = [('노랑',.905,.140,95),('주황',.830,.120,58),('코랄',.800,.110,28),('분홍',.845,.095,352),('라일락',.820,.085,315),('보라',.790,.100,288),
     ('파랑',.800,.090,258),('하늘',.870,.065,232),('민트',.870,.080,178),('초록',.855,.120,148),('연두',.905,.140,122),
     ('토프',.790,.022,40),('베이지',.870,.035,82),('회색',.860,.004,100)]
S = [('노랑',.890,.175,97),('주황',.740,.175,52),('코랄',.690,.180,25),('분홍',.790,.140,354),('라일락',.740,.140,318),('보라',.660,.170,288),
     ('파랑',.650,.150,258),('하늘',.800,.100,235),('민트',.790,.110,178),('초록',.720,.170,150),('연두',.880,.180,124),
     ('토프',.720,.030,40),('베이지',.840,.045,82),('회색',.820,.004,100)]
DEEP_M = [('올리브',.500,.075,125),('짙은 보라',.470,.120,288),('벽돌',.540,.110,32),('남색',.450,.070,245)]
DEEP_S = [('올리브',.480,.100,125),('짙은 보라',.430,.190,285),('벽돌',.530,.160,30),('남색',.420,.110,250)]
def text_on(h):
    return INK if contrast(h, INK) >= contrast(h, WHITE) else WHITE
def secondary_on(h):
    fg = text_on(h); w = 0.45 if fg == INK else 0.40
    c = [int(h[i:i+2],16) for i in (1,3,5)]; k = [int(fg[i:i+2],16) for i in (1,3,5)]
    for _ in range(8):
        s = '#' + ''.join('%02X' % round(c[j]*w + k[j]*(1-w)) for j in range(3))
        if contrast(s, h) >= 3.0: return s
        w -= 0.05
    return s
def build(spec):
    out = []
    for n, L, C, H in spec:
        h = oklch2hex(L, C, H); L2, C2, H2 = hex2oklch(h)
        out.append(dict(n=n, h=h, fg=text_on(h), sec=secondary_on(h), L=L2, C=C2, H=H2, want=C, deep=L < .6))
    return out
SETS = {'M': build(M), 'MD': build(M + DEEP_M), 'S': build(S), 'SD': build(S + DEEP_S)}
if __name__ == '__main__':
    for k in ['M', 'S']:
        print('==', k)
        for c in SETS[k + 'D']:
            print(f"  {c['n']:5s} {c['h']} L{c['L']:.2f} C{c['C']:.3f}(목표 {c['want']:.3f}) H{c['H']:3.0f} 글자 {c['fg']} {contrast(c['fg'], c['h']):.1f} 둘째 {c['sec']} {contrast(c['sec'], c['h']):.1f}")
