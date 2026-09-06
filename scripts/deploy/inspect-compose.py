#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import sys
from urllib.parse import urlsplit


def fail(message: str) -> None:
    raise SystemExit(f"compose contract failed: {message}")


parser = argparse.ArgumentParser()
parser.add_argument("--keycloak-mode", choices=("bundled", "external"), default="bundled")
args = parser.parse_args()

document = json.load(sys.stdin)
services = document.get("services", {})
expected = {"api", "frontend", "postgres"}
if args.keycloak_mode == "bundled":
    expected.add("keycloak")
if set(services) != expected:
    fail(f"services must be exactly {sorted(expected)}")

published = []
for name, service in services.items():
    if not service.get("healthcheck"):
        fail(f"{name} requires a healthcheck")
    image = service.get("image", "")
    if not image or image.endswith(":latest") or ":latest@" in image:
        fail(f"{name} must use a non-latest image tag")
    for port in service.get("ports", []):
        published.append((name, port))

if len(published) != 1 or published[0][0] != "frontend":
    fail("only frontend may publish one host port")
frontend_port = published[0][1]
if frontend_port.get("target") != 8080 or frontend_port.get("host_ip") in {None, "", "0.0.0.0", "::"}:
    fail("frontend port must target 8080 on an explicit non-wildcard host address")

postgres_mounts = services["postgres"].get("volumes", [])
if not any(mount.get("type") == "volume" and mount.get("target") == "/var/lib/postgresql/data" for mount in postgres_mounts):
    fail("PostgreSQL data must use a named volume")
if any(mount.get("type") == "tmpfs" and mount.get("target") == "/var/lib/postgresql/data" for mount in postgres_mounts):
    fail("PostgreSQL data must not use tmpfs")

api_environment = services["api"].get("environment", {})
if api_environment.get("SPRING_PROFILES_ACTIVE") != "portfolio":
    fail("API must activate only the portfolio profile group")
if api_environment.get("SPRING_CONFIG_IMPORT") != "configtree:/run/secrets/":
    fail("API must import file-backed secrets through config tree")

api_secret_targets = {secret.get("target") for secret in services["api"].get("secrets", [])}
required_api_secrets = {
    "BEANFLOW_DB_PASSWORD",
    "BEANFLOW_AUTH_ATTEMPT_HMAC_KEY_BASE64_URL",
    "BEANFLOW_CURSOR_HMAC_SECRET_BASE64_URL",
    "BEANFLOW_AISTOR_ACCESS_KEY",
    "BEANFLOW_AISTOR_SECRET_KEY",
    "TOSS_CLIENT_KEY",
    "TOSS_SECRET_KEY",
}
if not required_api_secrets.issubset(api_secret_targets):
    fail("API secret target set is incomplete")

vault_secret_targets = {
    secret.get("source"): secret.get("target")
    for secret in services["api"].get("secrets", [])
    if secret.get("source") in {"vault_role_id", "vault_secret_id", "vault_ca_pem"}
}
expected_vault_secret_targets = {
    "vault_role_id": "/run/beanflow-vault-bootstrap/BEANFLOW_VAULT_ROLE_ID",
    "vault_secret_id": "/run/beanflow-vault-bootstrap/BEANFLOW_VAULT_SECRET_ID",
    "vault_ca_pem": "/run/beanflow-vault-bootstrap/BEANFLOW_VAULT_CA_PEM",
}
if vault_secret_targets != expected_vault_secret_targets:
    fail("Vault bootstrap secrets must be mounted outside the JVM config tree")

api_tmpfs = services["api"].get("tmpfs", [])
if not any(
    (isinstance(mount, str) and mount.split(":", 1)[0] == "/run/beanflow-vault")
    or (isinstance(mount, dict) and mount.get("target") == "/run/beanflow-vault")
    for mount in api_tmpfs
):
    fail("Vault Proxy requires an isolated runtime tmpfs")

networks = document.get("networks", {})
if not networks.get("backend", {}).get("internal"):
    fail("backend network must be internal")
if "backend" not in services["postgres"].get("networks", {}) or len(services["postgres"].get("networks", {})) != 1:
    fail("PostgreSQL must attach only to the internal backend network")

if args.keycloak_mode == "external":
    for name, service in services.items():
        if "keycloak" in service.get("depends_on", {}):
            fail(f"{name} must not depend on bundled Keycloak")
        if any(secret.get("source", "").startswith("keycloak_") for secret in service.get("secrets", [])):
            fail(f"{name} must not mount Keycloak credentials")
    if any(name.startswith("keycloak_") for name in document.get("secrets", {})):
        fail("external Keycloak must not define Keycloak credentials")
    if any(mount.get("target", "").startswith("/docker-entrypoint-initdb.d/") for mount in postgres_mounts):
        fail("external Keycloak must not initialize a Keycloak database")

    def oidc_url(key: str) -> str:
        value = api_environment.get(key, "")
        try:
            parsed = urlsplit(value)
            valid = (
                parsed.scheme == "https" and parsed.hostname and parsed.port != 0
                and parsed.username is None and parsed.password is None
                and not parsed.query and not parsed.fragment
                and not any(character.isspace() or ord(character) < 32 for character in value)
            )
        except ValueError:
            valid = False
        if not valid:
            fail(f"{key} must be an absolute HTTPS URL without credentials, query or fragment")
        return value

    base = oidc_url("BEANFLOW_OPERATIONS_OIDC_AUTHORIZATION_SERVER_URL")
    issuer = oidc_url("BEANFLOW_OPERATIONS_OIDC_ISSUER_URI")
    jwks = oidc_url("BEANFLOW_JWK_SET_URI")
    realm = api_environment.get("BEANFLOW_OPERATIONS_OIDC_REALM", "")
    client_id = api_environment.get("BEANFLOW_OPERATIONS_OIDC_CLIENT_ID", "")
    if not realm or not client_id or any(character.isspace() for character in realm + client_id):
        fail("external Keycloak realm and client ID must be non-blank and contain no whitespace")
    if issuer != f"{base.rstrip('/')}/realms/{realm}":
        fail("external Keycloak issuer must match authorization server URL and realm")
    if jwks != f"{issuer}/protocol/openid-connect/certs":
        fail("external Keycloak JWKS must belong to the configured issuer")

    nginx_source = Path(__file__).resolve().parents[2] / "deploy/nginx/external-keycloak.conf"
    nginx_mounts = services["frontend"].get("volumes", [])
    if not any(
        mount.get("target") == "/etc/nginx/conf.d/default.conf"
        and mount.get("source") == str(nginx_source) and mount.get("read_only") is True
        for mount in nginx_mounts
    ):
        fail("external Keycloak requires the read-only external Nginx configuration")

print("Compose contract passed.")
