# bootstrap — 영속 계층 (ACM · ECR · IAM/OIDC)

`platform`과 달리 **destroy하지 않는 계층**이다. 도메인·인증서·이미지·CI 권한이 여기 있고,
연간 비용은 도메인($15 수준) + Hosted Zone($0.50/월) + ECR 저장 비용 정도다.

설계 근거: [terraform-design.md](../../../docs/deployment/terraform-design.md) §1,
[ADR-012](../../../docs/decisions/ADR-012-3az-eks-infra-cost-control.md) §9.

## 자원별 소유 방식이 다르다

| 자원 | 방식 | 이유 |
|---|---|---|
| **Route53 Hosted Zone** | `data` 참조 | 재생성하면 **NS가 바뀌어** 레지스트라 설정을 다시 해야 한다. 소유하지 않으면 실수로 destroy될 수도 없다 |
| **ACM 인증서** | **신규 생성** | 이 스택에서 유일하게 새로 만드는 자원 |
| **ECR ×2** | **import** | Phase 2에서 콘솔로 생성됨 |
| **OIDC 공급자 · IAM 롤** | **import** | Phase 2에서 콘솔로 생성됨 |

**왜 import까지 하나**: CI가 가진 권한이 콘솔에만 있으면 **언제 누가 넓혔는지 알 수 없다.**
코드로 옮기면 신뢰 정책과 권한이 git diff로 리뷰된다 — 장기 액세스 키를 안 쓰는 것과 같은 이유다.

## 절차

### 0. backend 설정

```bash
cd infra/terraform/state-bootstrap
terraform init && terraform apply          # state 버킷 + 잠금 테이블
terraform output -raw backend_hcl > ../bootstrap/backend.hcl
```

`backend.hcl`은 버킷 이름에 계정 ID가 들어가므로 **커밋하지 않는다**(`.gitignore` 대상).

```bash
cd ../bootstrap
terraform init -backend-config=backend.hcl
```

### 1. 기존 자원 import

**apply보다 먼저 해야 한다.** 하지 않으면 "이미 존재함" 오류로 실패한다.

```bash
# ECR (for_each를 쓰므로 주소에 키가 들어간다 — 셸 확장을 막으려 작은따옴표)
terraform import 'aws_ecr_repository.this["api"]' flowticket-api
terraform import 'aws_ecr_repository.this["web"]' flowticket-web

# OIDC 공급자 — ARN이 필요하다
aws iam list-open-id-connect-providers
terraform import aws_iam_openid_connect_provider.github \
  arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com

# IAM 롤 — 콘솔에서 만든 이름을 그대로 쓴다. 다르면 var.github_actions_role_name 을 맞춘다
terraform import aws_iam_role.github_actions <역할이름>
terraform import aws_iam_role_policy.ecr_push <역할이름>:<인라인정책이름>
```

> 인라인 정책 이름이 `ecr-push`가 아니면 import가 실패한다. 그 경우 **정책만 import하지 말고**
> 그대로 `apply`해 새로 만들게 두고, 콘솔의 옛 정책은 plan 결과를 확인한 뒤 지운다.

### 2. plan을 반드시 눈으로 본다 ⚠️

import 직후 첫 plan은 **콘솔 기본값과 코드 선언의 차이**를 보여준다. **이 diff를 읽는 것이
이 단계의 핵심이다** — 지금까지 콘솔 설정이 코드에 없었기 때문이다.

특히 확인할 것:

```
aws_iam_role.github_actions
  assume_role_policy 의 sub 조건       ← 좁히면 Image 파이프라인이 인증 실패할 수 있다
aws_iam_openid_connect_provider.github
  thumbprint_list                     ← 콘솔 생성값과 다르면 실제 값으로 맞춘다
aws_ecr_repository.this[*]
  image_tag_mutability / scan_on_push  ← 의도한 변경인지 확인
```

**sub 조건이 실제 워크플로의 ref와 어긋나면 인증이 조용히 실패한다.** `image.yml`은
`workflow_run`(main)과 `workflow_dispatch`로 도는데 둘 다 ref가 main이다. 확신이 서지 않으면
콘솔의 현재 값을 그대로 `var.github_oidc_subjects`에 넣고 시작한 뒤 나중에 좁힌다.

```bash
terraform plan          # 읽는다
terraform apply
```

### 3. 검증

```bash
terraform output certificate_arn         # 검증까지 끝난 인증서
terraform output github_actions_role_arn # GitHub 시크릿 AWS_ROLE_ARN 과 같아야 한다
```

**`apply` 후 Image 워크플로를 수동 실행해 파이프라인이 여전히 도는지 확인한다.** IAM을 손댔으니
이 확인 없이는 다음 push 때 깨진 걸 알게 된다.

```bash
gh workflow run image.yml --ref main
```

## ACM 검증은 시간이 걸린다

`aws_acm_certificate_validation`이 검증 완료까지 대기한다. 이 리소스가 없으면 아직 PENDING인
ARN이 platform으로 넘어가 **ALB 생성이 실패한다.** 대기가 길어지면 Route53에 검증 레코드가
실제로 생성됐는지 먼저 본다.

## 이 스택을 destroy하지 않는다

`prevent_destroy`를 ECR·IAM 롤·OIDC 공급자에 걸어 뒀다. 지우면 Image 파이프라인이 즉시 멈추고
과거 SHA로의 롤백 경로가 끊긴다. Hosted Zone은 애초에 Terraform이 소유하지 않는다.
