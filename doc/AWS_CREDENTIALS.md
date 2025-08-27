# AWS Credentials Setup

## Overview

To use the QClojure Braket backend, you need to configure AWS credentials that have permissions to access Amazon Braket and S3 services.

## Methods to Configure AWS Credentials

### 1. AWS CLI Configuration (Recommended for Development)

Install and configure the AWS CLI:

```bash
# Install AWS CLI (if not already installed)
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install

# Configure credentials
aws configure
```

You'll be prompted for:
- **AWS Access Key ID**: Your access key
- **AWS Secret Access Key**: Your secret key
- **Default region name**: e.g., `us-east-1` (recommended for Braket)
- **Default output format**: `json` (recommended)

### 2. Environment Variables

Set environment variables in your shell or application:

```bash
export AWS_ACCESS_KEY_ID="your-access-key-id"
export AWS_SECRET_ACCESS_KEY="your-secret-access-key"
export AWS_DEFAULT_REGION="us-east-1"
```

For persistent configuration, add these to your `~/.bashrc` or `~/.zshrc`:

```bash
# Add to ~/.bashrc or ~/.zshrc
export AWS_ACCESS_KEY_ID="your-access-key-id"
export AWS_SECRET_ACCESS_KEY="your-secret-access-key"
export AWS_DEFAULT_REGION="us-east-1"
```

### 3. IAM Roles (Recommended for Production)

When running on AWS infrastructure (EC2, Lambda, ECS, etc.), use IAM roles instead of access keys:

1. Create an IAM role with the required permissions (see below)
2. Attach the role to your AWS service
3. The AWS SDK will automatically use the role's credentials

### 4. AWS Credentials File

Create `~/.aws/credentials`:

```ini
[default]
aws_access_key_id = your-access-key-id
aws_secret_access_key = your-secret-access-key

[braket-profile]
aws_access_key_id = your-braket-specific-access-key
aws_secret_access_key = your-braket-specific-secret-key
```

And `~/.aws/config`:

```ini
[default]
region = us-east-1
output = json

[profile braket-profile]
region = us-east-1
output = json
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
                "braket:GetDevice",
                "braket:GetQuantumTask",
                "braket:SearchDevices",
                "braket:CreateQuantumTask",
                "braket:CancelQuantumTask",
                "braket:GetDeviceAvailability"
            ],
            "Resource": "*"
        },
        {
            "Effect": "Allow",
            "Action": [
                "s3:GetObject",
                "s3:PutObject",
                "s3:DeleteObject",
                "s3:ListBucket"
            ],
            "Resource": [
                "arn:aws:s3:::your-braket-results-bucket",
                "arn:aws:s3:::your-braket-results-bucket/*"
            ]
        },
        {
            "Effect": "Allow",
            "Action": [
                "pricing:GetProducts",
                "pricing:DescribeServices"
            ],
            "Resource": "*"
        }
    ]
}
```

## Creating IAM User for Braket

### 1. Create IAM User

```bash
aws iam create-user --user-name braket-user
```

### 2. Create and Attach Policy

Save the permissions JSON above to `braket-policy.json`, then:

```bash
aws iam create-policy \
    --policy-name BraketAccessPolicy \
    --policy-document file://braket-policy.json

aws iam attach-user-policy \
    --user-name braket-user \
    --policy-arn arn:aws:iam::YOUR-ACCOUNT-ID:policy/BraketAccessPolicy
```

### 3. Create Access Keys

```bash
aws iam create-access-key --user-name braket-user
```

Save the returned access key ID and secret access key securely.

## Security Best Practices

### 1. Use IAM Roles When Possible
- Prefer IAM roles over long-term access keys
- Roles automatically rotate credentials
- Easier to audit and manage

### 2. Principle of Least Privilege
- Only grant the minimum required permissions
- Use resource-specific permissions when possible
- Regularly review and update permissions

### 3. Rotate Access Keys Regularly
```bash
# List existing access keys
aws iam list-access-keys --user-name braket-user

# Create new access key
aws iam create-access-key --user-name braket-user

# Update your applications with new key
# Then delete old key
aws iam delete-access-key --user-name braket-user --access-key-id OLD-KEY-ID
```

### 4. Monitor Usage
- Enable CloudTrail for API call logging
- Set up CloudWatch alarms for unusual activity
- Review AWS costs regularly

## Troubleshooting

### Common Issues

#### "The security token included in the request is invalid"
**Cause**: Expired or incorrect credentials
**Solution**: 
- Refresh your credentials
- Check clock synchronization (important for temporary credentials)

#### "AccessDenied" when creating quantum tasks
**Cause**: Missing Braket permissions
**Solution**: 
- Verify IAM policy includes all required Braket actions
- Check if you're using the correct AWS region

#### "Access Denied" for S3 operations
**Cause**: Missing S3 permissions or incorrect bucket name
**Solution**:
- Verify S3 permissions in your IAM policy
- Ensure bucket name in policy matches your configuration
- Check bucket exists and is in the correct region

### Verification Commands

Test your AWS credentials:

```bash
# Test basic AWS access
aws sts get-caller-identity

# Test Braket access
aws braket search-devices

# Test S3 access (replace with your bucket name)
aws s3 ls s3://your-braket-results-bucket
```

### Regional Considerations

- Most Braket devices are in `us-east-1`
- Some devices are in `us-west-1` and `eu-west-2`
- Your S3 bucket should be in the same region as the Braket devices you plan to use
- Cross-region data transfer incurs additional costs

## Using Credentials in QClojure

Once credentials are configured, use them in your QClojure code:

```clojure
;; Using default credentials (from CLI, environment, or IAM role)
(def backend 
  (braket/create-braket-simulator {:s3-bucket "your-bucket-name"}))

;; Using specific profile
(def backend 
  (braket/create-braket-backend {:aws-profile "braket-profile"
                                 :s3-bucket "your-bucket-name"}))

;; Using specific region
(def backend 
  (braket/create-braket-simulator {:s3-bucket "your-bucket-name"
                                   :region "us-west-1"}))
```

The QClojure Braket backend will automatically pick up your AWS credentials from any of the configured sources above.
