#!/usr/bin/env python3
"""Evaluate the actual dashboard queries against failure and isolation fixtures."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
DASHBOARD = ROOT / "infra/observability/central/grafana/dashboards/beanflow-live-operations.json"
dashboard = json.loads(DASHBOARD.read_text())
panels = dashboard["panels"]
assert dashboard["uid"] == "beanflow-live-operations"
assert dashboard["time"] == {"from": "now-15m", "to": "now"}
assert dashboard["refresh"] == "10s"
assert len({p["id"] for p in panels}) == len(panels)
assert {v["name"] for v in dashboard["templating"]["list"]} == {"environment", "host", "route"}
assert "k6_" not in DASHBOARD.read_text() and "test_id" not in DASHBOARD.read_text()
assert "${host:regex}" not in DASHBOARD.read_text()
assert "${route:regex}" not in DASHBOARD.read_text()
for p in panels:
    if "targets" not in p:
        continue
    assert p["datasource"] == {"type": "prometheus", "uid": "beanflow-prometheus"}
    for t in p["targets"]:
        assert 'environment="$environment"' in t["expr"]
        assert 'instance="$host:' in t["expr"]
        assert "vector(0)" not in t["expr"] and "last_over_time" not in t["expr"]
        if p["type"] == "stat":
            assert t["instant"] and not t["range"]
            assert p["options"]["reduceOptions"]["calcs"] == ["last"]
        else:
            assert not p["fieldConfig"]["defaults"]["custom"]["spanNulls"]


def render(expr):
    return (expr.replace("$environment", "perf").replace("$host", "host-a")
            .replace("$route", ".*").replace("$__rate_interval", "1m"))


def query(title, index=0):
    return render(next(p for p in panels if p["title"] == title)["targets"][index]["expr"])


def series(metric, value, job="beanflow", host="host-a", environment="perf", extra=""):
    suffix = "," + extra if extra else ""
    port = {"beanflow": 18081, "beanflow-postgres": 19187, "beanflow-containers": 19101}[job]
    return {"series": f'{metric}{{job="{job}",instance="{host}:{port}",environment="{environment}"{suffix}}}',
            "values": str(value)}


def samples(value, labels="{}"):
    return [] if value is None else [{"labels": labels, "value": value}]


tests = []


def case(name, title, inputs, expected, at="0s", index=0):
    tests.append({"name": name, "interval": "10s", "input_series": inputs,
                  "promql_expr_test": [{"expr": query(title, index), "eval_time": at,
                                        "exp_samples": expected}]})


app_labels = '{instance="host-a:18081",job="beanflow"}'
case("API scrape success", "API 수집", [series("up", 1)], samples(1, app_labels))
case("API scrape failure", "API 수집", [series("up", 0)], samples(0, app_labels))
case("API target missing", "API 수집", [], [])
case("frozen API sample is not healthy", "API 수집", [series("up", 1)], samples(0, app_labels), "40s")
case("another host cannot satisfy API health", "API 수집", [series("up", 1, host="host-b")], [])
case("another environment cannot satisfy API health", "API 수집", [series("up", 1, environment="prod")], [])

traffic = [series("up", "1+0x6"),
           series("http_server_requests_seconds_count", "0+9x6", extra='uri="/api/v1/orders",status="200"'),
           series("http_server_requests_seconds_count", "0+1x6", extra='uri="/api/v1/orders",status="500"')]
case("request weighted error ratio", "서버 오류 · 5xx", traffic, samples(.1), "1m")
case("HTTP request rate", "현재 요청 / 초", traffic, samples(1), "1m")
case("no error series with requests is zero", "서버 오류 · 5xx", traffic[:2], samples(0), "1m")
idle = [series("up", "1+0x6"),
        series("http_server_requests_seconds_count", "0+0x6", extra='uri="/api/v1/orders",status="200"')]
case("known idle counter has zero request rate", "현재 요청 / 초", idle, samples(0), "1m")
case("idle ratio is undefined rather than healthy zero", "서버 오류 · 5xx", idle, [], "1m")
case("no first request metric is unavailable", "현재 요청 / 초", idle[:1], [], "1m")
case("failed scrape hides historical request rates", "현재 요청 / 초",
     [series("up", "0+0x6")] + traffic[1:], [], "1m")
case("failed scrape hides historical error ratio", "서버 오류 · 5xx",
     [series("up", "0+0x6")] + traffic[1:], [], "1m")
case("frozen scrape hides historical request rates", "현재 요청 / 초",
     [series("up", "1")] + traffic[1:], [], "1m")
case("other host and environment excluded from RPS", "현재 요청 / 초", traffic + [
    series("up", "1+0x6", host="host-b"),
    series("http_server_requests_seconds_count", "0+100x6", host="host-b", extra='uri="/api/v1/orders",status="200"'),
    series("up", "1+0x6", environment="prod"),
    series("http_server_requests_seconds_count", "0+100x6", environment="prod", extra='uri="/api/v1/orders",status="200"'),
], samples(1), "1m")

for name, up, pg, error, waiting, value in [
    ("healthy idle DB", 1, 1, 0, None, 0),
    ("DB exporter down", 0, 1, 0, None, None),
    ("DB disconnected", 1, 0, 0, None, None),
    ("DB query error", 1, 1, 1, None, None),
    ("DB target missing", None, 1, 0, None, None),
    ("DB connection metric missing", 1, None, 0, None, None),
    ("DB error metric missing", 1, 1, None, None, None),
    ("DB lock waiter", 1, 1, 0, 3, 3),
    ("old waiter after query failure", 1, 1, 1, 3, None),
]:
    inputs = [series(m, v, "beanflow-postgres") for m, v in [
        ("up", up), ("pg_up", pg), ("pg_exporter_last_scrape_error", error)] if v is not None]
    if waiting is not None:
        inputs.append(series("beanflow_pg_wait_sessions", waiting, "beanflow-postgres",
                             extra='wait_event="transactionid",wait_event_type="Lock"'))
    labels = '{wait_event="transactionid",wait_event_type="Lock"}' if waiting is not None else "{}"
    case(name, "PostgreSQL 대기 세션", inputs, samples(value, labels))

db_health = [series("up", 1, "beanflow-postgres"), series("pg_up", 1, "beanflow-postgres"),
             series("pg_exporter_last_scrape_error", 0, "beanflow-postgres")]
case("frozen DB health is unavailable", "PostgreSQL 대기 세션", db_health, [], "40s")
case("wrong host DB waiter cannot leak into selected DB", "PostgreSQL 대기 세션", db_health + [
    series("beanflow_pg_wait_sessions", 3, "beanflow-postgres", host="host-b",
           extra='wait_event="transactionid",wait_event_type="Lock"')], samples(0))

container_labels = '{__name__="beanflow_container_running",environment="perf",instance="host-a:19101",job="beanflow-containers",service="api"}'
for name, up, success, timestamp, running, at, expected in [
    ("running container", 1, 1, 0, 1, "0s", 1),
    ("stopped container stays zero", 1, 1, 0, 0, "0s", 0),
    ("container collection failure hides old sample", 1, 0, 0, 1, "0s", None),
    ("container scrape failure hides old sample", 0, 1, 0, 1, "0s", None),
    ("container timestamp missing hides sample", 1, 1, None, 1, "0s", None),
    ("stale collection with live exporter hides sample", "1+0x6", "1+0x6", 0, "1+0x6", "1m", None),
    ("frozen container exporter hides sample", 1, 1, 0, 1, "1m", None),
]:
    inputs = [series(m, v, "beanflow-containers") for m, v in [
        ("up", up), ("beanflow_container_collection_success", success),
        ("beanflow_container_last_success_timestamp_seconds", timestamp)] if v is not None]
    inputs.append(series("beanflow_container_running", running, "beanflow-containers", extra='service="api"'))
    case(name, "컨테이너 실행 상태", inputs, samples(expected, container_labels), at)
case("optional container collector absent", "컨테이너 실행 상태", [], [])

with tempfile.TemporaryDirectory(prefix="beanflow-live-queries-") as directory:
    path = Path(directory)
    rules = [{"record": f"beanflow_live_query_{p['id']}_{t['refId'].lower()}", "expr": render(t["expr"])}
             for p in panels for t in p.get("targets", [])]
    (path / "rules.yml").write_text(json.dumps({"groups": [{"name": "dashboard-query-syntax", "rules": rules}]}))
    (path / "tests.yml").write_text(json.dumps({"rule_files": ["rules.yml"], "evaluation_interval": "10s", "tests": tests}))
    subprocess.run(["docker", "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}",
                    "--entrypoint", "/bin/promtool", "-v", directory + ":/tests:ro", "-w", "/tests",
                    "prom/prometheus:v3.14.0", "test", "rules", "/tests/tests.yml"], check=True)
print(f"PASS: {len(rules)} actual dashboard queries and {len(tests)} availability/isolation cases")
