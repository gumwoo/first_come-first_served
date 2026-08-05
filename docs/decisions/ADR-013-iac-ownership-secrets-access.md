# ADR-013 · IaC 소유 경계와 비밀·접근 통제

- 상태: Proposed (`apply`로 실제 반영 시 Accepted로 승격)
- 날짜: 2026-08-05
- 슬라이스: S09(배포·운영 준비) Phase 3 — 인프라 트랙
- 관련: [[ADR-012]](3AZ 인프라·비용 통제), [[ADR-009]](GitOps CD),
  구현: `infra/terraform/`, [`terraform-design.md`](../deployment/terraform-design.md)

## 맥락

[ADR-012](ADR-012-3az-eks-infra-cost-control.md)는 **"무엇을 몇 개 둘 것인가"**(AZ·NAT·노드·Multi-AZ)를
정했다. 그걸 Terraform으로 옮기는 과정에서 **성격이 다른 결정들**이 따로 쌓였다.

- 어떤 자원은 이미 콘솔에 존재한다(Phase 2에서 손으로 만든 ECR·OIDC·IAM). **Terraform이 소유해야 하나?**
- state 파일에는 리소스 속성이 **평문**으로 들어간다. 비밀번호를 어디에 둘 것인가?
- "프라이빗 서브넷·최소 권한"을 설계 근거로 내세우는데, **실제로는 몇 군데를 열어 두었다.**
  EKS API 퍼블릭 엔드포인트, Redis 전송 평문, RDS 백업 없음.

이 결정들이 지금까지 **코드 주석과 README에만** 있었다. 특히 **열어 둔 곳의 근거**가 코드에만 있으면
"프라이빗을 강조하면서 왜 여기는 열었나"라는 질문에 답할 수 없다. 되돌리기 어렵거나(state·소유권)
보안 영향이 있는 것들이라 한 문서로 모은다.

## 결정

### 1. 소유 경계 — `data` / `import` / 신규 생성을 가르는 기준

| 자원 | 방식 | 근거 |
|---|---|---|
| Route53 Hosted Zone | **`data` 참조** | 재생성하면 **NS가 바뀌어** 레지스트라 설정을 다시 하고 DNS 전파를 기다려야 한다. 소유하지 않으면 실수로 destroy될 수도 없다 |
| ECR ×2, OIDC 공급자, IAM 롤 | **import** | **CI가 가진 권한이 git diff로 리뷰되게** 하려고 |
| ACM 인증서, VPC·EKS·RDS·ElastiCache | **신규 생성** | 콘솔에 존재하지 않던 것 |

**기준을 한 줄로**: 되돌리기 비용이 **압도적으로 큰 것만** Terraform 밖에 둔다. 그 외에는 코드로
가져와 변경이 리뷰되게 한다. "콘솔에서 만들었으니 그냥 둔다"는 이유가 되지 못한다.

IAM을 굳이 import하는 이유가 이 결정의 핵심이다 — **권한이 콘솔에만 있으면 언제 누가 넓혔는지
알 수 없다.** 장기 액세스 키 대신 OIDC를 쓴 것과 같은 논리를 관리 방식에까지 적용한다.

### 2. 비밀은 Terraform state에 남기지 않는다

state 파일은 **평문**이다. `output`으로 내보내지 않아도 리소스 속성은 그대로 저장된다.

- **DB 비밀번호**: `manage_master_user_password = true` — **RDS가 생성·저장·교체**하고 Terraform은
  값을 보지도 저장하지도 않는다. 출력은 `master_user_secret[0].secret_arn`(ARN)뿐이다.
- **원래는 `random_password`로 만들어 Secrets Manager에 넣었다.** 그 방식은 생성한 값이
  **state에 평문으로 남는다** — output을 막아도 소용없다. 리뷰에서 지적받아 구현을 바꿨다.
- **계정 ID도 커밋하지 않는다**: state 버킷 이름에 계정 ID가 들어가므로 backend는 `key`만 코드에
  두고 나머지는 `-backend-config=backend.hcl`로 넘긴다(`.gitignore` 대상, `.example`만 커밋).
- 앱에는 **External Secrets가 ARN을 읽어** K8s Secret으로 넣는다(ADR-009). ArgoCD는 값을 모른다.

### 3. state 저장소 자체를 보호한다

state가 깨지거나 유출되면 인프라 전체가 흔들린다.

| 설정 | 이유 |
|---|---|
| 버저닝 | state가 깨졌을 때 **되돌릴 유일한 수단** |
| AES256 암호화 | 리소스 속성이 평문으로 들어간다 |
| 퍼블릭 접근 차단 4종 | 내부 구조가 그대로 노출될 자리 |
| 비현행 버전 90일 만료 | 버저닝을 켜면 무한히 쌓인다 |
| `prevent_destroy` | 지우면 **모든 스택의 state**가 사라진다 |

**`prevent_destroy`를 어디에 걸었는지도 결정이다.**

```
state 버킷 · 잠금 테이블   → 지우면 모든 state 상실
ECR ×2                    → 지우면 과거 SHA 롤백 경로 상실
OIDC 공급자 · IAM 롤       → 지우면 CI 인증 즉시 중단
ACM 인증서                → 걸지 않음. 갱신·교체 대상이라 오히려 운영을 막는다
                            대신 create_before_destroy로 교체 중 공백을 없앤다
```

`state-bootstrap`만 **local state**를 쓴다(자기가 만드는 버킷에 자기 state를 둘 수 없다).
이 파일은 **백업 대상**이다 — 유실 시 6개 리소스를 각각 정확한 주소로 import해야 하고,
`prevent_destroy` 때문에 "새로 만들기"도 막힌다.

