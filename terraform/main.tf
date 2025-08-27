terraform {
  required_version = ">= 1.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

provider "aws" {
  region  = var.aws_region
  profile = var.aws_profile  # optional: uses credentials from your AWS profile
}

# Get current AWS account and region for resource naming and policies
data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

module "braket_setup" {
  source = "./braket-module"
  
  # Core configuration
  bucket_name = var.bucket_name
  environment = var.environment
  name_prefix = var.name_prefix
  
  # Security and lifecycle settings
  enable_encryption         = var.enable_encryption
  enable_versioning        = var.enable_versioning
  lifecycle_expiration_days = var.lifecycle_expiration_days
  
  # AWS context
  aws_account_id = data.aws_caller_identity.current.account_id
  aws_region     = data.aws_region.current.name
  
  # Tags
  tags = merge(var.tags, {
    Project     = "QClojure-Braket"
    ManagedBy   = "Terraform"
    Purpose     = "Quantum Computing Results Storage"
    Environment = var.environment
  })
}
