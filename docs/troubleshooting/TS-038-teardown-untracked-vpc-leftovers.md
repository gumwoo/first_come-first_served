# TS-038 · terraform이 모르는 자원이 VPC 삭제를 막았다 — 그리고 실패가 성공으로 보였다

- 슬라이스: S09(EKS 배포) — 인프라
- 날짜: 2026-08-26
- 유형: 철거 사건 회고
- 관련: `scripts/tear-down.sh`, `docs/deployment/terraform-design.md` §6, [[IMP-022]](같은 세션의 측정)
- 상태: **해결 · 스크립트에 반영** — 다만 실패 경로는 재현 검증하지 못했다(§6)

## 1. 무슨 일이 있었나

IMP-022 측정이 끝나고 `scripts/tear-down.sh`로 철거했다. 스크립트는 끝까지 돌았고
잔여 점검은 대부분 0을 찍었다. 그런데 **`VPC 1`이 남아 있었다.**

```
Error: deleting EC2 Subnet (subnet-02c1dcc4dd4557c45):
  DependencyViolation: The subnet has dependencies and cannot be deleted.
    terraform 종료 코드: 1
```

`terraform destroy`가 서브넷에서 19분을 버티다 실패했다.

## 2. 원인 — 둘 다 terraform이 만들지 않았다

서브넷을 붙잡고 있던 것을 하나씩 찾았다.

### ① VPC CNI가 남긴 고아 ENI

```
eni-0c9cfb88b2c181730   available   aws-K8S-i-0b200e0fc5d95d6c3
```

노드가 사라지면서 detach는 됐는데 **회수되지 않았다.** `available` 상태라 과금은 없지만
서브넷 삭제를 막는다.

### ② EKS가 만든 클러스터 보안그룹

ENI를 지워도 VPC가 안 지워졌다. 남아 있던 것은 이것이다.

```
sg-0fe1bb0cf21cac0af   eks-cluster-sg-flowticket-1337086394
"EKS created security group applied to ENI that is attached to
 EKS Control Plane master nodes, as well as any managed workloads."
```

**EKS가 직접 만든 것이라 terraform state에 없다.** destroy 대상이 아니니 그대로 두면
VPC는 영원히 지워지지 않는다. 지우자마자 VPC가 정리됐고, 이어서 돌린 destroy는
`No objects need to be destroyed`였다.

### 공통점

```
EBS PVC 볼륨   ← EBS CSI 드라이버가 만든다   (2026-08-16 사고, 4/7단계가 이미 처리)
고아 ENI       ← VPC CNI가 만든다            (이번)
클러스터 SG    ← EKS가 만든다                (이번)
```

**전부 terraform 밖에서 만들어져 terraform이 모르는 자원**이다. 스크립트는 이미 같은 이유로
EBS를 치우고 있었고, **그 목록에 둘이 빠져 있었을 뿐이다.**

## 3. 더 나빴던 것 — 실패가 성공으로 보였다

철거를 백그라운드로 돌렸는데 완료 알림이 이렇게 왔다.

```
Background command "Run full teardown" completed (exit code 0)
```

**terraform은 exit 1이었다.** 0은 내가 감싼 래퍼(`bash scripts/tear-down.sh > log 2>&1; echo "exit=$?"`)의
종료 코드였다 — 마지막 `echo`가 성공했으니 0이다.

이건 이 스크립트가 **이미 알고 있던 함정**이다. 5/7단계에 이런 주석이 있다.

> ⚠️ 파이프를 쓰지 않는다. `| tail` 을 붙이면 종료 코드가 tail의 것이 되어
> **실패한 destroy가 성공으로 보인다**(2026-08-16에 실제로 겪음).

스크립트 안에서는 막아 뒀는데, **그 스크립트를 부르는 쪽에서 같은 실수를 했다.**
알림만 믿고 "철거 완료"라고 보고할 뻔했다.

## 4. 고친 것

`tear-down.sh`에 6/7단계를 넣었다. **destroy가 실패한 뒤에만** 부른다.

```
5/7  terraform destroy
6/7  실패했다면 → 고아 ENI 삭제 → EKS 보안그룹 삭제 → destroy 재시도
7/7  잔여 점검
```

### 왜 destroy 앞이 아닌가

이것들은 **EKS 클러스터가 살아 있는 동안에는 지울 수 없다**(사용 중). destroy가 클러스터를
지우다 서브넷에서 막히는 그 순간에야 고아가 된다. 그래서 "미리 치우는 단계"가 아니라
"막혔을 때 푸는 단계"가 맞다.

