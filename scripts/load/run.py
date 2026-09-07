#!/usr/bin/env python3
"""Run k6 with private artifacts and comparable, explicitly identified deployment conditions."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import resource
import subprocess
import time
import urllib.parse
import urllib.request

SCRIPT = Path(__file__).with_name("beanflow-load.js")
CONDITIONS = {
    "BEANFLOW_LOAD_SCENARIO": "quote-order", "BEANFLOW_RATE": "1", "BEANFLOW_DURATION": "1m",
    "BEANFLOW_PREALLOCATED_VUS": "4", "BEANFLOW_MAX_VUS": "20", "BEANFLOW_BOARD_VUS": "4",
    "BEANFLOW_BOARD_POLL_SECONDS": "3", "BEANFLOW_P95_MS": "1000",
}


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    path.chmod(0o600)


def metric(summary, name, field):
    return summary.get("metrics", {}).get(name, {}).get("values", {}).get(field)


def compare(current, baseline):
    if not current.get("target_runtime") or not baseline.get("target_runtime"):
        return {"comparable": False, "different_conditions": ["target_runtime_not_captured"]}
    keys = ["conditions", "dataset_id", "base_url", "script_sha256", "generator", "k6_version", "target_runtime"]
    differences = [key for key in keys if current.get(key) != baseline.get(key)]
    if differences:
        return {"comparable": False, "different_conditions": differences}
    return {"comparable": True, "baseline_test_id": baseline["test_id"],
            "baseline": baseline.get("result"), "current": current.get("result"),
            "note": "Compare identical conditions and generator telemetry; this is not a capacity claim."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--local-only", action="store_true", help="Do not send metrics; artifacts still record that telemetry was not sent")
    args = parser.parse_args()
    os.umask(0o077)
    env = os.environ.copy()
    for name in ["BEANFLOW_BASE_URL", "BEANFLOW_TEST_ID", "BEANFLOW_LOAD_FIXTURE", "BEANFLOW_DATASET_ID"]:
        if not env.get(name):
            parser.error(name + " is required")
    test_id = env["BEANFLOW_TEST_ID"]
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", test_id):
        parser.error("Invalid BEANFLOW_TEST_ID")
    origin = urllib.parse.urlsplit(env["BEANFLOW_BASE_URL"])
    if origin.scheme not in ["http", "https"] or not origin.hostname or origin.username or origin.password or origin.query or origin.fragment or origin.path not in ["", "/"]:
        parser.error("BEANFLOW_BASE_URL must be an HTTP(S) origin without credentials or query")
    snapshot = None
    if env.get("BEANFLOW_TARGET_CONFIG_FILE"):
        snapshot = json.loads(Path(env["BEANFLOW_TARGET_CONFIG_FILE"]).read_text())
        if not 0 <= int(time.time() * 1000) - snapshot["captured_ms"] <= 600000:
            parser.error("Capture the target configuration again; it must be less than ten minutes old")
        if env.get("BEANFLOW_DEPLOYMENT_ID", snapshot["deployment_id"]) != snapshot["deployment_id"]:
            parser.error("Deployment ID differs from the target snapshot")
        env["BEANFLOW_DEPLOYMENT_ID"] = snapshot["deployment_id"]
    elif not args.local_only:
        parser.error("BEANFLOW_TARGET_CONFIG_FILE is required for a measured run")
    if not env.get("BEANFLOW_DEPLOYMENT_ID"):
        parser.error("BEANFLOW_DEPLOYMENT_ID is required for local-only fixture validation")
    for name in ["BEANFLOW_DEPLOYMENT_ID", "BEANFLOW_DATASET_ID"]:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9:._/@+-]{0,159}", env[name]):
            parser.error(name + " must be a bounded non-secret identifier")
    if not args.local_only and not env.get("K6_PROMETHEUS_RW_SERVER_URL"):
        parser.error("K6_PROMETHEUS_RW_SERVER_URL is required unless --local-only is explicit")
    output = args.output_dir.expanduser().resolve() / test_id
    repository = SCRIPT.resolve().parents[2]
    if output.is_relative_to(repository):
        parser.error("Store load artifacts outside the repository")
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    fixture = Path(env["BEANFLOW_LOAD_FIXTURE"]).resolve()
    conditions = {key: env.get(key, value) for key, value in CONDITIONS.items()}
    env.update(conditions)
    env["BEANFLOW_SUMMARY_PATH"] = str(output / "summary.json")
    env["K6_PROMETHEUS_RW_TREND_STATS"] = "p(95),p(99),min,max"
    env["K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM"] = "false"
    env["K6_PROMETHEUS_RW_STALE_MARKERS"] = "true"
    # Grafana credentials belong to the wrapper and never enter a VU.
    grafana_token = env.pop("BEANFLOW_GRAFANA_TOKEN", None)
    grafana_url = env.pop("BEANFLOW_GRAFANA_URL", "").rstrip("/")
    monitoring_ssh = env.pop("BEANFLOW_MONITORING_SSH", "")
    monitoring_key = env.pop("BEANFLOW_MONITORING_SSH_KEY", str(Path.home() / ".ssh/id_ed25519"))
    if monitoring_ssh and not re.fullmatch(r"[a-z_][a-z0-9_-]*@[A-Za-z0-9.-]+", monitoring_ssh):
        parser.error("Invalid BEANFLOW_MONITORING_SSH target")
    version = subprocess.check_output(["k6", "version"], text=True).strip()
    start = int(time.time() * 1000)
    manifest = {
        "test_id": test_id, "start_ms": start, "end_ms": None, "status": "RUNNING",
        "deployment_id": env["BEANFLOW_DEPLOYMENT_ID"], "dataset_id": env["BEANFLOW_DATASET_ID"],
        "base_url": env["BEANFLOW_BASE_URL"], "conditions": conditions,
        "script_sha256": hashlib.sha256(SCRIPT.read_bytes()).hexdigest(),
        "fixture_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
        "generator": {"system": platform.system(), "architecture": platform.machine(), "cpu_count": os.cpu_count()},
        "k6_version": version, "remote_write": not args.local_only,
        "telemetry_ingest": "NOT_VERIFIED" if not args.local_only else "NOT_SENT",
        "target_runtime": snapshot["runtime"] if snapshot else None,
        "target_snapshot_ms": snapshot["captured_ms"] if snapshot else None,
    }
    write_json(output / "manifest.json", manifest)
    command = ["k6", "run", "--quiet"]
    if not args.local_only:
        command += ["-o", "experimental-prometheus-rw"]
    before = resource.getrusage(resource.RUSAGE_CHILDREN)
    try:
        with (output / "k6.log").open("w") as log:
            code = subprocess.call(command + [str(SCRIPT)], env=env, stdout=log, stderr=subprocess.STDOUT)
    except KeyboardInterrupt:
        code = 130
    end = int(time.time() * 1000)
    after = resource.getrusage(resource.RUSAGE_CHILDREN)
    summary_path = output / "summary.json"
    summary = json.loads(summary_path.read_text()) if summary_path.exists() else {}
    gates = [value["ok"] for m in summary.get("metrics", {}).values() for value in m.get("thresholds", {}).values()]
    manifest.update(end_ms=end, exit_code=code, status="PASSED" if code == 0 and gates and all(gates) else "FAILED")
    manifest["generator_usage"] = {
        "user_cpu_seconds": after.ru_utime - before.ru_utime, "system_cpu_seconds": after.ru_stime - before.ru_stime,
        "max_rss_bytes": after.ru_maxrss * (1 if platform.system() == "Darwin" else 1024),
        "note": "Process totals only; inspect generator host saturation separately.",
    }
    manifest["result"] = {
        "workflow_started": metric(summary, "beanflow_workflow_started", "count"),
        "workflow_completed": metric(summary, "beanflow_workflow_completed", "count"),
        "workflow_failure_rate": metric(summary, "beanflow_workflow_failures", "rate"),
        "orders_created": metric(summary, "beanflow_orders_created", "count"),
        "payments_approved": metric(summary, "beanflow_payments_approved", "count"),
        "http_p95_ms": metric(summary, "http_req_duration", "p(95)"),
        "http_p99_ms": metric(summary, "http_req_duration", "p(99)"),
        "workflow_p95_ms": metric(summary, "beanflow_workflow_duration", "p(95)"),
        "workflow_p99_ms": metric(summary, "beanflow_workflow_duration", "p(99)"),
        "dropped_iterations": metric(summary, "dropped_iterations", "count"),
        "thresholds_passed": bool(gates) and all(gates),
    }
    if grafana_url:
        query = urllib.parse.urlencode({"from": start, "to": end + 60000, "var-test_id": test_id})
        manifest["grafana_url"] = grafana_url + "/d/beanflow-performance-rca/beanflow-performance-rca?" + query
    annotation = {"time": start, "timeEnd": end, "tags": ["beanflow-load", test_id],
                  "text": f'{test_id}: {manifest["status"]}; scenario={conditions["BEANFLOW_LOAD_SCENARIO"]}; deployment={manifest["deployment_id"]}'}
    write_json(output / "annotation.json", annotation)
    manifest["annotation"] = "NOT_PUBLISHED"
    if grafana_url and grafana_token:
        request = urllib.request.Request(grafana_url + "/api/annotations", data=json.dumps(annotation).encode(),
                                         headers={"Authorization": "Bearer " + grafana_token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                manifest["annotation"] = "PUBLISHED" if response.status == 200 else "FAILED"
        except OSError:
            manifest["annotation"] = "FAILED"
    elif monitoring_ssh:
        try:
            published = subprocess.run(["ssh", "-i", monitoring_key, "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
                                        monitoring_ssh, "python3 beanflow-perf-tools/scripts/load/publish-annotation.py"],
                                       input=json.dumps(annotation), text=True, capture_output=True, timeout=30)
            manifest["annotation"] = "PUBLISHED" if published.returncode == 0 and published.stdout.strip() == "PUBLISHED" else "FAILED"
        except (OSError, subprocess.TimeoutExpired):
            manifest["annotation"] = "FAILED"
    write_json(output / "manifest.json", manifest)
    if args.baseline:
        write_json(output / "comparison.json", compare(manifest, json.loads(args.baseline.read_text())))
    print(f'{manifest["status"]}: {output / "manifest.json"}')
    if manifest.get("grafana_url"):
        print(manifest["grafana_url"])
    print("Annotation: " + manifest["annotation"] + "; telemetry ingest: " + manifest["telemetry_ingest"])
    raise SystemExit(code if code else (0 if manifest["status"] == "PASSED" else 1))


if __name__ == "__main__":
    main()
