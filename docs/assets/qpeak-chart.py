# README 전환 곡선 그림: IMP-050 측정 세션(20261027-qpeak)의 실측 노드 수·대기열 Pod 수(새 측정 없음).
#   python docs/assets/qpeak-chart.py  → docs/assets/qpeak-cycle-light.png, qpeak-cycle-dark.png
# 입력: imp050-qpeak-nodes.csv(레포 밖 원자료 artifacts/loadtest/20261027-qpeak/memory/의 Prometheus 내보내기, 30초 간격).
#   nodes: kube_node_status_allocatable 시계열이 있는 노드 수(클러스터에 등록된 노드)
#   queue_pods: flowticket-api-queue Pod의 requests 시계열 수(롤링 중에는 옛·새 Pod를 함께 센다)
import csv
from datetime import datetime, timedelta, timezone
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

HERE = Path(__file__).parent
KST = timezone(timedelta(hours=9))
# 00:33Z 전은 kube-state-metrics가 Pod 시계열을 아직 다 만들지 않은 구간이라 뺀다(대기열 0~1로 보임).
rows = [r for r in csv.DictReader(open(HERE / "imp050-qpeak-nodes.csv", encoding="utf-8")) if r["utc"] >= "2026-10-27T00:33:00Z"]


def hours(utc):
    d = datetime.strptime(utc, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc).astimezone(KST)
    return d.hour + d.minute / 60 + d.second / 3600


t = [hours(r["utc"]) for r in rows]
nodes = [int(r["nodes"]) for r in rows]
queue = [int(r["queue_pods"]) for r in rows]

# session.log의 수동 동기화 시작 시각과 V4 run의 오픈 시각(T0)
EVENTS = [
    ("2026-10-27T01:54:55Z", "사전 확장 PR\n(피크 모양·하한 6)"),
    ("2026-10-27T04:57:17Z", "하한 2 PR"),
    ("2026-10-27T05:10:46Z", "평시 모양 PR"),
]
OPENS = ["2026-10-27T02:59:10Z", "2026-10-27T03:44:34Z", "2026-10-27T04:06:10Z", "2026-10-27T04:30:10Z"]

THEMES = {
    # scaling-chart.py와 같은 팔레트
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
    o = [hours(x) for x in OPENS]
    ax.axvspan(o[0] - 0.05, o[-1] + 0.2, color=c["band"], zorder=0)
    ax.text((o[0] + o[-1]) / 2, 10.35, "10만 명/10초 오픈 부하(유효 run 4회)", ha="center", va="bottom", fontsize=9, color=c["ink2"])
    for x in o:
        ax.axvline(x, color=c["base"], lw=0.8, ls=(0, (1, 2)), zorder=1)
    for i, (utc, label) in enumerate(EVENTS):
        x = hours(utc)
        ax.axvline(x, color=c["base"], lw=1, ls=(0, (3, 3)), zorder=1)
        ha, dx = ("left", 0.04) if i != 1 else ("right", -0.04)
        ax.text(x + dx, 0.3, label, ha=ha, va="bottom", fontsize=8.5, color=c["ink2"], linespacing=1.1)
    ax.step(t, nodes, where="post", lw=2, color=c["s2"], label="노드", zorder=3)
    ax.step(t, queue, where="post", lw=2, color=c["s1"], label="대기열 Pod", zorder=4)
    ax.text(t[-1] + 0.05, nodes[-1] + 0.25, "노드 3", color=c["ink"], fontsize=9, va="bottom")
    ax.text(t[-1] + 0.05, queue[-1] - 0.25, "대기열 2", color=c["ink"], fontsize=9, va="top")
    ax.text(t[0] + 0.05, 3.25, "평시 3대", color=c["ink2"], fontsize=8.5, va="bottom")
    # 노드 9 → 5는 하한 2 PR 동기화(04:57:17Z) 뒤 CA 축소(05:09:49Z)로 평시 모양 PR보다 먼저다(IMP-050 V6)
    ax.text(hours("2026-10-27T05:12:00Z"), 5.3, "5대: 하한 2 PR\n뒤 12분 32초", ha="left", va="bottom", color=c["ink2"],
            fontsize=8, linespacing=1.1)
    ax.text(hours("2026-10-27T01:16:30Z"), 4.25, "분산 수정 반영 롤링\n(잠깐 4대)", color=c["ink2"], fontsize=8, va="bottom",
            linespacing=1.1)
    ax.set_xlim(t[0], t[-1])
    ax.set_ylim(0, 10)
    ax.set_yticks(range(0, 11, 2))
    ticks = [h for h in range(10, 15)]
    ax.set_xticks(ticks)
    ax.set_xticklabels([f"{h}:00" for h in ticks])
    ax.set_xlabel("KST", color=c["muted"], fontsize=9)
    ax.set_ylabel("개수", color=c["muted"], fontsize=9)
    ax.tick_params(colors=c["muted"], labelsize=8.5, length=0)
    ax.grid(axis="y", color=c["grid"], lw=0.8)
    for side in ["top", "right", "left"]:
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(c["base"])
    leg = ax.legend(loc="upper left", bbox_to_anchor=(0, -0.16), ncol=2, frameon=False, fontsize=9)
    for txt in leg.get_texts():
        txt.set_color(c["ink"])
    fig.text(0.01, 0.97, "대기열 평시·피크 모양 전환(EKS, 2026-10-27): 노드·대기열 Pod 수(실측)", color=c["ink"], fontsize=11,
             fontweight="bold", va="top")
    fig.text(0.01, 0.915, "Prometheus 30초 간격. 노드는 등록된 수, 대기열 Pod는 롤링 중 옛·새 Pod를 함께 센 수. 출처: IMP-050",
             color=c["muted"], fontsize=8, va="top")
    fig.subplots_adjust(left=0.06, right=0.92, top=0.8, bottom=0.24)
    fig.savefig(HERE / f"qpeak-cycle-{name}.png", facecolor=c["surface"])
    plt.close(fig)
print("ok")
