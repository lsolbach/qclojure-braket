# S3 Bucket Setup for QClojure Braket Backend

## Overview

AWS Braket requires an S3 bucket to store quantum task results. This is a **mandatory requirement** - all quantum tasks executed on Braket devices (simulators and QPUs) store their output in S3.

## Quick Setup

### Option 1: Using Terraform (Recommended)

The easiest way to set up your S3 infrastructure is using our provided Terraform configuration:

```bash
cd terraform

# Copy example configuration
cp terraform.tfvars.example terraform.tfvars

# Edit terraform.tfvars with your bucket name
nano terraform.tfvars

# Deploy infrastructure
terraform init
terraform plan
terraform apply
```

After deployment, Terraform will output the exact configuration for your QClojure backend:

```clojure
;; Use the values from terraform output
(def backend 
  (braket/create-braket-simulator 
    {:s3-bucket "your-terraform-bucket-name"
     :s3-key-prefix "braket-results/"
     :region "us-east-1"}))
```

See [terraform/README.md](terraform/README.md) for detailed Terraform documentation.

#### CloudWatch Configuration

The Terraform setup includes optional CloudWatch logging for monitoring Braket tasks:

```bash
# For development/experiments - disable CloudWatch to save costs
enable_cloudwatch = false

# For production - enable CloudWatch with log retention
enable_cloudwatch         = true
cloudwatch_retention_days  = 90  # Valid values: 1,3,5,7,14,30,60,90,120,150,180,365,400,545,731,1827,3653
```

**Cost Considerations:**
- CloudWatch logging incurs additional charges
- Recommended for production environments only
- Can be disabled for small experiments and testing

### Option 2: Manual AWS CLI Setup

### 1. Create an S3 Bucket

```bash
aws s3 mb s3://my-braket-results-bucket --region us-east-1
```

### 2. Set Bucket Policy (Optional but Recommended)

Create a bucket policy to restrict access to your AWS account:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "BraketTaskResults",
            "Effect": "Allow",
            "Principal": {
                "Service": "braket.amazonaws.com"
            },
            "Action": [
                "s3:PutObject",
                "s3:GetObject",
                "s3:ListBucket"
            ],
            "Resource": [
                "arn:aws:s3:::my-braket-results-bucket",
                "arn:aws:s3:::my-braket-results-bucket/*"
            ]
        }
    ]
}
```

### 3. Configure Your Backend

```clojure
(require '[org.soulspace.qclojure.adapter.backend.braket :as braket])

;; For simulators
(def simulator-backend
  (braket/create-braket-simulator {:s3-bucket "my-braket-results-bucket"
                                   :s3-key-prefix "simulations/"}))

;; For QPUs  
(def qpu-backend
  (braket/create-braket-qpu "arn:aws:braket:us-east-1::device/qpu/rigetti/aspen-m-3"
                            {:s3-bucket "my-braket-results-bucket"
                             :s3-key-prefix "quantum-hardware/"
                             :region "us-east-1"}))
```

## Required AWS Permissions

Your AWS credentials need the following permissions:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "braket:*",
                "s3:GetObject",
                "s3:PutObject",
                "s3:ListBucket"
            ],
            "Resource": [
                "*",
                "arn:aws:s3:::my-braket-results-bucket",
                "arn:aws:s3:::my-braket-results-bucket/*"
            ]
        }
    ]
}
```

## S3 Result Structure

Braket stores results in your S3 bucket with the following structure:

```
my-braket-results-bucket/
├── braket-results/                     # Default key prefix
│   ├── task-1234567890-uuid/
│   │   ├── results.json               # Measurement results
│   │   └── task-metadata.json         # Task execution metadata
│   └── task-1234567891-uuid/
│       ├── results.json
│       └── task-metadata.json
└── experiments/                        # Custom key prefix
    └── bell-states/
        └── task-1234567892-uuid/
            ├── results.json
            └── task-metadata.json
```

## Error Messages

If you forget to specify an S3 bucket, you'll get this error:

```
ExceptionInfo: S3 bucket is required for Braket backend. AWS Braket stores all quantum task results in S3.
{:type :missing-s3-bucket, 
 :config {...}, 
 :help "Provide :s3-bucket in the config map, e.g., {:s3-bucket \"my-braket-results\"}"}
```

## Cost Considerations

- S3 storage costs apply to your quantum task results
- Results are typically small (few KB to few MB)
- Consider setting up S3 lifecycle policies to automatically delete old results
- You can use S3 Standard-IA or Glacier for long-term storage of historical results

## Security Best Practices

1. **Use dedicated buckets**: Create separate S3 buckets for Braket results
2. **Enable encryption**: Use S3 server-side encryption (SSE-S3 or SSE-KMS)
3. **Set lifecycle policies**: Automatically transition or delete old results
4. **Monitor access**: Enable CloudTrail for S3 API logging
5. **Use IAM roles**: Prefer IAM roles over long-term access keys

## Troubleshooting

### Common Issues

1. **Bucket doesn't exist**: Ensure the S3 bucket is created in the correct region
2. **Access denied**: Check that your AWS credentials have S3 permissions
3. **Cross-region issues**: Some Braket devices are region-specific

### Testing Your Setup

```clojure
;; Test that your backend can be created successfully
(def test-backend 
  (braket/create-braket-simulator {:s3-bucket "my-braket-results-bucket"}))

;; Check that the configuration is correct
(println (:config test-backend))
;; Should show your S3 bucket configuration
```
