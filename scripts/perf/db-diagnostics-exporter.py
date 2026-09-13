#!/usr/bin/env python3
"""Publish bounded PostgreSQL blocking snapshots and collector health.

The exporter selects one allowlisted Compose PostgreSQL service, executes a
read-only one-second query outside application transactions, and sends only
bounded diagnostic fields to Alloy OTLP/HTTP. It never emits SQL text or bind
values.
"""

import argparse
import csv
import io
import ipaddress
import json
import re
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

NAME = re.compile(r"[a-zA-Z0-9_][a-zA-Z0-9_-]{0,62}")
CONTAINER_ID = re.compile(r"[a-f0-9]{12,64}")
QUERY_FAMILIES = {
    "pickup_slot",
    "pickup_counter",
    "order",
    "idempotency",
    "payment",
    "event_publication",
    "notification",
    "unmapped",
}


class LogExportFailure(RuntimeError):
    """The database snapshot succeeded but its bounded OTLP log did not."""

SNAPSHOT_SQL = r"""
WITH waiters AS (
  SELECT
    activity.pid AS waiter_pid,
    activity.state AS waiter_state,
    activity.wait_event_type,
    activity.wait_event,
    activity.query_id,
    activity.xact_start,
    activity.query_start,
    pg_blocking_pids(activity.pid) AS blocker_pids
  FROM pg_stat_activity activity
  WHERE activity.datname = current_database()
    AND activity.pid <> pg_backend_pid()
    AND cardinality(pg_blocking_pids(activity.pid)) > 0
  ORDER BY activity.query_start NULLS LAST, activity.pid
  LIMIT 11
), bounded AS (
  SELECT * FROM waiters ORDER BY query_start NULLS LAST, waiter_pid LIMIT 10
), relations AS (
  SELECT
    lock.pid,
    min(
      CASE
        WHEN namespace.nspname = 'public' AND relation.relname IN (
          'fulfillment_pickup_slot',
          'ordering_store_pickup_counter',
          'ordering_order',
          'ordering_idempotency_record',
          'payment_payment',
          'event_publication',
          'notification_delivery'
        ) THEN relation.relname
        ELSE NULL
      END
    ) AS confirmed_relation
  FROM pg_locks lock
  LEFT JOIN pg_class relation ON relation.oid = lock.relation
  LEFT JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE lock.pid IN (SELECT waiter_pid FROM bounded)
    AND NOT lock.granted
  GROUP BY lock.pid
)
SELECT
  current_database() AS database,
  bounded.waiter_pid,
  bounded.blocker_pids[1] AS blocker_pid,
  blocker.state AS blocker_state,
  bounded.waiter_state,
  COALESCE(bounded.wait_event_type, 'none') AS wait_event_type,
  COALESCE(bounded.wait_event, 'none') AS wait_event,
  bounded.query_id,
  COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - bounded.xact_start)), 0)::bigint AS transaction_age_seconds,
  relations.confirmed_relation,
  CASE relations.confirmed_relation
    WHEN 'fulfillment_pickup_slot' THEN 'pickup_slot'
    WHEN 'ordering_store_pickup_counter' THEN 'pickup_counter'
    WHEN 'ordering_order' THEN 'order'
    WHEN 'ordering_idempotency_record' THEN 'idempotency'
    WHEN 'payment_payment' THEN 'payment'
    WHEN 'event_publication' THEN 'event_publication'
    WHEN 'notification_delivery' THEN 'notification'
    ELSE 'unmapped'
  END AS query_family,
  (SELECT count(*) > 10 FROM waiters) AS truncated
FROM bounded
LEFT JOIN pg_stat_activity blocker ON blocker.pid = bounded.blocker_pids[1]
LEFT JOIN relations ON relations.pid = bounded.waiter_pid
ORDER BY bounded.query_start NULLS LAST, bounded.waiter_pid
""".strip()


def validate_name(value, field):
    if not NAME.fullmatch(value):
        raise ValueError(f"Invalid {field}")
    return value


