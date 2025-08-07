output "bucket_name" {
  value = aws_s3_bucket.braket_bucket.id
}

output "bucket_arn" {
  value = aws_s3_bucket.braket_bucket.arn
}

output "iam_role_name" {
  value = aws_iam_role.braket_execution_role.name
}

output "iam_role_arn" {
  value = aws_iam_role.braket_execution_role.arn
}
