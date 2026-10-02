output "instance_ids" {
  value = aws_instance.loadgen[*].id
}

output "results_bucket" {
  value = local.enabled ? aws_s3_bucket.results[0].bucket : null
}
