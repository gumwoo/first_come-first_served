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
    # 3 AZ는 ADR-012의 전제다. 2 AZ면 Kafka 브로커 3개 중 2개가 한 장애 도메인에 몰려
    # "브로커 1대 장애에도 쓰기 지속"이라는 실증이 성립하지 않는다.
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
