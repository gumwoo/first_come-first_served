variable "name" {
  description = "리소스 이름 접두어"
  type        = string
}

variable "cidr" {
  description = "VPC CIDR"
  type        = string
}

variable "azs" {
  description = "사용할 가용 영역 3개. 순서가 서브넷 CIDR 리스트와 대응한다."
  type        = list(string)

  validation {
    # 3 AZ는 ADR-012의 전제다. 처음 근거(Kafka 브로커 3개를 장애 도메인마다 하나씩)는 Kafka 제거(ADR-028)로
    # 사라졌다. 가용성 G2(AZ 장애) 재평가(kafka-removal-design.md §7.4) 전까지 변경 범위를 줄이려고 유지한다.
    condition     = length(var.azs) == 3
    error_message = "AZ는 정확히 3개여야 한다(ADR-012)."
  }
}

variable "public_subnet_cidrs" {
  description = "퍼블릭 서브넷 CIDR(AZ 순서와 대응). ALB·NAT용이라 작아도 된다."
  type        = list(string)
}

variable "private_app_subnet_cidrs" {
  description = <<-EOT
    EKS 노드·Pod용 서브넷 CIDR. VPC CNI가 Pod에 이 서브넷 IP를 직접 할당하므로
    넉넉해야 한다(/19 권장). /24면 노드 몇 대에 Pod 수십 개만 떠도 IP가 고갈된다.
  EOT
  type        = list(string)
}

variable "private_data_subnet_cidrs" {
  description = "RDS·ElastiCache용 서브넷 CIDR. 기본 라우트가 없다."
  type        = list(string)
}

variable "cluster_name" {
  description = "EKS 클러스터 이름. 서브넷 검색 태그에 쓰인다."
  type        = string
}

variable "tags" {
  description = "공통 태그"
  type        = map(string)
  default     = {}
}
