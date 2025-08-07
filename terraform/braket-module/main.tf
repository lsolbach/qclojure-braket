resource "aws_s3_bucket" "braket_bucket" {
  bucket = var.bucket_name

  force_destroy = true

  tags = {
    Name        = "BraketOutput"
    Environment = var.environment
  }
}

resource "aws_iam_role" "braket_execution_role" {
  name = "${var.name_prefix}-braket-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect = "Allow",
        Principal = {
          AWS = "*"
        },
        Action = "sts:AssumeRole"
      }
    ]
  })
}

resource "aws_iam_policy" "braket_policy" {
  name = "${var.name_prefix}-braket-policy"

  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect = "Allow",
        Action = ["braket:*"],
        Resource = "*"
      },
      {
        Effect = "Allow",
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:ListBucket"
        ],
        Resource = [
          aws_s3_bucket.braket_bucket.arn,
          "${aws_s3_bucket.braket_bucket.arn}/*"
        ]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "policy_attachment" {
  role       = aws_iam_role.braket_execution_role.name
  policy_arn = aws_iam_policy.braket_policy.arn
}
