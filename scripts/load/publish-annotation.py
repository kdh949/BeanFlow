#!/usr/bin/env python3
"""Publish one bounded run annotation using the monitoring container's existing runtime credentials."""
import base64
import json
import re
import subprocess
import sys
import urllib.request


def validate(value):
    if set(value) != {"time", "timeEnd", "tags", "text"}:
        raise ValueError("Unexpected annotation fields")
    if any(type(value[k]) is not int for k in ["time", "timeEnd"]):
        raise ValueError("Expected epoch milliseconds")
    if not 0 < value["time"] <= value["timeEnd"] <= value["time"] + 86400000:
        raise ValueError("Invalid annotation interval")
    tags = value["tags"]
    if not isinstance(tags, list) or len(tags) != 2 or tags[0] != "beanflow-load" or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", tags[1]):
        raise ValueError("Invalid run tags")
    if not isinstance(value["text"], str) or not 1 <= len(value["text"]) <= 500:
        raise ValueError("Invalid run text")
    return value


def main():
    data = sys.stdin.buffer.read(8193)
    if len(data) > 8192:
        raise ValueError("Annotation is too large")
    annotation = validate(json.loads(data))
    container = json.loads(subprocess.check_output(["docker", "inspect", "orbit-monitoring-grafana-1"]))[0]
    env = dict(value.split("=", 1) for value in container["Config"]["Env"])
    auth = base64.b64encode((env["GF_SECURITY_ADMIN_USER"] + ":" + env["GF_SECURITY_ADMIN_PASSWORD"]).encode()).decode()
    # No env file changes or persistent credential copies. This host tool only posts annotations.
    request = urllib.request.Request("http://172.16.16.18:3000/api/annotations", data=json.dumps(annotation).encode(),
                                     headers={"Authorization": "Basic " + auth, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=15) as response:
        result = json.load(response)
        if response.status != 200 or "id" not in result:
            raise RuntimeError("Annotation creation failed")
    print("PUBLISHED")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Annotation publication failed: " + type(error).__name__, file=sys.stderr)
        raise SystemExit(1)
