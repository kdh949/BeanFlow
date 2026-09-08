#!/usr/bin/env python3
"""Materialize a validated Doppler snapshot for the file-backed Compose contract."""

import argparse
import os
from pathlib import Path
import tempfile

ROOT = Path(__file__).resolve().parents[2]
COMMON_SECRETS = (
    "BEANFLOW_POSTGRES_PASSWORD",
    "BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL",
    "BEANFLOW_CURSOR_HMAC_SECRET_BASE64_URL",
    "BEANFLOW_AISTOR_ACCESS_KEY",
    "BEANFLOW_AISTOR_SECRET_KEY",
    "TOSS_CLIENT_KEY",
    "TOSS_SECRET_KEY",
    "BEANFLOW_VAULT_ROLE_ID",
    "BEANFLOW_VAULT_SECRET_ID",
    "BEANFLOW_VAULT_CA_PEM",
)
KEYCLOAK_SECRETS = ("BEANFLOW_KEYCLOAK_DB_PASSWORD", "BEANFLOW_KEYCLOAK_ADMIN_PASSWORD")
OIDC_KEYS = (
    "BEANFLOW_JWK_SET_URI",
    "BEANFLOW_OPERATIONS_OIDC_ISSUER_URI",
    "BEANFLOW_OPERATIONS_OIDC_AUTHORIZATION_SERVER_URL",
    "BEANFLOW_OPERATIONS_OIDC_REALM",
    "BEANFLOW_OPERATIONS_OIDC_CLIENT_ID",
)


def fail(message):
    raise SystemExit(message)


def required(values, name):
    value = values.get(name, "")
    if not value.strip():
        fail(f"Required Doppler value is missing or empty: {name}")
    return value


def write_private(path, content):
    # Replacing the complete file avoids truncating a file currently mounted by a container.
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as output:
            output.write(content)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def prepare(environment, values):
    mode = values.get("BEANFLOW_KEYCLOAK_MODE", "bundled")
    if mode not in ("bundled", "external"):
        fail("BEANFLOW_KEYCLOAK_MODE must be bundled or external")
    template = ROOT / f"deploy/env/{environment}.env.example"
    settings = {
        line.split("=", 1)[0]: required(values, line.split("=", 1)[0])
        for line in template.read_text().splitlines()
        if line and not line.startswith("#")
    }
    settings["BEANFLOW_KEYCLOAK_MODE"] = mode
    if mode == "external":
        settings.update({name: required(values, name) for name in OIDC_KEYS})
    for name, value in settings.items():
        if any(character.isspace() or character in "\x00$'\"\\#" for character in value):
            fail(f"Deployment setting cannot contain multiline or dotenv interpolation characters: {name}")

    secret_names = COMMON_SECRETS + (KEYCLOAK_SECRETS if mode == "bundled" else ())
    secrets = {name: required(values, name) for name in secret_names}
    directory = Path(settings["BEANFLOW_SECRETS_DIR"])
    resolved = directory.resolve()
    if not directory.is_absolute() or resolved == Path("/") or resolved.is_relative_to(ROOT):
        fail("BEANFLOW_SECRETS_DIR must be an absolute directory outside the repository")
    if resolved != directory or directory.parent == Path("/"):
        fail("BEANFLOW_SECRETS_DIR must be a canonical path under a dedicated deployment directory")

    # Validate every value and existing file before writing any file. Credential rotation is separate.
    for name, value in secrets.items():
        path = directory / name
        if path.is_symlink() or (path.exists() and not path.is_file()):
            fail(f"Secret path must be a regular file: {name}")
        if path.exists() and path.read_text().rstrip("\r\n") != value.rstrip("\r\n"):
            fail(f"Existing secret differs from Doppler; reconcile the credential before deployment: {name}")
    env_file = directory.parent / "deployment.env"
    if env_file.is_symlink() or (env_file.exists() and not env_file.is_file()):
        fail("deployment.env must be a regular file")

    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    directory.chmod(0o700)
    for name, value in secrets.items():
        path = directory / name
        if not path.exists():
            write_private(path, value)
        path.chmod(0o600)
    write_private(env_file, "".join(f"{name}={value}\n" for name, value in settings.items()))
    return env_file


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("environment", choices=("staging", "prod"))
    args = parser.parse_args()
    prepare(args.environment, os.environ)
    print("Doppler deployment files prepared; secret values were not printed.")
