# state-bootstrap — Terraform state 저장소

**가장 먼저 한 번 실행하는 스택**이다. `bootstrap`·`platform`이 state를 둘 S3 버킷과
DynamoDB 잠금 테이블을 만든다.

## ⚠️ 이 스택만 local state를 쓴다 — 반드시 백업한다

자기가 만드는 버킷에 자기 state를 둘 수는 없다(닭과 달걀). 그래서 여기만
`terraform.tfstate`가 **로컬 파일로 남고**, `.gitignore` 대상이다.

**`terraform.tfstate`를 암호화된 개인 저장소에 백업한다.**

유실돼도 AWS 자원이 지워지지는 않는다. 다만 복구하려면 아래를 **각각 정확한 주소로
import**해야 한다 — "다시 만들면 된다"가 아니라 복구 비용이 큰 쪽이다.

```
aws_s3_bucket.state
aws_s3_bucket_versioning.state
aws_s3_bucket_server_side_encryption_configuration.state
aws_s3_bucket_public_access_block.state
aws_s3_bucket_lifecycle_configuration.state
aws_dynamodb_table.lock[0]
```

게다가 버킷과 잠금 테이블에는 `prevent_destroy`가 걸려 있어, state가 없는 상태에서
Terraform이 "새로 만들려" 하면 이름 충돌로 막힌다.

## 실행

```bash
cd infra/terraform/state-bootstrap
terraform init
terraform plan          # 눈으로 확인
terraform apply         # ← 첫 과금(S3 저장 + DynamoDB 요청, 월 몇 센트 수준)
```

다음 스택이 쓸 backend 설정을 출력한다:

```bash
terraform output -raw backend_hcl > ../bootstrap/backend.hcl
terraform output -raw backend_hcl > ../platform/environments/demo/backend.hcl
```

버킷 이름에 **계정 ID가 들어가므로** `backend.hcl`은 커밋하지 않는다(`.example`만 커밋).

## 무엇을 왜 켰나

| 설정 | 이유 |
|---|---|
| **버저닝** | state가 깨졌을 때 **되돌릴 유일한 수단** |
| **암호화(AES256)** | state에는 리소스 속성이 평문으로 들어간다 |
| **퍼블릭 접근 차단 4종** | 내부 구조가 그대로 노출될 자리 |
| **비현행 버전 90일 만료** | 버저닝을 켜면 과거 버전이 무한히 쌓인다 |
| **미완료 멀티파트 7일 정리** | 중단된 업로드가 조용히 과금된다 |
| **`prevent_destroy`** | 지우면 모든 스택의 state가 사라진다 |

## 잠금 — DynamoDB vs S3 네이티브

Terraform 1.10+ 는 S3 네이티브 잠금(`use_lockfile`)을 지원해 DynamoDB가 필요 없다.
**현재 CI가 1.9.8을 쓰므로** DynamoDB로 둔다. 버전을 올리면
`create_lock_table = false` 로 끄고 backend에서 `use_lockfile`을 쓰면 된다.

## 이 스택은 destroy하지 않는다

`prevent_destroy`가 걸려 있어 `terraform destroy`가 실패한다(의도).
정말 정리해야 한다면 `platform`·`bootstrap`을 먼저 정리하고, `prevent_destroy`를 뗀 뒤
버킷 안의 **모든 버전**을 비워야 삭제된다.
