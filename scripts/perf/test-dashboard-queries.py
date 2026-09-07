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
for panel in panels:
    if panel.get("datasource", {}).get("type") == "prometheus":
        assert panel["datasource"]["uid"] == "beanflow-prometheus"
    if panel["title"] in ["k6 HTTP p95 / p99", "전체 workflow p95 / p99"]:
        assert panel["fieldConfig"]["defaults"]["unit"] == "s"
    for target in panel.get("targets", []):
        if "k6_" in target.get("expr", ""):
            assert 'application="beanflow"' in target["expr"]
    if panel["type"] == "flamegraph":
        assert panel["targets"][0]["profileTypeId"] == "wall:wall:nanoseconds:wall:nanoseconds"

wait = next(p for p in panels if p["title"] == "PostgreSQL waiting sessions (correlated)")["targets"][0]["expr"]
tests = []
for name, up, pg, error, waiting, expected in [
    ("healthy idle DB is zero", 1, 1, 0, None, [{"labels": "{}", "value": 0}]),
    ("exporter down is unavailable", 0, 1, 0, None, []),
    ("DB disconnected is unavailable", 1, 0, 0, None, []),
    ("scrape error is unavailable", 1, 1, 1, None, []),
    ("missing exporter is unavailable", None, 1, 0, None, []),
    ("lock waiter preserves value", 1, 1, 0, 3, [{"labels": '{wait_event="transactionid",wait_event_type="Lock"}', "value": 3}]),
    ("old waiter with failed scrape is unavailable", 1, 1, 1, 3, []),
]:
    series = [{"series": metric + '{job="beanflow-postgres",instance="db:9187"}', "values": str(value)}
              for metric, value in [("up", up), ("pg_up", pg), ("pg_exporter_last_scrape_error", error)] if value is not None]
    if waiting is not None:
        series.append({"series": 'beanflow_pg_wait_sessions{job="beanflow-postgres",wait_event="transactionid",wait_event_type="Lock"}', "values": str(waiting)})
    tests.append({"name": name, "interval": "1m", "input_series": series,
                  "promql_expr_test": [{"expr": wait, "eval_time": "0m", "exp_samples": expected}]})
with tempfile.TemporaryDirectory(prefix="beanflow-dashboard-queries-") as directory:
    Path(directory, "tests.yml").write_text(json.dumps({"rule_files": [], "evaluation_interval": "1m", "tests": tests}))
    # Linux bind mounts preserve the private fixture directory's owner and 0700 permissions.
    subprocess.run(["docker", "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}", "--entrypoint", "/bin/promtool", "-v", directory + ":/tests:ro",
                    "prom/prometheus:v3.14.0", "test", "rules", "/tests/tests.yml"], check=True)
print("PASS: dashboard units, label isolation and seven DB wait availability cases")
