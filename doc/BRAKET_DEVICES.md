# Amazon Braket Devices and Providers

## Overview

Amazon Braket provides access to quantum computing hardware from multiple providers, as well as high-performance quantum simulators. This guide covers the available devices, their capabilities, and how to use them with QClojure.

## Device Types

### 1. Quantum Simulators
- **Cost**: Lower cost, pay per shot
- **Availability**: Always available
- **Use case**: Development, testing, large circuit simulation

### 2. Quantum Processing Units (QPUs)  
- **Cost**: Higher cost, pay per shot + per-task fees
- **Availability**: Limited scheduling windows
- **Use case**: Real quantum hardware experiments

## Available Providers

### Amazon (Simulators)

#### SV1 - State Vector Simulator
- **ARN**: `arn:aws:braket:::device/quantum-simulator/amazon/sv1`
- **Max Qubits**: 34
- **Supported Gates**: All standard gates
- **Use Case**: Exact simulation with full state vector
- **Region**: All Braket regions

#### TN1 - Tensor Network Simulator  
- **ARN**: `arn:aws:braket:::device/quantum-simulator/amazon/tn1`
- **Max Qubits**: 50+
- **Supported Gates**: Limited set optimized for tensor networks
- **Use Case**: Large circuits with specific structure
- **Region**: All Braket regions

#### DM1 - Density Matrix Simulator
- **ARN**: `arn:aws:braket:::device/quantum-simulator/amazon/dm1`
- **Max Qubits**: 17
- **Supported Gates**: All gates + noise modeling
- **Use Case**: Noise simulation and error analysis
- **Region**: All Braket regions

### Rigetti (Superconducting QPUs)

#### Aspen-M-3
- **ARN**: `arn:aws:braket:us-west-1::device/qpu/rigetti/aspen-m-3`
- **Qubits**: 80
- **Topology**: Limited connectivity (2D grid-like)
- **Native Gates**: RZ, RX, CZ
- **Region**: us-west-1
- **Availability**: Scheduled windows

**Characteristics**:
- Fast gate operations (~10-100 ns)
- Limited qubit connectivity requires SWAP gates
- Good for variational algorithms (QAOA, VQE)

### IonQ (Trapped Ion QPUs)

#### Aria-1 
- **ARN**: `arn:aws:braket:us-east-1::device/qpu/ionq/aria-1`
- **Qubits**: 25
- **Topology**: All-to-all connectivity
- **Native Gates**: RX, RY, RZ, XX
- **Region**: us-east-1
- **Availability**: Scheduled windows

#### Forte-1
- **ARN**: `arn:aws:braket:us-east-1::device/qpu/ionq/forte-1`
- **Qubits**: 32
- **Topology**: All-to-all connectivity  
- **Native Gates**: RX, RY, RZ, XX
- **Region**: us-east-1
- **Availability**: Scheduled windows

**Characteristics**:
- High-fidelity operations
- All-to-all connectivity (no SWAP gates needed)
- Slower gate operations (~10-100 μs)
- Excellent for quantum algorithms requiring long-range entanglement

### IQM (Superconducting QPUs)

#### Garnet
- **ARN**: `arn:aws:braket:eu-north-1::device/qpu/iqm/garnet`
- **Qubits**: 20
- **Topology**: Custom connectivity
- **Native Gates**: RZ, XY, CZ
- **Region**: eu-north-1
- **Availability**: Scheduled windows

### Oxford Quantum Computing (OQC)

#### Lucy
- **ARN**: `arn:aws:braket:eu-west-2::device/qpu/oqc/lucy`
- **Qubits**: 8
- **Topology**: Ring connectivity
- **Native Gates**: RZ, RX, ECR
- **Region**: eu-west-2
- **Availability**: Scheduled windows

### QuEra (Neutral Atom QPUs)

#### Aquila
- **ARN**: `arn:aws:braket:us-east-1::device/qpu/quera/aquila`
- **Qubits**: 256
- **Topology**: Analog quantum simulation
- **Native Gates**: Analog Hamiltonian evolution
- **Region**: us-east-1
- **Availability**: Scheduled windows

**Characteristics**:
- Large qubit count
- Analog quantum simulation paradigm
- Specialized for optimization problems
- Different programming model from gate-based systems

## Device Selection in QClojure

### By Device Type

```clojure
;; Use simulator for development
(def sim-backend
  (braket/create-braket-simulator {:s3-bucket "my-bucket"}))

;; Use specific QPU
(def rigetti-backend
  (braket/create-braket-qpu "arn:aws:braket:us-west-1::device/qpu/rigetti/aspen-m-3"
                            {:s3-bucket "my-bucket"
                             :region "us-west-1"}))
```

### By Provider

```clojure
;; Get provider-specific information
(braket/get-provider-info sim-backend :rigetti)
;; => {:provider :rigetti
;;     :supported-gates #{:rz :rx :cz :i :measure}
;;     :constraints {:max-qubits 80, :native-connectivity :grid}
;;     :pricing-multiplier 1.0}

(braket/get-provider-info sim-backend :ionq)  
;; => {:provider :ionq
;;     :supported-gates #{:rx :ry :rz :xx :i :measure}
;;     :constraints {:max-qubits 32, :native-connectivity :all-to-all}
;;     :pricing-multiplier 2.5}
```

