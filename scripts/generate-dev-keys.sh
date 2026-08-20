#!/usr/bin/env bash
# Generates a development RSA key pair for auth-service.
# These keys are gitignored and MUST NOT be used in production.
#abcd
set -euo pipefail

KEY_DIR="services/auth-service/src/main/resources/keys"
mkdir -p "$KEY_DIR"

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEY_DIR/private.pem"
openssl rsa -pubout -in "$KEY_DIR/private.pem" -out "$KEY_DIR/public.pem"

echo "Development keys written to $KEY_DIR (gitignored)."
