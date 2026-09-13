import importlib.util
import json
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("db_diagnostics", Path(__file__).with_name("db-diagnostics-exporter.py"))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class DatabaseDiagnosticsExporterTest(unittest.TestCase):
    def test_snapshot_is_bounded_and_contains_no_sql_text(self):
        row = exporter.sanitize_row(
            {
                "database": "beanflow_perf",
                "waiter_pid": "41",
                "blocker_pid": "40",
                "blocker_state": "idle in transaction",
                "waiter_state": "active",
                "wait_event_type": "Lock",
                "wait_event": "transactionid",
                "query_id": "1234",
                "transaction_age_seconds": "19",
                "confirmed_relation": "fulfillment_pickup_slot",
                "query_family": "pickup_slot",
                "truncated": "false",
                "query": "UPDATE secret SET value='customer-id'",
            },
            "beanflow_perf",
        )
        body = exporter.otlp_body([row], "perf", "app-01", 123)
        encoded = json.dumps(body)
        self.assertNotIn("UPDATE", encoded)
        self.assertNotIn("customer-id", encoded)
        self.assertIn("pickup_slot", encoded)
        self.assertIn("waiter_pid", encoded)
        resource_keys = {item["key"] for item in body["resourceLogs"][0]["resource"]["attributes"]}
        self.assertEqual(
            {"service.name", "service.namespace", "deployment.environment.name", "host.name"},
            resource_keys,
        )

    def test_database_mismatch_and_unknown_family_fail_closed(self):
        with self.assertRaises(RuntimeError):
            exporter.sanitize_row({"database": "other"}, "beanflow_perf")
        row = exporter.sanitize_row(
            {
                "database": "beanflow_perf",
                "waiter_pid": "1",
                "query_family": "raw-user-value",
            },
            "beanflow_perf",
        )
        self.assertEqual("unmapped", row["query_family"])

    def test_collection_failure_keeps_last_values_and_marks_failure(self):
        state = {
            "collection_success": 1,
            "last_success": 100,
            "log_export_success": 1,
            "log_last_success": 100,
            "blocked_sessions": 2,
            "max_transaction_age": 9,
            "truncated": 0,
        }
        state["collection_success"] = 0
        state["log_export_success"] = 0
        metrics = exporter.metric_text(state)
        self.assertIn("beanflow_db_diagnostics_collection_success 0", metrics)
        self.assertIn("beanflow_db_diagnostics_blocked_sessions 2", metrics)
        self.assertIn("beanflow_db_diagnostics_last_success_timestamp_seconds 100", metrics)

    def test_read_snapshot_uses_read_only_timeout_and_no_shell(self):
        completed = SimpleNamespace(
            stdout=(
                "database,waiter_pid,blocker_pid,blocker_state,waiter_state,wait_event_type,wait_event,query_id,"
                "transaction_age_seconds,confirmed_relation,query_family,truncated\n"
            )
        )
        with patch.object(exporter.subprocess, "run", return_value=completed) as run:
            self.assertEqual([], exporter.read_snapshot("abcdef123456", "beanflow_perf", "beanflow"))
        command = run.call_args.args[0]
        self.assertIn("PGOPTIONS=-c statement_timeout=1000 -c default_transaction_read_only=on", command)
        self.assertIn("--dbname=beanflow_perf", command)
        self.assertIsNone(run.call_args.kwargs.get("shell"))
        self.assertNotIn("query", " ".join(command[:5]).lower())

    def test_empty_snapshot_still_checks_otlp_delivery(self):
        args = SimpleNamespace(
            project="beanflow-perf",
            service="postgres",
            docker="docker",
            database="beanflow_perf",
            username="beanflow",
            otlp_endpoint="http://127.0.0.1:4318/v1/logs",
            environment="perf",
            host="app-01",
        )
        state = {
            "collection_success": 0,
            "last_success": 0,
            "log_export_success": 0,
            "log_last_success": 0,
            "blocked_sessions": 0,
            "max_transaction_age": 0,
            "truncated": 0,
        }
        with (
            patch.object(exporter, "find_postgres", return_value="abcdef123456"),
            patch.object(exporter, "read_snapshot", return_value=[]),
            patch.object(exporter, "send_otlp") as send,
        ):
            exporter.collect_once(args, state)
        send.assert_called_once()
        self.assertEqual(1, state["log_export_success"])

    def test_log_export_failure_does_not_erase_collection_success(self):
        args = SimpleNamespace(
            project="beanflow-perf",
            service="postgres",
            docker="docker",
            database="beanflow_perf",
            username="beanflow",
            otlp_endpoint="http://127.0.0.1:4318/v1/logs",
            environment="perf",
            host="app-01",
        )
        state = {
            "collection_success": 0,
            "last_success": 0,
            "log_export_success": 1,
            "log_last_success": 50,
            "blocked_sessions": 0,
            "max_transaction_age": 0,
            "truncated": 0,
        }
        with (
            patch.object(exporter, "find_postgres", return_value="abcdef123456"),
            patch.object(exporter, "read_snapshot", return_value=[]),
            patch.object(exporter, "send_otlp", side_effect=OSError("unavailable")),
            self.assertRaises(exporter.LogExportFailure),
        ):
            exporter.collect_once(args, state)
        self.assertEqual(1, state["collection_success"])
        self.assertEqual(0, state["log_export_success"])
        self.assertEqual(50, state["log_last_success"])


if __name__ == "__main__":
    unittest.main()
