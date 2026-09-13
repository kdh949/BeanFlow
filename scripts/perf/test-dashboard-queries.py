#!/usr/bin/env python3
import json
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
dashboard = json.loads((root / "infra/observability/central/grafana/dashboards/beanflow-performance-rca.json").read_text())
panels = dashboard["panels"]
assert len({p["id"] for p in panels}) == len(panels)
assert {v["name"] for v in dashboard["templating"]["list"]} == {
    "environment", "host", "route", "test_id", "baseline_test_id", "database",
}
assert {
    "DB blocked sessions", "DB snapshot collection", "Worker backlog by owner/state",
    "Internal phase p95", "Application task executor (HTTP와 별도)", "Tomcat HTTP threads",
    "Alloy exporter queue usage", "Container CPU throttled periods", "App host filesystem available",
    "Worker enqueue→claim / claim→outcome p95",
} <= {p["title"] for p in panels}
backlog = next(p for p in panels if p["title"] == "Worker backlog by owner/state")["targets"][0]
oldest_due = next(p for p in panels if p["title"] == "Oldest due work")["targets"][0]["expr"]
assert "claimability" in backlog["legendFormat"]
assert 'claimability="due"' in oldest_due
assert "on(environment,host,instance,owner,state,claimability,database)" in oldest_due
total_time = next(p for p in panels if p["id"] == 130)["targets"][0]["expr"]
mean_time = next(p for p in panels if p["id"] == 131)["targets"][0]["expr"]
assert "pg_stat_statements_seconds_total" in total_time and 'datname="$database"' in total_time
assert "pg_stat_statements_calls_total" in mean_time and "> 0" in mean_time and "clamp_min" not in mean_time
assert all("${__from}" in link["url"] and "${__to}" in link["url"]
           for p in panels if p["id"] in {5, 6, 126, 127, 128, 129, 130, 131, 132}
           for link in p["links"])
for panel in panels:
    if panel.get("datasource", {}).get("type") == "prometheus":
        assert panel["datasource"]["uid"] == "beanflow-prometheus"
    if panel["title"] in ["k6 HTTP p95 / p99", "전체 workflow p95 / p99"]:
        assert panel["fieldConfig"]["defaults"]["unit"] == "s"
    for target in panel.get("targets", []):
        if "k6_" in target.get("expr", ""):
            assert 'application="beanflow"' in target["expr"]
        if panel.get("datasource", {}).get("type") == "prometheus" and 'job="beanflow' in target.get("expr", ""):
            assert 'environment="$environment"' in target["expr"]
            assert 'host="$host"' in target["expr"]
    if panel["type"] == "flamegraph":
        assert panel["targets"][0]["profileTypeId"] == "wall:wall:nanoseconds:wall:nanoseconds"

def render(expression):
    return (expression.replace("$environment", "perf").replace("$host", "host-a")
            .replace("$database", "beanflow_perf").replace("$__rate_interval", "2m"))


wait = render(next(p for p in panels if p["title"] == "PostgreSQL waiting sessions (correlated)")["targets"][0]["expr"])
tests = []
for name, up, pg, error, waiting, expected in [
    ("healthy idle DB is zero", 1, 1, 0, None, [{"labels": "{}", "value": 0}]),
    ("exporter down is unavailable", 0, 1, 0, None, []),
    ("DB disconnected is unavailable", 1, 0, 0, None, []),
    ("scrape error is unavailable", 1, 1, 1, None, []),
    ("missing exporter is unavailable", None, 1, 0, None, []),
    ("lock waiter preserves value", 1, 1, 0, 3, [{"labels": '{database="beanflow_perf",wait_event="transactionid",wait_event_type="Lock"}', "value": 3}]),
    ("old waiter with failed scrape is unavailable", 1, 1, 1, 3, []),
]:
    series = [{"series": metric + '{job="beanflow-postgres",instance="db:9187",environment="perf",host="host-a"}', "values": str(value)}
              for metric, value in [("up", up), ("pg_up", pg), ("pg_exporter_last_scrape_error", error)] if value is not None]
    if waiting is not None:
        series.append({"series": 'beanflow_pg_wait_sessions{job="beanflow-postgres",instance="db:9187",environment="perf",host="host-a",database="beanflow_perf",wait_event="transactionid",wait_event_type="Lock"}', "values": str(waiting)})
    tests.append({"name": name, "interval": "1m", "input_series": series,
                  "promql_expr_test": [{"expr": wait, "eval_time": "0m", "exp_samples": expected}]})

pickup_mean = render(next(p for p in panels if p["id"] == 152)["targets"][0]["expr"])
for name, instances, expected in [
    ("pickup without observations is unavailable", [("api:8081", "0+0x2", "0+0x2")], []),
    ("pickup idle after earlier observations is unavailable", [("api:8081", "5+0x2", "10+0x2")], []),
    ("missing pickup metrics are unavailable", [], []),
    ("observed pickup mean preserves duration", [("api:8081", "0+1x2", "0+2x2")],
     [{"labels": '{environment="perf",host="host-a",instance="api:8081",job="beanflow"}', "value": 0.5}]),
    ("observed zero pickup duration remains zero", [("api:8081", "0+0x2", "0+2x2")],
     [{"labels": '{environment="perf",host="host-a",instance="api:8081",job="beanflow"}', "value": 0}]),
    ("idle pickup instance does not hide active instance", [
        ("idle:8081", "5+0x2", "10+0x2"), ("active:8081", "0+1x2", "0+2x2")],
     [{"labels": '{environment="perf",host="host-a",instance="active:8081",job="beanflow"}', "value": 0.5}]),
]:
    series = [
        {"series": f'beanflow_order_pickup_sequence_allocation_duration_seconds_{suffix}'
                   + f'{{job="beanflow",instance="{instance}",environment="perf",host="host-a"}}', "values": values}
        for instance, sums, counts in instances
        for suffix, values in [("sum", sums), ("count", counts)]
    ]
    tests.append({"name": name, "interval": "1m", "input_series": series,
                  "promql_expr_test": [{"expr": pickup_mean, "eval_time": "2m", "exp_samples": expected}]})
with tempfile.TemporaryDirectory(prefix="beanflow-dashboard-queries-") as directory:
    Path(directory, "tests.yml").write_text(json.dumps({"rule_files": [], "evaluation_interval": "1m", "tests": tests}))
    # Linux bind mounts preserve the private fixture directory's owner and 0700 permissions.
    subprocess.run(["docker", "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}", "--entrypoint", "/bin/promtool", "-v", directory + ":/tests:ro",
                    "prom/prometheus:v3.14.0", "test", "rules", "/tests/tests.yml"], check=True)
print("PASS: dashboard units, label isolation, seven DB wait and six pickup mean cases")
