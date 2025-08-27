variable "bucket_name" {
  description = "S3 bucket for Braket output"
  type        = string
}

variable "environment" {
  description = "Environment name (e.g., dev, staging, prod)"
  type        = string
}

variable "name_prefix" {
  description = "Prefix for IAM role and policy names"
  type        = string
}

variable "enable_encryption" {
  description = "Enable S3 bucket encryption"
  type        = bool
  default     = true
}

variable "enable_versioning" {
  description = "Enable S3 bucket versioning"
  type        = bool
  default     = true
}

variable "lifecycle_expiration_days" {
  description = "Number of days after which objects are automatically deleted (0 = disabled)"
  type        = number
  default     = 90
}

variable "aws_account_id" {
  description = "AWS Account ID for policies"
  type        = string
}

variable "aws_region" {
  description = "AWS Region for the bucket"
  type        = string
}

variable "enable_cloudwatch" {
  description = "Enable CloudWatch logging for Braket tasks (may incur additional costs)"
  type        = bool
  default     = false
}

variable "cloudwatch_retention_days" {
  description = "CloudWatch log retention in days (only used if enable_cloudwatch is true)"
  type        = number
  default     = 30
  validation {
    condition = contains([
      1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1827, 3653
    ], var.cloudwatch_retention_days)
    error_message = "CloudWatch log retention must be one of the valid AWS values: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1827, or 3653 days."
  }
}

variable "tags" {
  description = "Tags to apply to all resources"
  type        = map(string)
  default     = {}
}
