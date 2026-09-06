#!/usr/bin/env python3
"""Validate public OIDC metadata without printing external response payloads."""

import json
import os
import sys


try:
    metadata = json.load(sys.stdin)
except ValueError:
    raise SystemExit("External Keycloak discovery did not return valid JSON")

expected = {
    "issuer": os.environ["BEANFLOW_OPERATIONS_OIDC_ISSUER_URI"],
    "jwks_uri": os.environ["BEANFLOW_JWK_SET_URI"],
}
if not isinstance(metadata, dict) or any(metadata.get(key) != value for key, value in expected.items()):
    raise SystemExit("External Keycloak discovery issuer or JWKS differs from deployment settings")
methods = metadata.get("code_challenge_methods_supported", [])
if not isinstance(methods, list) or "S256" not in methods:
    raise SystemExit("External Keycloak must support PKCE S256")
print("External Keycloak discovery contract passed.")
