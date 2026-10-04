"""`시각 방향 · 대표 화면`의 목적 보드를 전체 화면 목적 보드와 같은 규칙으로 만든다.

python3 gen_vis.py <out_root>
전체 화면 보드를 그대로 쓰고, 연결만 대표 화면 보드로 바꾼다.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen_purpose import purpose_home, purpose_detail

LINKS = [("FPurposeDetailEmpty", "VisPurposeDetail"), ("FPurposeDetail", "VisPurposeDetail"), ("FPurposeHome", "VisPurpose"),
         ("FCategoryHome", "VisCategory"), ("FHome", "VisHome"), ("FProductDetail{}.dc.html", "#"), ("FArchiveList{}.dc.html", "#")]


def relink(html, th):
    for a, b in LINKS:
        if "{}" in a:
            html = html.replace(a.format(th), b)
        else:
            html = html.replace(a + th + ".dc.html", b + th + ".dc.html")
    return html


def main():
    out = os.path.join(sys.argv[1], "project")
    os.makedirs(out, exist_ok=True)
    for th, nm in (("L", "light"), ("D", "dark")):
        with open(os.path.join(out, f"VisPurpose{th}.dc.html"), "w") as f:
            f.write(relink(purpose_home(th, f"목적 첫 화면 시각 {nm}", "{}"), th))
        with open(os.path.join(out, f"VisPurposeDetail{th}.dc.html"), "w") as f:
            f.write(relink(purpose_detail(th, f"목적 상세 시각 {nm}", "{}"), th))
    print("VisPurposeL VisPurposeD VisPurposeDetailL VisPurposeDetailD")


if __name__ == "__main__":
    main()
