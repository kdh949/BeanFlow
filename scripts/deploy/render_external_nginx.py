#!/usr/bin/env python3
"""Render the external-Keycloak proxy from non-secret deployment settings."""

import argparse
from pathlib import Path
import re
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]


def endpoint(values, key, schemes):
    value = values.get(key, "")
    try:
        parsed = urlsplit(value)
        valid = (
            re.fullmatch(r"https?://[A-Za-z0-9.\[\]:-]+/?", value)
            and parsed.scheme in schemes and parsed.hostname
            and (parsed.port is None or 1 <= parsed.port <= 65535)
        )
    except ValueError:
        valid = False
    if not valid:
        raise ValueError(f"{key} must be an absolute {'/'.join(schemes)} origin without credentials, path, query or fragment")
    return parsed


def origin(parsed):
    return parsed.scheme, parsed.hostname, parsed.port or (443 if parsed.scheme == "https" else 80)


def render(values):
    public = endpoint(values, "BEANFLOW_AISTOR_PUBLIC_ENDPOINT", ("https",))
    app = endpoint(values, "BEANFLOW_PUBLIC_ORIGIN", ("https",))
    template = (ROOT / "deploy/nginx/external-keycloak.conf").read_text()
    route = "    # AIStor uses a separate public origin; routing is owned by that origin."
    if origin(public) == origin(app):
        upstream = endpoint(values, "BEANFLOW_AISTOR_ENDPOINT", ("http", "https"))
        if origin(upstream) == origin(public):
            raise ValueError("BEANFLOW_AISTOR_ENDPOINT must not loop back to the public origin")
        bucket = values.get("BEANFLOW_AISTOR_BUCKET", "")
        if not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket) or bucket in {"api", "auth", "assets", "healthz"}:
            raise ValueError("BEANFLOW_AISTOR_BUCKET must be a valid bucket name without reserved route collisions")
        # Match the signer URL's canonical authority, including non-default ports.
        authority = public.hostname
        if ":" in authority:
            authority = f"[{authority}]"
        if public.port and public.port != 443:
            authority += f":{public.port}"
        route = (ROOT / "deploy/nginx/aistor-route.conf.template").read_text().rstrip()
        for name, value in {
            "BUCKET": bucket,
            "BUCKET_PATTERN": bucket.replace(".", r"\."),
            "UPSTREAM": upstream.geturl().rstrip("/"),
            "PUBLIC_AUTHORITY": authority,
            "TLS_HOST": upstream.hostname,
        }.items():
            route = route.replace("${" + name + "}", value)
    return template.replace("    # BEANFLOW_AISTOR_ROUTE", route)


def config_path(values):
    return Path(values["BEANFLOW_SECRETS_DIR"]).parent / "external-keycloak.conf"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", required=True, type=Path)
    args = parser.parse_args()
    values = dict(line.split("=", 1) for line in args.env_file.read_text().splitlines() if line and not line.startswith("#"))
    try:
        print(render(values), end="")
    except ValueError as error:
        parser.exit(1, f"Nginx configuration rejected: {error}\n")
