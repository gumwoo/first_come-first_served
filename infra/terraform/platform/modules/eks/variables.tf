variable "cluster_name" {
  type = string
}

variable "kubernetes_version" {
  description = "EKS 지원 버전. 낡으면 생성 실패 또는 연장 지원 요금이 붙는다 — 환경 변수 설명 참고."
  type        = string
}

variable "private_app_subnet_ids" {
  description = "컨트롤플레인 ENI가 들어갈 서브넷"
  type        = list(string)
}

variable "private_app_subnet_id_by_az" {
  description = "AZ => 서브넷 ID. 노드그룹을 AZ별로 만들기 위한 입력(ADR-012 §3)."
  type        = map(string)
}

variable "public_access_cidrs" {
  description = <<-EOT
    K8s API 퍼블릭 엔드포인트 접근을 허용할 출발지. **기본값을 두지 않는다** —
    호출자가 의식적으로 지정하게 한다(전 세계 공개는 환경 계층에서 validation으로 막는다).
  EOT
  type        = list(string)
}

variable "node_instance_type" {
  description = <<-EOT
    기본은 t3.large(기능·배포·페일오버 검증).
    부하테스트(S10)에서는 반드시 비버스터블(m6i.large 등)로 바꾼다 —
    T 계열은 CPU 크레딧이 결과에 개입해 측정의 인과를 주장할 수 없다(ADR-012 §5).
  EOT
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
  description = "AZ당 최대 노드. 3 AZ × 2 = 최대 6노드 = 12 vCPU(쿼터 32 이내)."
  type        = number
  default     = 2
}

variable "tags" {
  type    = map(string)
  default = {}
}

variable "cluster_log_types" {
  description = <<-EOT
    CloudWatch로 내보낼 컨트롤플레인 로그 종류.

    빈 배열이면 로그가 남지 않는다 — 롤링 배포·스케줄링이 왜 그렇게 동작했는지 사후에
    증명할 수 없다. 요금이 부담되면 ["api", "audit"]으로 줄인다.
  EOT
  type        = list(string)
  default     = ["api", "audit", "authenticator", "controllerManager", "scheduler"]
}

variable "cluster_log_retention_days" {
  description = "컨트롤플레인 로그 보존 일수. 데모는 짧게 — 클러스터를 지워도 로그 요금은 남는다."
  type        = number
  default     = 7
}
