output "braket_bucket_name" {
  description = "Name of the S3 bucket for Braket results"
  value       = module.braket_setup.bucket_name
}

output "braket_bucket_arn" {
  description = "ARN of the S3 bucket for Braket results"
  value       = module.braket_setup.bucket_arn
}

output "braket_bucket_region" {
  description = "Region where the S3 bucket is located"
  value       = module.braket_setup.bucket_region
}

output "iam_role_arn" {
  description = "ARN of the IAM role for Braket execution"
  value       = module.braket_setup.iam_role_arn
}

output "iam_role_name" {
  description = "Name of the IAM role for Braket execution"
  value       = module.braket_setup.iam_role_name
}

output "braket_config" {
  description = "Configuration map for QClojure Braket backend"
  value = {
    s3-bucket     = module.braket_setup.bucket_name
    s3-key-prefix = "braket-results/"
    region        = module.braket_setup.bucket_region
  }
}

output "setup_instructions" {
  description = "Instructions for using this infrastructure with QClojure Braket"
  value = <<-EOT
    Your Braket infrastructure is ready! Use this configuration in your QClojure code:
    
    (require '[org.soulspace.qclojure.adapter.backend.braket :as braket])
    
    (def backend 
      (braket/create-braket-simulator 
        {:s3-bucket "${module.braket_setup.bucket_name}"
         :s3-key-prefix "braket-results/"
         :region "${module.braket_setup.bucket_region}"}))
    
    S3 Bucket: ${module.braket_setup.bucket_name}
    Region: ${module.braket_setup.bucket_region}
    IAM Role: ${module.braket_setup.iam_role_arn}
  EOT
}
