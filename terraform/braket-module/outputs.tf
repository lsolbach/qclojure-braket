output "bucket_name" {
  description = "Name of the S3 bucket for Braket results"
  value       = aws_s3_bucket.braket_bucket.id
}

output "bucket_arn" {
  description = "ARN of the S3 bucket for Braket results"
  value       = aws_s3_bucket.braket_bucket.arn
}

output "bucket_region" {
  description = "Region where the S3 bucket is located"
  value       = aws_s3_bucket.braket_bucket.region
}

output "bucket_domain_name" {
  description = "Domain name of the S3 bucket"
  value       = aws_s3_bucket.braket_bucket.bucket_domain_name
}

output "iam_role_name" {
  description = "Name of the IAM role for Braket execution"
  value       = aws_iam_role.braket_execution_role.name
}

output "iam_role_arn" {
  description = "ARN of the IAM role for Braket execution"
  value       = aws_iam_role.braket_execution_role.arn
}

output "iam_policy_arn" {
  description = "ARN of the IAM policy for Braket access"
  value       = aws_iam_policy.braket_policy.arn
}

output "cloudwatch_log_group_name" {
  description = "Name of the CloudWatch log group for Braket logs"
  value       = aws_cloudwatch_log_group.braket_logs.name
}

output "cloudwatch_log_group_arn" {
  description = "ARN of the CloudWatch log group for Braket logs"
  value       = aws_cloudwatch_log_group.braket_logs.arn
}
