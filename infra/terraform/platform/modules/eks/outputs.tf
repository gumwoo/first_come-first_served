output "cluster_name" {
  value = aws_eks_cluster.this.name
}

output "cluster_endpoint" {
  value = aws_eks_cluster.this.endpoint
}

output "cluster_security_group_id" {
  description = "EKS가 만든 클러스터 SG. RDS·ElastiCache의 ingress 출발지로 쓴다."
  value       = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}

output "oidc_provider_arn" {
  value = aws_iam_openid_connect_provider.this.arn
}

output "irsa_role_arns" {
  description = "Helm 설치 시 서비스 어카운트에 annotate할 역할 ARN."
  value = {
    ebs_csi                  = aws_iam_role.ebs_csi.arn
    cluster_autoscaler       = aws_iam_role.cluster_autoscaler.arn
    load_balancer_controller = aws_iam_role.load_balancer_controller.arn
  }
}

output "node_group_names" {
  value = [for ng in aws_eks_node_group.this : ng.node_group_name]
}
