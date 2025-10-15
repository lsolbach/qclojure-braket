# Amazon Braket QPU infos

## IonQ
* Trapped Ion

### Supported Gates
'x', 'y', 'z', 'rx', 'ry', 'rz', 'h', 'cnot', 's', 'si', 't', 'ti', 'v', 'vi', 'xx', 'yy', 'zz', 'swap'

### Verbatim Gates
'gpi', 'gpi2', 'ms'

## IQM
* Superconducting
* Garnet 20 Qubit
* Emerald 54 Qubit

### Supported Gates
'ccnot', 'cnot', 'cphaseshift', 'cphaseshift00', 'cphaseshift01', 'cphaseshift10', 'cswap', 'swap', 'iswap', 'pswap', 'ecr', 'cy', 'cz', 'xy', 'xx', 'yy', 'zz', 'h', 'i', 'phaseshift', 'rx', 'ry', 'rz', 's', 'si', 't', 'ti', 'v', 'vi', 'x', 'y', 'z'

### Verbatim Gates
'cz', 'prx'

## Rigetti
* Superconducting
* Ankaa-3 84 Qubit

### Supported Gates
'cz', 'xy', 'ccnot', 'cnot', 'cphaseshift', 'cphaseshift00', 'cphaseshift01', 'cphaseshift10', 'cswap', 'h', 'i', 'iswap', 'phaseshift', 'pswap', 'rx', 'ry', 'rz', 's', 'si', 'swap', 't', 'ti', 'x', 'y', 'z'

### Verbatim Gates
'rx', 'rz', 'iswap' (Rigetti superconducting quantum processors can run the 'rx' gate with only the angles of ±π/2 or ±π)

### Pulse Control
`flux_tx`, `charge_tx`, `readout_rx`, `readout_tx`


## QuEra
* Analog Hamiltonian Simulation


 [Submitting quantum tasks to QPUs](https://docs.aws.amazon.com/braket/latest/developerguide/braket-submit-tasks.html)