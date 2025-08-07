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

module "braket_setup" {
  source      = "./braket-module"
  bucket_name = var.bucket_name
  environment = var.environment
  name_prefix = var.name_prefix
}
