variable "bucket_name" {
  description = "S3 bucket for Braket output"
  type        = string
}

variable "environment" {
  description = "Environment name (e.g., dev)"
  type        = string
}

variable "name_prefix" {
  description = "Prefix for IAM role and policy names"
  type        = string
}
