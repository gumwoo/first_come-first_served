# README 확장 곡선 그림 — IMP-031 판정 run pf-r2의 실측 api Pod 수·Ready 노드 수(새 측정 없음).
#   python docs/assets/scaling-chart.py  → docs/assets/scaling-pf-r2-light.png, scaling-pf-r2-dark.png
# 입력: imp031-pf-r2-scaling.csv(레포 밖 원자료 artifacts/loadtest/20261007-platform/에서 뽑음).
#   T0 −910~−775초: prescale-ready.txt(15초 해상도, api는 가용 replicas)
#   T0 −161초 이후: platform-timeline.log(20초 해상도, api는 HPA 현재 replicas)
import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).parent
rows = list(csv.DictReader(open(HERE / "imp031-pf-r2-scaling.csv", encoding="utf-8")))
t = [int(r["t_s"]) / 60 for r in rows]
api = [int(r["api_pods"]) for r in rows]
nodes = [int(r["ready_nodes"]) for r in rows]

THEMES = {
    # dataviz 기준 팔레트(검증: 범주 2색 light·dark 모두 통과)
    "light": dict(surface="#fcfcfb", ink="#0b0b0b", ink2="#52514e", muted="#898781", grid="#e1e0d9",
                  base="#c3c2b7", band="#f1efe8", s1="#2a78d6", s2="#eb6834"),
    "dark": dict(surface="#1a1a19", ink="#ffffff", ink2="#c3c2b7", muted="#898781", grid="#2c2c2a",
                 base="#383835", band="#262624", s1="#3987e5", s2="#d95926"),
}
from matplotlib import font_manager

_have = {f.name for f in font_manager.fontManager.ttflist}
plt.rcParams["font.family"] = [f for f in ["Malgun Gothic", "AppleGothic", "NanumGothic"] if f in _have] or ["DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False

for name, c in THEMES.items():
    fig, ax = plt.subplots(figsize=(10, 4.2), dpi=150)
    fig.patch.set_facecolor(c["surface"])
    ax.set_facecolor(c["surface"])
    ax.axvspan(900 / 60, 1380 / 60, color=c["band"], zorder=0)
    ax.text(1140 / 60, 10.35, "폴링 폭증 7,000/s", ha="center", va="bottom", fontsize=9, color=c["ink2"])
    for x, label in [(-883 / 60, "사전 확장\nPR 머지"), (0, "오픈\n10만 명/10초"), (60 / 60, "")]:
        ax.axvline(x, color=c["base"], lw=1, ls=(0, (3, 3)), zorder=1)
        if label:
            ax.text(x + 0.4, 10.35, label, ha="left", va="bottom", fontsize=8.5, color=c["ink2"], linespacing=1.1)
    ax.text(60 / 60 + 0.4, 1.0, "사전 확장 끄기 PR\n(HPA·CA에 인계)", ha="left", va="bottom", fontsize=8.5, color=c["ink2"],
            linespacing=1.1)
    ax.step(t, nodes, where="post", lw=2, color=c["s2"], label="Ready 노드", zorder=3)
    ax.step(t, api, where="post", lw=2, color=c["s1"], label="api Pod", zorder=4)
    ax.text(t[-1] + 0.6, nodes[-1] + 0.25, "Ready 노드 4", color=c["ink"], fontsize=9, va="bottom")
    ax.text(t[-1] + 0.6, api[-1] - 0.25, "api Pod 3", color=c["ink"], fontsize=9, va="top")
    ax.set_xlim(-16, 56)
    ax.set_ylim(0, 10)
    ax.set_yticks(range(0, 11, 2))
    ax.set_xlabel("오픈(T0) 기준 분", color=c["muted"], fontsize=9)
    ax.set_ylabel("개수", color=c["muted"], fontsize=9)
    ax.tick_params(colors=c["muted"], labelsize=8.5, length=0)
    ax.grid(axis="y", color=c["grid"], lw=0.8)
    for side in ["top", "right", "left"]:
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(c["base"])
    leg = ax.legend(loc="upper left", bbox_to_anchor=(0, -0.16), ncol=2, frameon=False, fontsize=9)
    for txt in leg.get_texts():
        txt.set_color(c["ink"])
    fig.text(0.01, 0.97, "통합 구조 10만 명 판정 run(pf-r2) — api Pod·Ready 노드 수(실측)", color=c["ink"], fontsize=11,
             fontweight="bold", va="top")
    fig.text(0.01, 0.915, "사전 확장 구간은 15초 해상도·api 가용 수, 이후는 20초 해상도·HPA 현재 replicas. 출처: IMP-031",
             color=c["muted"], fontsize=8, va="top")
    fig.subplots_adjust(left=0.06, right=0.9, top=0.8, bottom=0.24)
    fig.savefig(HERE / f"scaling-pf-r2-{name}.png", facecolor=c["surface"])
    plt.close(fig)
print("ok")