def find_postgres(project, service="postgres", docker="docker"):
    command = [
        docker,
        "ps",
        "--filter",
        f"label=com.docker.compose.project={project}",
        "--filter",
        f"label=com.docker.compose.service={service}",
        "--filter",
        "status=running",
        "--format",
        "{{.ID}}",
    ]
    result = subprocess.run(command, capture_output=True, text=True, timeout=3, check=True)
    identifiers = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if len(identifiers) != 1 or not CONTAINER_ID.fullmatch(identifiers[0]):
        raise RuntimeError("Expected one running PostgreSQL service")
    return identifiers[0]


def read_snapshot(container, database, username, docker="docker"):
    command = [
        docker,
        "exec",
        "--env",
        "PGOPTIONS=-c statement_timeout=1000 -c default_transaction_read_only=on",
        container,
        "psql",
        "-X",
        "--csv",
        "--no-psqlrc",
        "--set=ON_ERROR_STOP=1",
        f"--username={username}",
        f"--dbname={database}",
        "--command",
        SNAPSHOT_SQL,
    ]
    result = subprocess.run(command, capture_output=True, text=True, timeout=3, check=True)
    rows = list(csv.DictReader(io.StringIO(result.stdout)))
    return [sanitize_row(row, database) for row in rows]


def optional_int(value):
    return int(value) if value not in (None, "") else None


def sanitize_row(row, expected_database):
    if row.get("database") != expected_database:
        raise RuntimeError("PostgreSQL diagnostic database mismatch")
    family = row.get("query_family") or "unmapped"
    if family not in QUERY_FAMILIES:
        family = "unmapped"
    relation = row.get("confirmed_relation") or None
    if relation and not re.fullmatch(r"[a-z_]{1,63}", relation):
        relation = None
    return {
        "database": expected_database,
        "waiter_pid": int(row["waiter_pid"]),
        "blocker_pid": optional_int(row.get("blocker_pid")),
        "blocker_state": row.get("blocker_state") or "unknown",
        "waiter_state": row.get("waiter_state") or "unknown",
        "wait_event_type": row.get("wait_event_type") or "none",
        "wait_event": row.get("wait_event") or "none",
        "query_id": optional_int(row.get("query_id")),
        "transaction_age_seconds": max(0, int(row.get("transaction_age_seconds") or 0)),
        "confirmed_relation": relation,
        "query_family": family,
        "truncated": str(row.get("truncated", "false")).lower() in {"t", "true", "1"},
    }


def otlp_body(rows, environment, host, observed_at_ns):
    records = []
    for row in rows:
        attributes = []
        for key, value in row.items():
            if key == "truncated" or value is None:
                continue
            otlp_value = {"intValue": str(value)} if isinstance(value, int) else {"stringValue": str(value)}
            attributes.append({"key": key, "value": otlp_value})
        records.append(
            {
                "timeUnixNano": str(observed_at_ns),
                "severityText": "INFO",
                "body": {"stringValue": "beanflow_db_blocking_snapshot"},
                "attributes": attributes,
            }
        )
    return {
        "resourceLogs": [
            {
                "resource": {
                    "attributes": [
                        {"key": "service.name", "value": {"stringValue": "beanflow-db-diagnostics"}},
                        {"key": "service.namespace", "value": {"stringValue": "beanflow"}},
                        {"key": "deployment.environment.name", "value": {"stringValue": environment}},
                        {"key": "host.name", "value": {"stringValue": host}},
                    ]
                },
                "scopeLogs": [{"scope": {"name": "beanflow.db.diagnostics"}, "logRecords": records}],
            }
        ]
    }


def send_otlp(endpoint, body):
    request = urllib.request.Request(
        endpoint,
        data=json.dumps(body, separators=(",", ":")).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=3) as response:
        if response.status < 200 or response.status >= 300:
            raise RuntimeError("OTLP log export failed")


