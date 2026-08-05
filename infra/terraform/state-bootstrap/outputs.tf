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
  # heredoc + %{if} 조합은 지시자 주변 들여쓰기가 남아 출력이 지저분해진다
  # (README가 이 출력을 backend.hcl로 바로 파이프하라고 안내하므로 그대로 쓸 수 있어야 한다).
  # join/compact로 줄을 조립하면 결과가 결정적이다.
  value = join("\n", compact([
    "bucket         = \"${aws_s3_bucket.state.id}\"",
    "region         = \"${var.region}\"",
    "encrypt        = true",
    var.create_lock_table ? "dynamodb_table = \"${try(aws_dynamodb_table.lock[0].name, "")}\"" : "",
  ]))
}
