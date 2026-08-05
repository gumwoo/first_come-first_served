output "state_bucket" {
  description = "bootstrap·platform의 backend.hcl에 넣을 버킷 이름."
  value       = aws_s3_bucket.state.id
}

output "lock_table" {
  value = try(aws_dynamodb_table.lock[0].name, null)
}

output "backend_hcl" {
  description = <<-EOT
    그대로 backend.hcl 로 저장해 `terraform init -backend-config=backend.hcl` 에 쓴다.
    버킷 이름에 계정 ID가 들어가므로 이 파일은 커밋하지 않는다(.gitignore 대상).
  EOT
  value       = <<-EOT
    bucket         = "${aws_s3_bucket.state.id}"
    region         = "${var.region}"
    encrypt        = true
    %{if var.create_lock_table~}
    dynamodb_table = "${try(aws_dynamodb_table.lock[0].name, "")}"
    %{endif~}
  EOT
}
