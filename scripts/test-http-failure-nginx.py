#!/usr/bin/env python3
"""Verify support-code correlation using the product Nginx configuration and runtime image."""

import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
IMAGE = re.search(r"^FROM (nginxinc/nginx-unprivileged:[^\s]+)$", (ROOT / "frontend/Dockerfile").read_text(), re.MULTILINE).group(1)
REFERENCE = "c736d39b-64a5-43c4-901b-314ff05dcb44"


def docker(*args):
    return subprocess.run(["docker", *args], check=True, capture_output=True, text=True).stdout.strip()


def main():
    identity = "beanflow-support-log-" + uuid.uuid4().hex[:10]
    network = identity + "-network"
    upstream = identity + "-upstream"
    proxy = identity + "-proxy"
    created = []
    docker("network", "create", network)
    try:
        with tempfile.TemporaryDirectory(prefix="beanflow-support-nginx-") as temporary:
            stub = Path(temporary) / "upstream.conf"
            stub.write_text(
                "server { listen 8080; location / { "
                f"add_header X-Correlation-Id {REFERENCE} always; "
                "default_type application/json; return 503 '{\"code\":\"DEPENDENCY_UNAVAILABLE\"}'; } }"
            )
            docker("run", "-d", "--name", upstream, "--network", network, "--network-alias", "api",
                   "--network-alias", "keycloak", "-v", f"{stub}:/etc/nginx/conf.d/default.conf:ro", IMAGE)
            created.append(upstream)
            docker("run", "-d", "--name", proxy, "--network", network, "-p", "127.0.0.1::8080",
                   "-v", f"{ROOT / 'frontend/nginx/default.conf'}:/etc/nginx/conf.d/default.conf:ro", IMAGE)
            created.append(proxy)
            docker("exec", proxy, "nginx", "-t")
            port = json.loads(docker("inspect", proxy))[0]["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
            base = f"http://127.0.0.1:{port}"
            for attempt in range(30):
                try:
                    with urllib.request.urlopen(base + "/healthz", timeout=1) as response:
                        assert response.status == 200
                    break
                except (OSError, urllib.error.URLError):
                    if attempt == 29:
                        raise
                    time.sleep(0.1)
            request = urllib.request.Request(base + "/api/v1/private-order?ticket=private-ticket",
                                             headers={"Authorization": "private-auth", "Cookie": "private-cookie"})
            try:
                urllib.request.urlopen(request, timeout=3)
                raise AssertionError("upstream failure must remain 503")
            except urllib.error.HTTPError as error:
                assert error.code == 503
                assert error.headers["X-Correlation-Id"] == REFERENCE
                assert json.loads(error.read())["code"] == "DEPENDENCY_UNAVAILABLE"
            logs = docker("logs", proxy)
            line = next(line for line in logs.splitlines() if "status=503" in line)
            assert f"correlation_id={REFERENCE}" in line
            assert re.search(r"request_id=[a-f0-9]{32}", line)
            assert "method=GET" in line
            assert all(value not in logs for value in ["private-order", "private-ticket", "private-auth", "private-cookie"])
            try:
                urllib.request.urlopen(base + "/auth/admin/", timeout=3)
                raise AssertionError("local rejection must remain 404")
            except urllib.error.HTTPError as error:
                assert error.code == 404
            rejection = next(line for line in docker("logs", proxy).splitlines() if "status=404" in line)
            assert "correlation_id=-" in rejection
            print("Passed: Nginx config, 503/header/log correlation, raw request non-exposure, local 404")
    finally:
        for container in reversed(created):
            docker("rm", "-f", container)
        docker("network", "rm", network)


if __name__ == "__main__":
    main()
