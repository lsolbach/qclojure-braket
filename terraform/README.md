# Terraform Infrastructure for QClojure Braket Backend

This Terraform configuration automatically sets up the required AWS infrastructure for the QClojure Braket backend, following security best practices and cost optimization.

## Quick Start

```bash
# Copy example configuration
cp terraform.tfvars.example terraform.tfvars

# Edit with your settings
nano terraform.tfvars

# Deploy
terraform init
terraform plan
terraform apply
```

## What Gets Created

- **S3 Bucket**: Encrypted storage for Braket quantum task results
- **IAM Roles**: Secure access to Braket and S3 services  
- **Bucket Policies**: Allow Braket service to store results
- **Lifecycle Policies**: Automatic cost management
- **CloudWatch Logs**: Monitoring and observability

## Configuration

Edit `terraform.tfvars`:

```hcl
bucket_name = "my-company-braket-results"  # Must be globally unique
environment = "prod"                       # dev, staging, or prod  
aws_region  = "us-east-1"                 # Most Braket devices here
```

## Outputs

After deployment, use the output configuration in your QClojure code:

```clojure
(def backend 
  (braket/create-braket-simulator 
    {:s3-bucket "your-terraform-bucket-name"     ; From output
     :s3-key-prefix "braket-results/"
     :region "us-east-1"}))                      ; From output
```

## Security Features

- S3 encryption enabled by default
- Public access completely blocked
- IAM policies follow least-privilege principle
- Account-restricted bucket policies
- Audit logging via CloudWatch

## Cost Management

- Automatic lifecycle transitions (Standard → IA → Glacier)
- Configurable retention periods
- Cleanup of incomplete uploads
- S3 cost optimization

## Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `bucket_name` | S3 bucket name (globally unique) | `my-braket-results-bucket` |
| `environment` | Environment (dev/staging/prod) | `dev` |
| `aws_region` | AWS region | `us-east-1` |
| `lifecycle_expiration_days` | Auto-delete after N days | `90` |

See `terraform.tfvars.example` for all available options.

## Cleanup

```bash
# WARNING: This deletes your S3 bucket and all results
terraform destroy
```

For detailed documentation, see [doc/S3_SETUP.md](../doc/S3_SETUP.md).
