variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "state_bucket" {
  description = <<-EOT
    bootstrap의 state가 있는 S3 버킷. 계정 ID가 들어가므로 기본값을 두지 않고
    terraform.tfvars 로 넘긴다(backend.hcl과 같은 값).
  EOT
  type        = string
}

variable "azs" {
  description = "3 AZ. Kafka 브로커를 서로 다른 장애 도메인에 하나씩 두기 위한 최소 구성이다."
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
}

# ---------------------------------------------------------------------------
# 네트워크 (terraform-design §2)
# ---------------------------------------------------------------------------
variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "ALB·NAT만 들어가므로 /24로 충분하다."
  type        = list(string)
  default     = ["10.0.0.0/24", "10.0.1.0/24", "10.0.2.0/24"]
}

variable "private_app_subnet_cidrs" {
  description = "VPC CNI가 Pod에 이 서브넷 IP를 직접 할당하므로 /19로 크게 잡는다."
  type        = list(string)
  default     = ["10.0.32.0/19", "10.0.64.0/19", "10.0.96.0/19"]
}

variable "private_data_subnet_cidrs" {
  type    = list(string)
  default = ["10.0.16.0/24", "10.0.17.0/24", "10.0.18.0/24"]
}

variable "vpc_endpoints_enabled" {
  description = "NAT가 있으므로 꺼도 동작한다. AWS 트래픽을 NAT에서 분리하는 개선 항목."
  type        = bool
  default     = true
}

# ---------------------------------------------------------------------------
# EKS
# ---------------------------------------------------------------------------
variable "cluster_name" {
  type    = string
  default = "flowticket"
}

variable "kubernetes_version" {
  description = <<-EOT
    ⚠️ apply 전에 반드시 실측 확인한다 — 이 값은 시간이 지나면 낡는다.

        aws eks describe-cluster-versions --region ap-northeast-2
        aws eks describe-addon-versions --addon-name vpc-cni --kubernetes-version <버전>

    표준 지원이 끝난 버전을 쓰면 클러스터 생성이 실패하거나, 연장 지원 대상이 되어
    컨트롤플레인 요금이 크게 오른다(비용 안전망을 세워 둔 이 프로젝트에서는 조용한 청구 증가로
    나타난다).

    2026-08-06 실측 (표준 지원 종료일):
        1.36 → 2027-08-02
        1.35 → 2027-03-27   ← 현재 값
        1.34 → 2026-12-02   (약 4개월 남아 가장 임박)
        1.33 → 2026-07-29   (이미 만료, 연장 지원 구간)
    같은 날 애드온 4종(vpc-cni·coredns·kube-proxy·aws-ebs-csi-driver) 모두 1.36 지원 확인.

    ⚠️ 2026-08-25: 1.36 → 1.35로 **내렸다.** 지원 창이 더 긴 쪽을 고르던 원칙을 뒤집은
    것이므로 이유를 남긴다.

    Cluster Autoscaler를 기본 구성으로 도입했는데(ADR-012 §4 재도입), CA는 Kubernetes
    마이너와 버전을 맞춰야 한다(CA v1.35 → k8s 1.35). 그런데 **CA는 아직 1.36용이 없다** —
    helm 저장소 최신이 appVersion 1.35.0이다(2026-08-25 확인).

        helm search repo autoscaler/cluster-autoscaler --versions | awk '{print $3}' | sort -uV | tail -1
        → 1.35.0

    즉 1.36을 유지하면 bring-up.sh의 CA 호환성 검사에 걸려 기동 자체가 멈춘다.
    검사를 완화해 CA 1.35를 1.36에 얹는 선택지도 있었으나, 그건 CA 공식 정책 밖이라
    **"권장 조합이 아닌 상태에서 잰 값"**이 된다. 측정의 신뢰도가 목적이므로 클러스터를
    맞췄다. 1.35의 표준 지원은 2027-03-27까지로 아직 1년 이상 남았다.

    **CA 1.36이 나오면 되돌린다** — 위 명령으로 확인하고, 이 값과
    bring-up.sh의 CA_CHART_VERSION을 함께 올린다.
  EOT
  type        = string
  default     = "1.35"
}

variable "cluster_public_access_cidrs" {
  description = <<-EOT
    EKS API 접근을 허용할 관리자 공인 IP CIDR. **기본값을 두지 않는다.**

    데모라 로컬 kubectl·ArgoCD 부트스트랩 때문에 퍼블릭 엔드포인트를 켜지만,
    "프라이빗 서브넷·최소 권한"을 설계 근거로 내세우면서 API를 전 세계에 여는 것은
    문서와 코드가 충돌하는 상태다. 기본값이 없으므로 apply 때 반드시 의식적으로 지정하게 된다.

    terraform.tfvars.example 참고.
  EOT
  type        = list(string)

  validation {
    condition = alltrue([
      for cidr in var.cluster_public_access_cidrs : cidr != "0.0.0.0/0"
    ])
    error_message = "EKS API에 0.0.0.0/0을 허용할 수 없다. 관리자 공인 IP/32를 지정하라."
  }
}

variable "node_instance_type" {
  description = "부하테스트 때는 m6i.large 등 비버스터블로 바꾼다(ADR-012 §5)."
  type        = string
  default     = "t3.large"
}

variable "node_min_size" {
  type    = number
  default = 1
}

variable "node_desired_size" {
  type    = number
  default = 1
}

variable "node_max_size" {
  description = "AZ당 최대. 3 AZ × 2 = 6노드 = 12 vCPU(쿼터 32 이내)."
  type        = number
  default     = 2
}

# ---------------------------------------------------------------------------
# 데이터 계층
# ---------------------------------------------------------------------------
variable "db_multi_az" {
  description = <<-EOT
    RDS는 진실원이라 단일 AZ면 그 AZ 장애가 곧 전체 중단이다 — NAT를 AZ별로 둔 것과
    같은 논리의 귀결이다(ADR-012 §10). 페일오버는 강제 실행이 가능해 실증 대상이기도 하다.
  EOT
  type        = bool
  default     = true
}

variable "redis_multi_az" {
  description = "대기열·SSE 팬아웃의 가용성. 정합성은 DB가 지키지만 간판 기능이 멈춘다(ADR-012 §10)."
  type        = bool
  default     = true
}

variable "cluster_log_types" {
  description = "EKS 컨트롤플레인 로그 종류. 빈 배열이면 로그 없음(요금 절약, 증거 없음)."
  type        = list(string)
  default     = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
}

variable "cluster_log_retention_days" {
  description = "컨트롤플레인 로그 보존 일수."
  type        = number
  default     = 7
}

variable "redis_transit_encryption" {
  description = <<-EOT
    Redis 전송 중 암호화(TLS). 켜면 앱이 반드시 TLS로 접속해야 한다
    (spring.data.redis.ssl.enabled=true). k8s 매니페스트 작업의 전제 조건이다.
  EOT
  type        = bool
  default     = true
}
