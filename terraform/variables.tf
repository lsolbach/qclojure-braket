variable "aws_region" {
  description = "AWS region to deploy resources in"
  type        = string
  default     = "us-west-1"
}

variable "aws_profile" {
  description = "AWS CLI profile to use for authentication"
  type        = string
  default     = "default"
}

variable "bucket_name" {
  description = "Name of the S3 bucket used for Braket output"
  type        = string
  default     = "my-braket-output-12345"
}

variable "environment" {
  description = "Deployment environment (e.g., dev, prod)"
  type        = string
  default     = "dev"
}

variable "name_prefix" {
  description = "Prefix for resource names"
  type        = string
  default     = "qclojure"
}