### 소유 증명

4단계 EBS와 같은 원칙을 지켰다 — **`Project=flowticket` 태그가 붙은 VPC 안에 있는 것만**
지운다. 그 VPC를 못 찾으면 아무것도 지우지 않고 종료한다.

```
$ clean_untracked          # 소유 VPC가 없는 상태에서
Project=flowticket VPC를 찾지 못했다 — 아무것도 지우지 않는다
return=1
```

보안그룹은 **이름 접두사로 한 번 더 좁혔다.**

```
삭제  eks-cluster-sg-flowticket-*     ← EKS가 만든 것
보고  그 밖의 non-default SG          ← 지우지 않는다
제외  default                         ← VPC와 함께 사라진다
```

⚠️ 초판은 `GroupName != 'default'`로 잡았는데, 그러면 이 VPC의 non-default SG를 **전부**
지운다 — **terraform이 만든 것까지 포함해서**. 최종 목표가 VPC destroy라 결과적으로 다
사라질 자원이긴 하지만, 이 단계가 내세운 원칙은 *"terraform 밖에서 생긴 잔여만,
소유를 증명한 것만"*이다. **구현이 원칙보다 넓으면 안 된다** — 파괴 자동화에서는 더 그렇다.

접두사 판별을 쓰는 이유는 이 시점에 **클러스터가 이미 지워져** `describe-cluster`로 SG ID를
물을 수 없기 때문이다. 픽스처로 확인했다.

```
default                              → 제외
eks-cluster-sg-flowticket-133708...  → 삭제
flowticket-rds-sg                    → 보고만 (terraform 소유)
eks-cluster-sg-otherproject-99       → 보고만 (다른 클러스터)
```

## 5. 최종 확인 — 두 겹으로 봤다

스크립트 감사는 태그(`Project=flowticket`) 기준이라, **태그가 없는 자원은 0으로 보인다.**
실제로 철거 **전에도** `EC2(실행중) 0`이 나왔다 — 노드 3대가 떠 있는데도. 그래서 태그를
쓰지 않는 확인을 따로 했다.

| 항목 | 스크립트 감사 | 독립 확인(리전 기준) |
|---|---|---|
| EKS / EC2 | 0 / 0 | 0 / 0 |
| NAT / EIP | 0 / 0 | 0 / 0 |
| RDS(인스턴스·스냅샷·서브넷그룹) | 0 | 0 / 0 / 0 |
| ElastiCache(RG·스냅샷) | 0 | 0 / 0 |
| EBS(볼륨·스냅샷) | 0 | 0 / 0 |
| ALB / TG | 0 / 0 | 0 / 0 |
| 기본 외 VPC | 0 | 0 |
| 고아 ENI | — | 0 |

`terraform state list`도 비었다. 부트스트랩(ECR·ACM·Route53·tfstate·IAM)은 설계상 유지 대상이다.

## 6. 한계

- **실패 경로를 재현 검증하지 못했다.** 새 6/7단계는 destroy가 실패해야 돌아가는데,
  그 상황을 만들려면 클러스터를 다시 세워야 한다. 검증한 것은 둘뿐이다.
  - 소유 VPC가 없을 때 **아무것도 지우지 않고 멈춘다**(위 §4)
  - destroy가 성공했을 때 **건너뛴다**(state가 빈 상태로 전체 실행)
  실제 정리 로직은 **오늘 손으로 실행한 명령과 같은 것**이지만, 스크립트 안에서 그 경로가
  돈 적은 없다.
- **다음 철거가 이 두 가지에서만 막힌다는 보장은 없다.** 오늘 막은 것은 오늘 겪은 것이다.
- SG 삭제가 다른 SG의 참조 때문에 실패할 수 있다. 그 경우 경고만 남기고 destroy 재시도로
  넘어간다 — **재시도가 또 실패하면 사람이 본다.**

## 7. 남은 것

- **실패 경로 검증** — 다음에 클러스터를 세울 때 자연히 확인된다.
- **감사를 태그에 의존하지 않게** — §5의 `EC2 0` 오독은 태그 필터의 구조적 한계다.
  VPC 기준 확인을 `audit()`에 넣는 것을 검토한다.
- **호출하는 쪽의 종료 코드** — §3은 스크립트가 아니라 **부르는 쪽**의 문제였다.
  래퍼에서 `; echo "exit=$?"`로 감싸면 실패가 가려진다.
