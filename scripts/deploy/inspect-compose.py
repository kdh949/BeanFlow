#!/usr/bin/env python3
import json
import sys


def fail(message: str) -> None:
    raise SystemExit(f"compose contract failed: {message}")


document = json.load(sys.stdin)
services = document.get("services", {})
expected = {"api", "frontend", "keycloak", "postgres"}
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
    "BEANFLOW_VAULT_ROLE_ID",
    "BEANFLOW_VAULT_SECRET_ID",
    "BEANFLOW_VAULT_CA_PEM",
}
if not required_api_secrets.issubset(api_secret_targets):
    fail("API secret target set is incomplete")

networks = document.get("networks", {})
if not networks.get("backend", {}).get("internal"):
    fail("backend network must be internal")
if "backend" not in services["postgres"].get("networks", {}) or len(services["postgres"].get("networks", {})) != 1:
    fail("PostgreSQL must attach only to the internal backend network")

print("Compose contract passed.")
