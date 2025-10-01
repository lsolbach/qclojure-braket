In this project we build a production grade Amazon Braket backend for the QClojure project, not some toy implementation.
The QClojure codebase is available in the sibling directory '../qclojure'
or as github repo 'lsolbach/qclojure'.
Some relevant namespaces in the QClojure codebase are
* `org.soulspace.qclojure.domain.circuit` - quantum circuits
* `org.soulspace.qclojure.application.backend` - backend protocols
* `org.soulspace.qclojure.application.hardware-optimization` - hardware optimization
* `org.soulspace.qclojure.application.error-mitigation` - error mitigation
* `org.soulspace.qclojure.adapter.backend.ideal-simulator` - simulator backend
* `org.soulspace.qclojure.adapter.backend.hardware-simulator` - noisy simulator backend
* `org.soulspace.qclojure.application.format.qasm3` - qasm3 formatting

For the math we use the fastmath library.
For the aws api we use the cognitect aws-api library.
Double precision is used for the math, but we can switch to arbitrary precision later.

To get information about the qclojure codebase, you can use the github_repo tool with lsolbach/qclojure. Please use the tag matching the version referenced in the dependencies.
You can also use the github_repo tool to search for specific functions or namespaces in the codebase.
