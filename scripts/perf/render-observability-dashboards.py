#!/usr/bin/env python3
"""Apply the bounded O1-O6 readiness layout to provisioned Grafana dashboards."""

import copy
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DASHBOARDS = ROOT / "infra/observability/central/grafana/dashboards"
PROMETHEUS = {"type": "prometheus", "uid": "beanflow-prometheus"}
EXPLORE_URL = (
    "/explore?schemaVersion=1&panes=%7B%22beanflow%22:%7B%22datasource%22:%22beanflow-prometheus%22,"
    "%22queries%22:%5B%5D,%22range%22:%7B%22from%22:%22${__from}%22,"
    "%22to%22:%22${__to}%22%7D%7D%7D"
)


def read(name):
    path = DASHBOARDS / name
    return path, json.loads(path.read_text())


def write(path, dashboard):
    path.write_text(json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n")


def target(expression, legend, ref="A"):
    return {"refId": ref, "expr": expression, "legendFormat": legend}


def timeseries(base, identifier, title, x, y, width, height, targets, unit="short", description=""):
    panel = copy.deepcopy(base)
    panel.update(id=identifier, title=title, gridPos={"h": height, "w": width, "x": x, "y": y})
    panel["datasource"] = PROMETHEUS
    panel["targets"] = targets
    panel["fieldConfig"]["defaults"]["unit"] = unit
    panel["fieldConfig"]["defaults"]["noValue"] = "데이터 없음"
    panel["description"] = description
    panel["links"] = [{"title": "같은 절대 시간 범위로 Explore", "url": EXPLORE_URL, "targetBlank": True}]
    return panel


def row(base, identifier, title, y):
    panel = copy.deepcopy(base)
    panel.update(id=identifier, title=title, gridPos={"h": 1, "w": 24, "x": 0, "y": y})
    return panel


def add_scope(expression):
    def scoped(match):
        selector = match.group(0)
        if not re.search(r'job(?:=|=~)"beanflow', selector):
            return selector
        additions = []
        if 'environment="$environment"' not in selector:
            additions.append('environment="$environment"')
        if 'host="$host"' not in selector:
            additions.append('host="$host"')
        if not additions:
            return selector
        return "{" + ",".join(additions) + "," + selector[1:]

    return re.sub(r"\{[^{}]*\}", scoped, expression)


def update_rca():
    path, dashboard = read("beanflow-performance-rca.json")
    dashboard["version"] = 3
    panels = [panel for panel in dashboard["panels"] if panel["id"] < 153]
    by_id = {panel["id"]: panel for panel in panels}
    variables = [item for item in dashboard["templating"]["list"] if item["name"] not in {"environment", "host"}]
    route = next(item for item in variables if item["name"] == "route")
    environment = {
        "name": "environment",
        "label": "환경",
        "type": "query",
        "datasource": PROMETHEUS,
        "query": {"query": 'label_values(up{job="beanflow"}, environment)', "refId": "environment"},
        "regex": "",
        "refresh": 1,
        "sort": 1,
        "includeAll": False,
        "multi": False,
        "allValue": None,
        "current": {"text": "perf", "value": "perf"},
        "options": [],
    }
    host = copy.deepcopy(environment)
    host.update(name="host", label="호스트", current={"text": "", "value": ""})
    host["query"] = {
        "query": 'label_values(up{job="beanflow",environment="$environment"}, host)',
        "refId": "host",
    }
    route["query"] = {
        "query": (
            'label_values(http_server_requests_seconds_count{job="beanflow",environment="$environment",'
            'host="$host"}, uri)'
        ),
        "refId": "Prometheus-route-Variable-Query",
    }
    database = next(item for item in variables if item["name"] == "database")
    database["query"] = {
        "query": (
            'label_values(beanflow_pg_database_identity_info{job="beanflow-postgres",'
            'environment="$environment",host="$host"}, database)'
        ),
        "refId": "database",
    }
    database["current"] = {"text": "", "value": ""}
    dashboard["templating"]["list"] = [environment, host, *variables]

    for panel in panels:
        if panel.get("datasource", {}).get("type") != "prometheus":
            continue
        for item in panel.get("targets", []):
            if "expr" in item:
                item["expr"] = add_scope(item["expr"])

    health = (
        '((up{job="beanflow-postgres"} == 1) and on(job,instance,environment,host) '
        '(pg_up{job="beanflow-postgres"} == 1) and on(job,instance,environment,host) '
        '(pg_exporter_last_scrape_error{job="beanflow-postgres"} == 0))'
    )
    by_id[5]["targets"][0]["expr"] = add_scope(
        '(sum by (database,wait_event_type,wait_event) '
        '(beanflow_pg_wait_sessions{job="beanflow-postgres",database="$database"}) and on() max' + health + ") "
        'or on() (0 * max' + health + ")",
    )
    by_id[6]["targets"][0]["expr"] = add_scope(
        'beanflow_pg_ungranted_locks{job="beanflow-postgres",database="$database"} and on() max' + health,
    )
    by_id[130]["targets"][0]["expr"] = add_scope(
        'topk(10, rate(pg_stat_statements_seconds_total{job="beanflow-postgres",datname="$database"}'
        '[$__rate_interval]))',
    )
    by_id[131]["targets"][0]["expr"] = add_scope(
        'topk(10, rate(pg_stat_statements_seconds_total{job="beanflow-postgres",datname="$database"}'
        '[$__rate_interval]) / (rate(pg_stat_statements_calls_total{job="beanflow-postgres",datname="$database"}'
        '[$__rate_interval]) > 0))',
    )
    for identifier in (5, 6, 126, 127, 128, 129, 130, 131, 132):
        by_id[identifier]["links"] = [
            {"title": "같은 절대 시간 범위로 Prometheus Explore", "url": EXPLORE_URL, "targetBlank": True},
        ]

    by_id[12]["targets"][0]["query"] = (
        '{ resource.service.name = "beanflow" && resource.deployment.environment.name = "$environment" '
        '&& resource.host.name = "$host" && duration > 1s }'
    )
    by_id[13]["targets"][0]["expr"] = (
        '{service_name="beanflow",deployment_environment_name="$environment"} | host_name = "$host"'
    )
    by_id[14]["targets"][0]["labelSelector"] = (
        '{service_name="beanflow",deployment_environment_name="$environment",host_name="$host"}'
    )
    by_id[150]["targets"][0]["query"] = (
        '{ resource.service.name = "beanflow" && resource.deployment.environment.name = "$environment" '
        '&& resource.host.name = "$host" && span.beanflow.test.id = "${test_id:raw}" && duration > 200ms }'
    )

    base = by_id[145]
    row_base = by_id[116]
    new_panels = [row(row_base, 153, "PostgreSQL blocker · bounded snapshot readiness", 209)]
    new_panels.extend(
        [
            timeseries(base, 154, "DB blocked sessions", 0, 210, 6, 7, [target(add_scope(
                'beanflow_pg_blocked_sessions{job="beanflow-postgres",database="$database"} and on() max' + health),
                "blocked")], description="pg_blocking_pids()가 확인한 대상 DB의 blocked session 수다."),
            timeseries(base, 155, "DB max lock wait", 6, 210, 6, 7, [target(add_scope(
                'beanflow_pg_lock_wait_max_seconds{job="beanflow-postgres",database="$database"} and on() max' + health),
                "lock wait")], unit="s", description="현재 lock wait의 최대 경과 시간이다."),
            timeseries(base, 156, "DB max transaction age", 12, 210, 6, 7, [target(add_scope(
                'beanflow_pg_transaction_max_age_seconds{job="beanflow-postgres",database="$database"} and on() max' + health),
                "transaction age")], unit="s"),
            timeseries(base, 157, "DB idle in transaction", 18, 210, 6, 7, [target(add_scope(
                'beanflow_pg_transaction_idle_in_transaction_sessions{job="beanflow-postgres",database="$database"} '
                'and on() max' + health), "idle in tx")]),
            timeseries(base, 158, "DB snapshot collection", 0, 217, 8, 7, [
                target(add_scope('beanflow_db_diagnostics_collection_success{job="beanflow-db-diagnostics"}'), "DB read"),
                target(add_scope('beanflow_db_diagnostics_log_export_success{job="beanflow-db-diagnostics"}'), "OTLP log", "B"),
            ], description="조회 실패와 OTLP 전송 실패를 빈 잠금으로 바꾸지 않는다."),
            timeseries(base, 159, "DB snapshot age", 8, 217, 8, 7, [target(add_scope(
                'time() - beanflow_db_diagnostics_last_success_timestamp_seconds{job="beanflow-db-diagnostics"}'),
                "last success age")], unit="s"),
            timeseries(base, 160, "DB snapshot truncation", 16, 217, 8, 7, [target(add_scope(
                'beanflow_db_diagnostics_snapshot_truncated{job="beanflow-db-diagnostics"}'), "truncated")],
                description="1이면 10개 waiter 한도를 넘어 snapshot이 잘렸다."),
            row(row_base, 161, "Worker owner · backlog · freshness", 224),
            timeseries(base, 162, "Worker backlog by owner/state", 0, 225, 12, 7, [target(add_scope(
                'beanflow_worker_backlog_items{job="beanflow-postgres",database="$database"}'),
                "{{owner}} · {{state}} · {{claimability}}")],
                description="전체 DB count이며 business state와 실제 claim 가능 여부를 함께 보존한다."),
            timeseries(base, 163, "Oldest due work", 12, 225, 12, 7, [target(add_scope(
                'beanflow_worker_backlog_oldest_due_age_seconds{job="beanflow-postgres",database="$database",claimability="due"} '
                'and on(environment,host,instance,owner,state,claimability,database) '
                '(beanflow_worker_backlog_items{job="beanflow-postgres",database="$database",claimability="due"} > 0)'),
                "{{owner}} · {{state}}")], unit="s"),
            timeseries(base, 164, "Worker completed / failed throughput", 0, 232, 12, 7, [target(add_scope(
                'sum by (owner,outcome) (rate(beanflow_worker_items_total{job="beanflow",outcome=~"completed|failed"}'
                '[$__rate_interval]))'), "{{owner}} · {{outcome}}"),
                target(add_scope('sum by (owner,outcome) (rate(beanflow_worker_runs_total{job="beanflow"}'
                                 '[$__rate_interval]))'), "{{owner}} run · {{outcome}}", "B")], unit="ops"),
            timeseries(base, 165, "Worker data freshness / stale threshold", 12, 232, 12, 7, [
                target(add_scope('(time() - beanflow_worker_data_last_success_timestamp_seconds{job="beanflow"}) '
                                 'and on(environment,host,instance,owner) '
                                 '(beanflow_worker_data_last_success_timestamp_seconds{job="beanflow"} > 0)'),
                       "{{owner}} data age"),
                target(add_scope('(time() - beanflow_worker_business_last_success_timestamp_seconds{job="beanflow"}) '
                                 'and on(environment,host,instance,owner) '
                                 '(beanflow_worker_business_last_success_timestamp_seconds{job="beanflow"} > 0)'),
                       "{{owner}} business age", "B"),
                target(add_scope('(time() - beanflow_worker_last_started_timestamp_seconds{job="beanflow"}) '
                                 'and on(environment,host,instance,owner) '
                                 '(beanflow_worker_last_started_timestamp_seconds{job="beanflow"} > 0)'),
                       "{{owner}} last started age", "C"),
                target(add_scope('2 * beanflow_worker_refresh_interval_seconds{job="beanflow"} + 10'),
                       "{{owner}} stale threshold", "D"),
            ], unit="s", description="data age가 2×worker refresh + 10초 scrape를 넘으면 stale이다."),
            row(row_base, 166, "Request phases · executors · Alloy delivery", 239),
            timeseries(base, 167, "Internal phase p95", 0, 240, 8, 8, [target(add_scope(
                'histogram_quantile(0.95, sum by (le,operation,stage) '
                '(rate(beanflow_operation_phase_duration_seconds_bucket{job="beanflow"}[$__rate_interval])))'),
                "{{operation}} · {{stage}}")], unit="s"),
            timeseries(base, 168, "Application task executor (HTTP와 별도)", 8, 240, 8, 8, [
                target(add_scope('executor_active_threads{job="beanflow"}'), "{{name}} active"),
                target(add_scope('executor_pool_max_threads{job="beanflow"}'), "{{name}} max", "B"),
                target(add_scope('executor_queued_tasks{job="beanflow"}'), "{{name}} queued", "C"),
            ]),
            timeseries(base, 169, "Tomcat HTTP threads", 16, 240, 8, 8, [
                target(add_scope('tomcat_threads_busy_threads{job="beanflow"}'), "{{name}} busy"),
                target(add_scope('tomcat_threads_config_max_threads{job="beanflow"}'), "{{name}} max", "B"),
                target(add_scope('tomcat_threads_current_threads{job="beanflow"}'), "{{name}} current", "C"),
            ], description="Spring Boot WebMVC의 embedded Tomcat thread metric이다. applicationTaskExecutor와 구분한다."),
            timeseries(base, 170, "Alloy exporter queue usage", 0, 248, 12, 8, [target(add_scope(
                'sum by (exporter) (otelcol_exporter_queue_size{job="beanflow-alloy"}) '
                '/ (sum by (exporter) (otelcol_exporter_queue_capacity{job="beanflow-alloy"}) > 0)'),
                "{{exporter}}")], unit="percentunit"),
            timeseries(base, 171, "Alloy failed / refused telemetry", 12, 248, 12, 8, [
                target(add_scope('sum by (exporter) (increase(otelcol_exporter_send_failed_spans_total'
                                 '{job="beanflow-alloy"}[5m]))'), "{{exporter}} failed spans"),
                target(add_scope('sum by (exporter) (increase(otelcol_exporter_send_failed_log_records_total'
                                 '{job="beanflow-alloy"}[5m]))'), "{{exporter}} failed logs", "B"),
                target(add_scope('sum(increase(otelcol_receiver_refused_spans_total{job="beanflow-alloy"}[5m]))'),
                       "receiver refused spans", "C"),
                target(add_scope('sum(increase(otelcol_receiver_refused_log_records_total{job="beanflow-alloy"}[5m]))'),
                       "receiver refused logs", "D"),
            ]),
            row(row_base, 172, "App host/container guardrails (load generator · WAF 제외)", 256),
            timeseries(base, 173, "Container CPU throttled periods", 0, 257, 8, 8, [target(add_scope(
                'sum by (service) (rate(beanflow_container_cpu_throttled_periods_total{job="beanflow-containers"}'
                '[$__rate_interval])) / (sum by (service) (rate(beanflow_container_cpu_periods_total'
                '{job="beanflow-containers"}[$__rate_interval])) > 0)'), "{{service}}")], unit="percentunit"),
            timeseries(base, 174, "Container OOM state / support", 8, 257, 8, 8, [
                target(add_scope('beanflow_container_oom_killed{job="beanflow-containers"}'), "{{service}} OOM"),
                target(add_scope('beanflow_container_signal_supported{job="beanflow-containers",signal="oom_state"}'),
                       "{{service}} supported", "B"),
            ], description="지원되지 않으면 OOM sample을 만들지 않고 support=0만 표시한다."),
            timeseries(base, 175, "App host filesystem available", 16, 257, 8, 8, [
                target(add_scope('beanflow_host_filesystem_available_bytes{job="beanflow-containers"} '
                                 '/ (beanflow_host_filesystem_size_bytes{job="beanflow-containers"} > 0)'),
                       "available ratio"),
                target(add_scope('beanflow_host_filesystem_collection_success{job="beanflow-containers"}'),
                       "collection success", "B"),
            ], unit="percentunit"),
        ]
    )
    log_panel = copy.deepcopy(by_id[13])
    log_panel.update(
        id=176,
        title="Bounded DB blocking snapshots",
        gridPos={"h": 9, "w": 24, "x": 0, "y": 265},
        description=(
            "PID/query ID는 structured metadata이며 index label이 아니다. raw SQL, literal, 고객·주문 식별자는 수집하지 않는다."
        ),
    )
    log_panel["targets"] = [{
        "refId": "A",
        "expr": (
            '{service_name="beanflow-db-diagnostics",deployment_environment_name="$environment"} '
            '| host_name = "$host"'
        ),
    }]
    new_panels.append(log_panel)
    new_panels.append(
        timeseries(
            base,
            177,
            "Worker enqueue→claim / claim→outcome p95",
            0,
            274,
            24,
            8,
            [
                target(
                    add_scope(
                        'histogram_quantile(0.95, sum by (le,owner) '
                        '(rate(beanflow_worker_enqueue_to_claim_duration_seconds_bucket'
                        '{job="beanflow"}[$__rate_interval])))',
                    ),
                    "{{owner}} enqueue→claim",
                ),
                target(
                    add_scope(
                        'histogram_quantile(0.95, sum by (le,owner,outcome) '
                        '(rate(beanflow_worker_claim_to_outcome_duration_seconds_bucket'
                        '{job="beanflow"}[$__rate_interval])))',
                    ),
                    "{{owner}} claim→{{outcome}}",
                    "B",
                ),
            ],
            unit="s",
            description="due/enqueue 대기와 실제 claim 이후 terminal outcome까지 시간을 합치지 않는다.",
        ),
    )
    dashboard["panels"] = panels + new_panels
    write(path, dashboard)


def update_live():
    path, dashboard = read("beanflow-live-operations.json")
    dashboard["version"] = 2
    panels = [panel for panel in dashboard["panels"] if panel["id"] < 33]
    by_id = {panel["id"]: panel for panel in panels}
    variables = dashboard["templating"]["list"]
    host = next(item for item in variables if item["name"] == "host")
    host["query"] = {
        "query": 'label_values(up{job="beanflow",environment="$environment"}, host)',
        "refId": "host",
    }
    host["regex"] = ""
    route = next(item for item in variables if item["name"] == "route")
    route["query"] = {
        "query": (
            'label_values(http_server_requests_seconds_count{job="beanflow",environment="$environment",'
            'host="$host",uri!~"/actuator.*"}, uri)'
        ),
        "refId": "route",
    }
    database = next((item for item in variables if item["name"] == "database"), copy.deepcopy(host))
    database.update(name="database", label="DB", current={"text": "", "value": ""})
    database["query"] = {
        "query": (
            'label_values(beanflow_pg_database_identity_info{job="beanflow-postgres",'
            'environment="$environment",host="$host"}, database)'
        ),
        "refId": "database",
    }
    if not any(item["name"] == "database" for item in variables):
        variables.append(database)
    for panel in panels:
        if panel.get("datasource", {}).get("type") != "prometheus":
            continue
        for item in panel.get("targets", []):
            expression = re.sub(r'instance="\$host:[0-9]+",?', "", item.get("expr", ""))
            expression = expression.replace(",}", "}")
            item["expr"] = add_scope(expression)
            if "beanflow_pg_wait_sessions" in item["expr"] or "beanflow_pg_ungranted_locks" in item["expr"]:
                item["expr"] = item["expr"].replace('job="beanflow-postgres"}',
                                                        'job="beanflow-postgres",database="$database"}')

    base = by_id[26]
    row_base = by_id[8]
    dashboard["panels"] = panels + [
        row(row_base, 33, "Blocker · worker · telemetry freshness", 58),
        timeseries(base, 34, "DB blocker / lock wait", 0, 59, 8, 7, [
            target(add_scope('beanflow_pg_blocked_sessions{job="beanflow-postgres",database="$database"}'), "blocked"),
            target(add_scope('beanflow_pg_lock_wait_max_seconds{job="beanflow-postgres",database="$database"}'), "max wait", "B"),
        ]),
        timeseries(base, 35, "DB diagnostic snapshot health", 8, 59, 8, 7, [
            target(add_scope('beanflow_db_diagnostics_collection_success{job="beanflow-db-diagnostics"}'), "DB read"),
            target(add_scope('time() - beanflow_db_diagnostics_last_success_timestamp_seconds'
                             '{job="beanflow-db-diagnostics"}'), "age", "B"),
        ]),
        timeseries(base, 36, "Worker data age / stale threshold", 16, 59, 8, 7, [
            target(add_scope('(time() - beanflow_worker_data_last_success_timestamp_seconds{job="beanflow"}) '
                             'and on(environment,host,instance,owner) '
                             '(beanflow_worker_data_last_success_timestamp_seconds{job="beanflow"} > 0)'),
                   "{{owner}} age"),
            target(add_scope('(time() - beanflow_worker_business_last_success_timestamp_seconds{job="beanflow"}) '
                             'and on(environment,host,instance,owner) '
                             '(beanflow_worker_business_last_success_timestamp_seconds{job="beanflow"} > 0)'),
                   "{{owner}} business age", "B"),
            target(add_scope('2 * beanflow_worker_refresh_interval_seconds{job="beanflow"} + 10'),
                   "{{owner}} threshold", "C"),
        ], unit="s"),
        row(row_base, 37, "App server saturation guardrails", 66),
        timeseries(base, 38, "Alloy queue / failed delivery", 0, 67, 8, 7, [
            target(add_scope('sum by (exporter) (otelcol_exporter_queue_size{job="beanflow-alloy"}) '
                             '/ (sum by (exporter) (otelcol_exporter_queue_capacity{job="beanflow-alloy"}) > 0)'),
                   "{{exporter}} queue"),
            target(add_scope('sum by (exporter) (increase(otelcol_exporter_send_failed_spans_total'
                             '{job="beanflow-alloy"}[5m]))'), "{{exporter}} span fail", "B"),
        ]),
        timeseries(base, 39, "Container throttling / OOM", 8, 67, 8, 7, [
            target(add_scope('sum by (service) (rate(beanflow_container_cpu_throttled_periods_total'
                             '{job="beanflow-containers"}[$__rate_interval])) '
                             '/ (sum by (service) (rate(beanflow_container_cpu_periods_total'
                             '{job="beanflow-containers"}[$__rate_interval])) > 0)'), "{{service}} throttle"),
            target(add_scope('beanflow_container_oom_killed{job="beanflow-containers"}'), "{{service}} OOM", "B"),
        ]),
        timeseries(base, 40, "App host filesystem", 16, 67, 8, 7, [
            target(add_scope('beanflow_host_filesystem_available_bytes{job="beanflow-containers"} '
                             '/ (beanflow_host_filesystem_size_bytes{job="beanflow-containers"} > 0)'),
                   "available ratio"),
            target(add_scope('beanflow_host_filesystem_collection_success{job="beanflow-containers"}'),
                   "collection success", "B"),
        ], unit="percentunit"),
    ]
    write(path, dashboard)


if __name__ == "__main__":
    update_rca()
    update_live()
