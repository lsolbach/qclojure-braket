# S3 Bucket for Braket Results
resource "aws_s3_bucket" "braket_bucket" {
  bucket = var.bucket_name

  tags = merge(var.tags, {
    Name    = "Braket Results Bucket"
    Purpose = "AWS Braket Quantum Task Results Storage"
  })
}

# Block all public access to the bucket
resource "aws_s3_bucket_public_access_block" "braket_bucket_pab" {
  bucket = aws_s3_bucket.braket_bucket.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Enable versioning if requested
resource "aws_s3_bucket_versioning" "braket_bucket_versioning" {
  bucket = aws_s3_bucket.braket_bucket.id
  versioning_configuration {
    status = var.enable_versioning ? "Enabled" : "Disabled"
  }
}

# Server-side encryption
resource "aws_s3_bucket_server_side_encryption_configuration" "braket_bucket_encryption" {
  count  = var.enable_encryption ? 1 : 0
  bucket = aws_s3_bucket.braket_bucket.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
    bucket_key_enabled = true
  }
}

# Lifecycle configuration to manage costs
resource "aws_s3_bucket_lifecycle_configuration" "braket_bucket_lifecycle" {
  count  = var.lifecycle_expiration_days > 0 ? 1 : 0
  bucket = aws_s3_bucket.braket_bucket.id

  rule {
    id     = "braket_results_lifecycle"
    status = "Enabled"

    # Transition to Standard-IA after 30 days
    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }

    # Transition to Glacier after 60 days
    transition {
      days          = 60
      storage_class = "GLACIER"
    }

    # Delete after specified days
    expiration {
      days = var.lifecycle_expiration_days
    }

    # Clean up incomplete multipart uploads
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# Bucket policy to allow Braket service access
resource "aws_s3_bucket_policy" "braket_bucket_policy" {
  bucket = aws_s3_bucket.braket_bucket.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowBraketServiceAccess"
        Effect = "Allow"
        Principal = {
          Service = "braket.amazonaws.com"
        }
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.braket_bucket.arn,
          "${aws_s3_bucket.braket_bucket.arn}/*"
        ]
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = var.aws_account_id
          }
        }
      },
      {
        Sid    = "AllowAccountAccess"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${var.aws_account_id}:root"
        }
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.braket_bucket.arn,
          "${aws_s3_bucket.braket_bucket.arn}/*"
        ]
      }
    ]
  })

  depends_on = [aws_s3_bucket_public_access_block.braket_bucket_pab]
}

# IAM role for Braket execution (if needed for programmatic access)
resource "aws_iam_role" "braket_execution_role" {
  name = "${var.name_prefix}-braket-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${var.aws_account_id}:root"
        }
        Action = "sts:AssumeRole"
        Condition = {
          StringEquals = {
            "sts:ExternalId" = "${var.name_prefix}-braket-access"
          }
        }
      }
    ]
  })

  tags = merge(var.tags, {
    Name    = "Braket Execution Role"
    Purpose = "Programmatic access to Braket and S3"
  })
}

# IAM policy for Braket and S3 access
resource "aws_iam_policy" "braket_policy" {
  name        = "${var.name_prefix}-braket-policy"
  description = "Policy for QClojure Braket backend access to AWS Braket and S3"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "BraketAccess"
        Effect = "Allow"
        Action = [
          "braket:GetDevice",
          "braket:GetQuantumTask",
          "braket:SearchDevices",
          "braket:CreateQuantumTask",
          "braket:CancelQuantumTask",
          "braket:GetDeviceAvailability"
        ]
        Resource = "*"
      },
      {
        Sid    = "S3BucketAccess"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.braket_bucket.arn,
          "${aws_s3_bucket.braket_bucket.arn}/*"
        ]
      },
      {
        Sid    = "PricingAccess"
        Effect = "Allow"
        Action = [
          "pricing:GetProducts",
          "pricing:DescribeServices"
        ]
        Resource = "*"
      }
    ]
  })

  tags = var.tags
}

# Attach policy to role
resource "aws_iam_role_policy_attachment" "braket_policy_attachment" {
  role       = aws_iam_role.braket_execution_role.name
  policy_arn = aws_iam_policy.braket_policy.arn
}

# Optional: CloudWatch log group for monitoring
resource "aws_cloudwatch_log_group" "braket_logs" {
  name              = "/aws/braket/${var.name_prefix}"
  retention_in_days = 30

  tags = merge(var.tags, {
    Name    = "Braket Logs"
    Purpose = "Braket task execution logs"
  })
}
