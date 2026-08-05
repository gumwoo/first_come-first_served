variable "region" {
  type    = string
  default = "ap-northeast-2"
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
  description = "EKS 지원 버전 중에서 고른다. 지원 종료가 가까운 버전은 피한다."
  type        = string
  default     = "1.30"
}

variable "cluster_public_access_cidrs" {
  description = "가능하면 본인 공인 IP/32로 좁힌다."
  type        = list(string)
  default     = ["0.0.0.0/0"]
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
  description = "ADR-012의 일관성 논점이 걸린 값 — modules/rds/variables.tf 주석 참고."
  type        = bool
  default     = false
}

variable "redis_multi_az" {
  type    = bool
  default = false
}
