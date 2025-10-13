# Changelog

## Version (NEXT)
* add target :braket to submit options
* updated QClojure version
* updated AWS API versions
* incorporated backend protocol changes
* implemented MultiDeviceBackend protocol
* enhanced device handling
* fixed `CreateQuantumTask` REST request
* changed default S3 bucket name, it has to start with `amazon-braket-`
  * please keep in mind that S3 bucket names hafe to be globally unique
* removed obsolete code

## Version 0.2.0
* updated dependencies
* refactored to use topology namespace and coupling functions
* registered project with `zenodo.org` for DOI generation
  * makes qclojure-braket releases citeable in papers

## Version 0.1.0
* added QClojure backend for Amazon Braket
* added a terraform module to create an S3 bucket and setup the roles
* added docs and examples
* added initial tutorial notebook