### 4. 열어 둔 곳 3개와 그 근거 (가장 중요한 절)

"프라이빗 서브넷·최소 권한"이 이 프로젝트의 설계 근거인데, **실제로는 세 군데를 열었다.**
숨기지 않고 근거와 대안을 적는다.

**① EKS API 퍼블릭 엔드포인트 — 켜되 출발지를 제한한다**
```
endpoint_private_access = true
endpoint_public_access  = true
public_access_cidrs     = <관리자 공인 IP/32>   ← 기본값 없음, 0.0.0.0/0은 validation으로 거부
```
데모라 **로컬에서 `kubectl`·ArgoCD 부트스트랩**을 해야 한다. 완전 프라이빗으로 두면 배스천이나
VPN이 필요한데, 그건 이 트랙의 실증 대상이 아니면서 컴포넌트만 늘린다.
**기본값을 두지 않은 것**이 이 결정의 핵심이다 — 문서에 "좁힌다"고 쓰고 기본값을 전 세계로
열어 두면 문서와 코드가 충돌한다(초안이 그랬고 리뷰에서 교정됐다).

**② ElastiCache 전송 암호화 — 끈다**
```
transit_encryption_enabled = false   # 저장 시 암호화(at_rest)는 켬
```
켜면 클라이언트가 TLS로 접속해야 해 **앱 설정을 함께 바꿔야 한다**(`spring.data.redis.ssl`).
Redis는 **프라이빗 데이터 서브넷**에 있고 기본 라우트가 없으며, ingress는 **CIDR이 아니라 EKS
클러스터 SG**로 제한된다. 즉 VPC 밖에서 도달할 경로가 없다.
**한계를 분명히 한다**: VPC 내부 트래픽은 평문이다. 같은 VPC를 침해당한 상황은 막지 못한다.

**③ RDS 백업 — 하지 않는다**
```
backup_retention_period = 0
skip_final_snapshot     = true
deletion_protection     = false
```
`platform`은 **apply/destroy를 반복하는 계층**이다(ADR-012 §8). 데이터는 Flyway 마이그레이션,
KOPIS 동기화, 좌석 자동 시딩, Kafka 토픽 자동 생성으로 **기동 시 스스로 채워진다.**
백업을 남기면 저장 비용이 붙고 destroy도 느려진다.
**한계**: 데모 중 만든 주문·결제 데이터는 destroy와 함께 사라진다. 실증 증거는 **영상·캡처로**
남기지 DB에 남기지 않는다.

## 고려한 대안

| 대안 | 기각 이유 |
|---|---|
| **전부 `data` 참조**(콘솔 자원을 Terraform 밖에 둠) | 가장 안전하지만 **CI 권한·ECR 설정이 코드에 없다.** IaC라고 말하기 어렵고, 변경 이력이 남지 않는다 |
| **전부 신규 생성**(콘솔 자원 무시) | 이미 존재해 `apply`가 "이미 존재함"으로 실패한다. 지우고 다시 만들면 **Image 파이프라인과 이미지가 날아간다** |
| **Hosted Zone도 import** | destroy 시 Zone이 삭제되고 재생성하면 **NS가 바뀐다**. 이 스택에서 되돌리기 비용이 가장 큰 자원이라 예외로 둔다 |
| **`random_password` + Secrets Manager 직접 관리** | 생성한 값이 **state에 평문으로 남는다.** output을 막아도 소용없다 → RDS 관리형으로 교체 |
| **EKS API 완전 프라이빗** | 배스천/VPN이 필요하다. 실증 대상이 아닌 컴포넌트가 늘고, 데모 기동 절차가 길어진다 |
| **Redis TLS 활성화** | 앱 설정 변경이 따라와야 하고, 프라이빗 서브넷 + SG 제한으로 도달 경로가 이미 없다. 필요해지면 앱과 함께 켠다 |
| **RDS 백업 유지** | 매번 지웠다 만드는 계층이라 백업의 수명이 스택보다 짧다. 저장 비용과 destroy 지연만 남는다 |
| **모든 자원에 `prevent_destroy`** | ACM처럼 **교체가 정상 운영인 자원**까지 막으면 갱신이 불가능해진다 |

## 결과 / 한계 (정직)

- **아직 Proposed다.** import·`plan`·`apply` 미실행. 실제 반영 후 Accepted로 승격한다.
- **import 후 첫 `plan`이 진짜 검증이다.** 콘솔에 실제로 무엇이 설정돼 있었는지는 그때 처음 보인다.
  특히 IAM 신뢰 정책의 `sub` 조건은 **좁히면 Image 파이프라인이 조용히 인증 실패**하므로,
  `apply` 직후 `gh workflow run image.yml`로 확인하는 절차를 README에 넣었다.
- **`terraform validate`는 이 결정들을 검증하지 못한다.** 실제로 이번 구현에서 `validate`를
  통과하고도 apply 시점 결함이 둘 나왔다(ACM 검증 레코드 중복 키, S3 라이프사이클 순서).
- **VPC 내부는 평문이다**(§4-②). 같은 VPC를 침해당한 시나리오는 범위 밖이다.
- **데모 데이터는 보존되지 않는다**(§4-③).
- **`prevent_destroy`는 Terraform 안에서만 유효하다.** 콘솔에서 지우는 것은 막지 못한다.
- **되돌리기**: 열어 둔 세 곳은 전부 변수·설정 수준의 변경이다. 운영으로 전환한다면
  EKS API를 프라이빗으로 좁히고(배스천/VPN 도입), Redis TLS를 앱과 함께 켜고,
  RDS 백업·`deletion_protection`을 활성화한다. 그때는 이 ADR을 대체하는 새 문서를 남긴다.
