#!/usr/bin/env python3
"""Read one Compose project's Docker stats; never expose inspect/config/labels verbatim."""
import argparse
import concurrent.futures
import http.client
import ipaddress
import json
import re
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SERVICES = {"api", "frontend", "postgres", "postgres-exporter", "node-exporter", "alloy", "toss-driver"}


class DockerConnection(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect("/var/run/docker.sock")


def docker_get(path):
    connection = DockerConnection("localhost", timeout=8)
    try:
        connection.request("GET", path)
        response = connection.getresponse()
        if response.status != 200:
            raise RuntimeError("Docker read failed")
        return json.load(response)
    finally:
        connection.close()


def samples(service, stats, detail):
    labels = '{service="' + service + '"}'
    memory = stats["memory_stats"]
    usage = memory["usage"]
    cache = memory.get("stats", {}).get("inactive_file", memory.get("stats", {}).get("total_inactive_file", 0))
    cpu = stats["cpu_stats"]
    previous = stats["precpu_stats"]
    system_delta = cpu.get("system_cpu_usage", 0) - previous.get("system_cpu_usage", 0)
    cpu_delta = cpu["cpu_usage"]["total_usage"] - previous.get("cpu_usage", {}).get("total_usage", 0)
    cores = cpu.get("online_cpus", len(cpu["cpu_usage"].get("percpu_usage", [])))
    if system_delta <= 0 or cpu_delta < 0 or not cores:
        raise RuntimeError("Docker CPU interval unavailable")
    host = detail["HostConfig"]
    quota = host.get("NanoCpus", 0) / 1e9
    if not quota and host.get("CpuQuota", 0) > 0 and host.get("CpuPeriod", 0) > 0:
        quota = host["CpuQuota"] / host["CpuPeriod"]
    values = {
        "cpu_cores": cpu_delta / system_delta * cores,
        "cpu_limit_cores": quota,
        "memory_working_set_bytes": usage - cache if cache < usage else usage,
        "memory_limit_bytes": host.get("Memory", 0),
        "restart_count": detail["RestartCount"],
        "running": int(detail["State"]["Running"]),
        "pids": stats.get("pids_stats", {}).get("current", 0),
    }
    return [f"beanflow_container_{name}{labels} {value}" for name, value in values.items()]


def collect(project):
    filters = '%7B%22label%22%3A%5B%22com.docker.compose.project%3D' + project + '%22%5D%7D'
    containers = docker_get("/containers/json?all=true&filters=" + filters)
    selected = []
    seen = set()
    for container in containers:
        labels = container["Labels"]
        service = labels.get("com.docker.compose.service")
        if service not in SERVICES or labels.get("com.docker.compose.oneoff", "false").lower() == "true":
            continue
        if service in seen or not re.fullmatch(r"[a-f0-9]{64}", container["Id"]):
            raise RuntimeError("Expected one container per service")
        seen.add(service)
        selected.append((service, container["Id"], container["State"]))
    if seen != SERVICES:
        raise RuntimeError("Required Compose service missing")

    def read(item):
        service, identifier, state = item
        if state != "running":
            return [f'beanflow_container_running{{service="{service}"}} 0']
        detail = docker_get(f"/containers/{identifier}/json")
        stats = docker_get(f"/containers/{identifier}/stats?stream=false")
        return samples(service, stats, detail)

    with concurrent.futures.ThreadPoolExecutor(max_workers=7) as pool:
        return [line for batch in pool.map(read, selected) for line in batch]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", required=True)
    parser.add_argument("--allow", action="append", required=True)
    parser.add_argument("--port", type=int, default=19101)
    parser.add_argument("--project", default="beanflow-perf")
    args = parser.parse_args()
    address = ipaddress.ip_address(args.bind)
    if not address.is_private or address.is_unspecified:
        parser.error("An explicit private bind address is required")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", args.project):
        parser.error("Invalid Compose project")
    allowed = {str(ipaddress.ip_address(value)) for value in args.allow}
    lock = threading.Lock()
    state = {"text": "beanflow_container_collection_success 0\n", "last_success": 0}

    def poll():
        while True:
            started = time.monotonic()
            try:
                lines = collect(args.project)
                state["last_success"] = time.time()
                lines.append("beanflow_container_collection_success 1")
            except Exception as error:
                # No stale container samples, config, raw labels or daemon error body.
                lines = ["beanflow_container_collection_success 0"]
                print("container collection failed: " + type(error).__name__, flush=True)
            lines.append(f'beanflow_container_last_success_timestamp_seconds {state["last_success"]}')
            with lock:
                state["text"] = "\n".join(lines) + "\n"
            time.sleep(max(1, 15 - (time.monotonic() - started)))

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.client_address[0] not in allowed:
                self.send_error(403)
                return
            if self.path != "/metrics":
                self.send_error(404)
                return
            with lock:
                body = state["text"].encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    threading.Thread(target=poll, daemon=True).start()
    ThreadingHTTPServer((args.bind, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