def metric_text(state):
    lines = [
        f"beanflow_db_diagnostics_collection_success {state['collection_success']}",
        f"beanflow_db_diagnostics_last_success_timestamp_seconds {state['last_success']}",
        f"beanflow_db_diagnostics_log_export_success {state['log_export_success']}",
        f"beanflow_db_diagnostics_log_last_success_timestamp_seconds {state['log_last_success']}",
    ]
    if state["last_success"]:
        lines.extend(
            [
                f"beanflow_db_diagnostics_blocked_sessions {state['blocked_sessions']}",
                f"beanflow_db_diagnostics_max_transaction_age_seconds {state['max_transaction_age']}",
                f"beanflow_db_diagnostics_snapshot_truncated {state['truncated']}",
            ]
        )
    return "\n".join(lines) + "\n"


def collect_once(args, state, export_logs=True):
    rows = read_snapshot(find_postgres(args.project, args.service, args.docker), args.database, args.username, args.docker)
    observed = time.time()
    state.update(
        collection_success=1,
        last_success=observed,
        blocked_sessions=len(rows),
        max_transaction_age=max((row["transaction_age_seconds"] for row in rows), default=0),
        truncated=int(any(row["truncated"] for row in rows)),
    )
    if export_logs:
        try:
            send_otlp(args.otlp_endpoint, otlp_body(rows, args.environment, args.host, time.time_ns()))
        except Exception as error:
            state["log_export_success"] = 0
            raise LogExportFailure("OTLP log export failed") from error
        state["log_export_success"] = 1
        state["log_last_success"] = observed
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bind", required=True)
    parser.add_argument("--allow", action="append", required=True)
    parser.add_argument("--port", type=int, default=19102)
    parser.add_argument("--project", required=True)
    parser.add_argument("--service", default="postgres")
    parser.add_argument("--database", required=True)
    parser.add_argument("--username", default="beanflow")
    parser.add_argument("--environment", required=True)
    parser.add_argument("--host", required=True)
    parser.add_argument("--otlp-endpoint")
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--output-only", action="store_true")
    args = parser.parse_args()
    for value, field in (
        (args.project, "Compose project"),
        (args.service, "Compose service"),
        (args.database, "database"),
        (args.username, "database user"),
        (args.environment, "environment"),
        (args.host, "host"),
    ):
        validate_name(value, field)
    address = ipaddress.ip_address(args.bind)
    if not address.is_private or address.is_unspecified:
        parser.error("An explicit private bind address is required")
    allowed = {str(ipaddress.ip_address(value)) for value in args.allow}
    if args.output_only and not args.once:
        parser.error("--output-only is valid only with --once")
    if not args.output_only and not args.otlp_endpoint:
        parser.error("--otlp-endpoint is required")
    if args.otlp_endpoint and not args.otlp_endpoint.startswith(("http://", "https://")):
        parser.error("OTLP endpoint must use HTTP or HTTPS")

    state = {
        "collection_success": 0,
        "last_success": 0,
        "log_export_success": 0,
        "log_last_success": 0,
        "blocked_sessions": 0,
        "max_transaction_age": 0,
        "truncated": 0,
    }
    if args.once:
        rows = collect_once(args, state, export_logs=not args.output_only)
        print(json.dumps({"metrics": metric_text(state), "rows": rows}, sort_keys=True))
        return

    lock = threading.Lock()

    def poll():
        while True:
            started = time.monotonic()
            try:
                collect_once(args, state)
            except LogExportFailure:
                print("db diagnostics log export failed", flush=True)
            except Exception as error:
                state["collection_success"] = 0
                state["log_export_success"] = 0
                print("db diagnostics collection failed: " + type(error).__name__, flush=True)
            with lock:
                state["text"] = metric_text(state)
            time.sleep(max(1, 10 - (time.monotonic() - started)))

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.client_address[0] not in allowed:
                self.send_error(403)
                return
            if self.path != "/metrics":
                self.send_error(404)
                return
            with lock:
                body = state.get("text", metric_text(state)).encode()
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