## Hardware Optimization by Provider

The QClojure Braket backend automatically optimizes circuits for each provider:

### Rigetti Optimization
- **SWAP insertion**: For non-adjacent qubit operations
- **Gate decomposition**: Complex gates → RZ, RX, CZ
- **Topology mapping**: Map logical to physical qubits

### IonQ Optimization  
- **Native gate compilation**: Use XX gates for entanglement
- **All-to-all advantage**: No SWAP gates needed
- **Gate sequence optimization**: Minimize operation time

### IQM/OQC Optimization
- **Provider-specific decomposition**: Custom gate sets
- **Connectivity constraints**: Respect device topology
- **Calibration data**: Use latest device calibration

## Device Availability and Scheduling

### Checking Availability

```clojure
;; Check device status
(braket/get-device-info backend "arn:aws:braket:us-east-1::device/qpu/ionq/aria-1")
;; => {:provider :ionq
;;     :status :online
;;     :availability-window {:start "2025-08-27T10:00:00Z"
;;                          :end "2025-08-27T18:00:00Z"}
;;     :queue-depth 5}
```

### QPU Scheduling
- Most QPUs have scheduled availability windows
- Queue times vary based on demand  
- Simulators are always available
- Some devices may be offline for maintenance

## Cost Considerations

### Simulator Pricing (approximate)
- **SV1**: $0.075 per shot
- **TN1**: $0.275 per shot  
- **DM1**: $0.075 per shot

### QPU Pricing (approximate)
- **Per-shot fees**: $0.00035 - $0.01 per shot
- **Per-task fees**: $0.30 - $3.00 per task
- **Provider multipliers**: Vary by device

### Cost Estimation

```clojure
;; Estimate cost before running
(qb/estimate-cost backend circuit {:shots 1000})
;; => {:total-cost 2.45
;;     :cost-breakdown {:shots-cost 0.35
;;                     :task-cost 3.00
;;                     :provider-multiplier 1.2}}
```

## Best Practices for Device Selection

### For Development and Testing
- Use **Amazon SV1** simulator
- Unlimited shots for debugging
- Perfect for algorithm development

### For Noise Studies  
- Use **Amazon DM1** simulator
- Built-in noise modeling
- Study error mitigation strategies

### For Real Quantum Experiments

#### Choose Rigetti if:
- Circuit has limited connectivity requirements
- Need fast gate operations  
- Working with variational algorithms

#### Choose IonQ if:
- Need high-fidelity operations
- Circuit requires long-range entanglement
- All-to-all connectivity is beneficial

#### Choose IQM/OQC if:
- Experimenting with European devices
- Specific to their unique capabilities
- Research collaboration requirements

#### Choose QuEra if:
- Working on optimization problems
- Need large qubit counts
- Analog quantum simulation approach

## Device-Specific Examples

### Rigetti Circuit Optimization

```clojure
(def rigetti-circuit
  (-> (qc/create-circuit 3)
      (qc/h-gate 0)
      (qc/cnot-gate 0 2)))  ; Non-adjacent, will need SWAP

;; Backend automatically optimizes for Rigetti topology
(qb/submit-circuit rigetti-backend rigetti-circuit 
                   {:shots 1000
                    :optimize-for-device? true})
```

### IonQ All-to-All Connectivity

```clojure
(def ionq-circuit
  (-> (qc/create-circuit 5)
      (qc/h-gate 0)
      (qc/cnot-gate 0 4)    ; Direct connection possible
      (qc/cnot-gate 1 3)))  ; No SWAPs needed

(qb/submit-circuit ionq-backend ionq-circuit {:shots 1000})
```

## Regional Considerations

### Device Locations
- **us-east-1**: IonQ (Aria-1, Forte-1), QuEra (Aquila)
- **us-west-1**: Rigetti (Aspen-M-3)  
- **eu-north-1**: IQM (Garnet)
- **eu-west-2**: OQC (Lucy)

### S3 Bucket Placement
- Place S3 bucket in same region as target devices
- Cross-region transfers incur additional costs
- Results are automatically stored in your bucket

## Monitoring and Troubleshooting

### Device Status Monitoring

```clojure
;; Get all available devices
(braket/list-devices backend)

;; Check specific device
(braket/get-device-info backend device-arn)

;; Check queue status  
(braket/get-device-availability backend device-arn)
```

### Common Issues

**Device Offline**
- Check device availability windows
- Some devices have maintenance schedules
- Use simulators for development

**Queue Delays**
- QPUs can have long queue times
- Plan experiments during off-peak hours
- Consider using multiple devices

**Regional Access**
- Ensure your AWS account has access to device regions
- Some devices may have geographic restrictions

## Further Resources

- [AWS Braket Developer Guide](https://docs.aws.amazon.com/braket/)
- [Device Calibration Data](https://docs.aws.amazon.com/braket/latest/developerguide/braket-devices.html)
- [Pricing Calculator](https://aws.amazon.com/braket/pricing/)
- [Device Availability](https://aws.amazon.com/braket/quantum-computers/)

For hands-on examples with different devices, see the [Braket Tutorial](BRAKET_TUTORIAL.md).
