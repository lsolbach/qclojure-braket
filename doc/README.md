# QClojure Braket Documentation

Welcome to the QClojure Braket documentation! This collection of guides will help you set up and use Amazon Braket quantum computing services with QClojure.

## Getting Started

### 1. Prerequisites
- Familiarity with [QClojure](https://github.com/lsolbach/qclojure) quantum computing basics
- AWS account with appropriate permissions
- Basic understanding of quantum circuits and algorithms

### 2. Setup Guides (Complete in Order)

#### [AWS Credentials Setup](AWS_CREDENTIALS.md)
Learn how to configure AWS credentials for accessing Braket services:
- AWS CLI configuration
- Environment variables
- IAM roles and policies
- Security best practices

#### [S3 Bucket Setup](S3_SETUP.md) 
Set up S3 storage for quantum task results (required by AWS Braket):
- Terraform-based setup (recommended)
- Manual AWS CLI setup
- Bucket policies and permissions
- Cost management strategies

### 3. Learning Resources

#### [Amazon Braket Devices](BRAKET_DEVICES.md)
Comprehensive guide to available quantum hardware and simulators:
- Quantum simulators (Amazon SV1, TN1, DM1)
- QPUs from Rigetti, IonQ, IQM, OQC, QuEra
- Device capabilities and limitations
- Provider-specific optimizations

#### [Braket Tutorial](BRAKET_TUTORIAL.md)
Step-by-step tutorial building on QClojure knowledge:
- Your first Braket circuit
- Hardware optimization features
- Error mitigation techniques
- Multi-provider comparisons
- Cost management
- Production best practices

## Documentation Structure

```
doc/
├── README.md              # This overview
├── AWS_CREDENTIALS.md     # AWS authentication setup
├── S3_SETUP.md           # S3 bucket configuration
├── BRAKET_DEVICES.md     # Available quantum devices
└── BRAKET_TUTORIAL.md    # Hands-on tutorial
```

## Quick Reference

### Basic Backend Creation

```clojure
(require '[org.soulspace.qclojure.adapter.backend.braket :as braket])

;; Simulator (always available, lower cost)
(def sim-backend
  (braket/create-braket-simulator {:s3-bucket "my-braket-results-bucket"}))

;; Real quantum hardware (scheduled, higher cost)
(def qpu-backend
  (braket/create-braket-qpu "arn:aws:braket:us-east-1::device/qpu/ionq/aria-1"
                            {:s3-bucket "my-braket-results-bucket"}))
```

### Circuit Submission

```clojure
(require '[org.soulspace.qclojure.domain.circuit :as qc]
         '[org.soulspace.qclojure.application.backend :as qb])

;; Create circuit
(def bell-circuit
  (-> (qc/create-circuit 2)
      (qc/h-gate 0)
      (qc/cnot-gate 0 1)))

;; Submit with optimization and error mitigation
(def job-id 
  (qb/submit-circuit qpu-backend bell-circuit 
                     {:shots 1000
                      :optimize-for-device? true
                      :apply-error-mitigation? true}))

;; Get results
(qb/get-job-result qpu-backend job-id)
```

## Key Features

### 🔧 **Hardware Optimization**
- Automatic circuit optimization for each quantum device
- Provider-specific gate decomposition
- Topology-aware qubit mapping
- SWAP insertion for limited connectivity devices

### 🛡️ **Error Mitigation**
- Zero-noise extrapolation (ZNE)
- Readout error correction
- Symmetry verification
- Virtual distillation for expectation values

### 💰 **Cost Management** 
- Real-time cost estimation
- Provider pricing comparisons
- Batch processing for efficiency
- S3 lifecycle policies for result storage

### 🌐 **Multi-Provider Support**
- Amazon simulators (SV1, TN1, DM1)
- Rigetti superconducting QPUs
- IonQ trapped ion systems
- IQM, OQC, QuEra quantum computers

## Common Workflows

### Development Workflow
1. Develop algorithm using QClojure local simulators
2. Test on Amazon Braket simulators
3. Validate on small QPU runs
4. Scale to production QPU workloads

### Research Workflow  
1. Design quantum experiment
2. Estimate costs across providers
3. Choose optimal device for experiment
4. Apply appropriate error mitigation
5. Analyze results with statistical methods

### Production Workflow
1. Optimize circuits for target hardware
2. Implement robust error handling
3. Monitor costs and performance
4. Set up automated result processing

## Troubleshooting

### Common Issues
- **S3 bucket not configured**: See [S3 Setup](S3_SETUP.md)
- **AWS credentials not found**: See [AWS Credentials](AWS_CREDENTIALS.md) 
- **Device offline**: Check device availability in [Braket Devices](BRAKET_DEVICES.md)
- **High costs**: Review cost optimization in [Tutorial Chapter 6](BRAKET_TUTORIAL.md#chapter-6-cost-management)

### Getting Help
- Check the specific guide for your issue
- Review AWS Braket documentation
- Verify your setup step-by-step
- Test with simulators before using QPUs

## Infrastructure as Code

For production deployments, see the Terraform configuration:

```bash
cd terraform/
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your settings
terraform init
terraform apply
```

This automatically sets up:
- S3 bucket with proper policies
- IAM roles and permissions  
- Cost management lifecycle policies
- Security configurations

See [Terraform README](../terraform/README.md) for details.

## Contributing

Found an issue or want to contribute?
- Report bugs via GitHub issues
- Submit documentation improvements
- Share quantum algorithms and examples
- Help improve device-specific optimizations

## License

This documentation is part of the QClojure Braket project, licensed under the Eclipse Public License 1.0.
