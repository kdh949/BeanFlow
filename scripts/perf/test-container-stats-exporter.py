import importlib.util
import unittest
from types import SimpleNamespace
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("exporter", Path(__file__).with_name("container-stats-exporter.py"))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class ContainerStatsTest(unittest.TestCase):
    def test_cpu_memory_and_unlimited_quota(self):
        stats = {"memory_stats": {"usage": 1000, "stats": {"inactive_file": 200}},
                 "cpu_stats": {"system_cpu_usage": 200, "online_cpus": 4, "cpu_usage": {"total_usage": 50},
                               "throttling_data": {"periods": 9, "throttled_periods": 3, "throttled_time": 2_000_000_000}},
                 "precpu_stats": {"system_cpu_usage": 100, "cpu_usage": {"total_usage": 25}}}
        detail = {"HostConfig": {"Memory": 2048, "NanoCpus": 0}, "RestartCount": 2,
                  "State": {"Running": True, "OOMKilled": False}}
        result = exporter.samples("api", stats, detail)
        self.assertIn('beanflow_container_cpu_cores{service="api"} 1.0', result)
        self.assertIn('beanflow_container_memory_working_set_bytes{service="api"} 800', result)
        self.assertIn('beanflow_container_cpu_limit_cores{service="api"} 0.0', result)
        self.assertIn('beanflow_container_restart_count{service="api"} 2', result)
        self.assertIn('beanflow_container_cpu_throttled_periods_total{service="api"} 3', result)
        self.assertIn('beanflow_container_cpu_throttled_seconds_total{service="api"} 2.0', result)
        self.assertIn('beanflow_container_oom_killed{service="api"} 0', result)
        stats["cpu_stats"]["system_cpu_usage"] = 100
        with self.assertRaises(RuntimeError):
            exporter.samples("api", stats, detail)

    def test_missing_service_is_failed_collection(self):
        with patch.object(exporter, "docker_get", return_value=[]):
            with self.assertRaises(RuntimeError):
                exporter.collect("beanflow-perf")

    def test_unsupported_signal_is_explicit_without_fake_value(self):
        stats = {"memory_stats": {"usage": 1000, "stats": {}},
                 "cpu_stats": {"system_cpu_usage": 200, "online_cpus": 1, "cpu_usage": {"total_usage": 50}},
                 "precpu_stats": {"system_cpu_usage": 100, "cpu_usage": {"total_usage": 25}}}
        detail = {"HostConfig": {"Memory": 0, "NanoCpus": 0}, "RestartCount": 0, "State": {"Running": True}}
        result = exporter.samples("api", stats, detail)
        self.assertIn('beanflow_container_signal_supported{service="api",signal="cpu_throttling"} 0', result)
        self.assertNotIn("beanflow_container_cpu_throttled_periods_total", "\n".join(result))
        self.assertIn('beanflow_container_signal_supported{service="api",signal="oom_state"} 0', result)
        self.assertNotIn("beanflow_container_oom_killed", "\n".join(result))

    def test_filesystem_uses_only_allowlisted_path_and_no_path_label(self):
        with patch.object(exporter.os, "statvfs", return_value=SimpleNamespace(f_blocks=10, f_bavail=4, f_frsize=1024)):
            result = exporter.filesystem_samples("/")
        self.assertIn('beanflow_host_filesystem_size_bytes{scope="app_host"} 10240', result)
        self.assertIn('beanflow_host_filesystem_available_bytes{scope="app_host"} 4096', result)
        self.assertNotIn('path=', "\n".join(result))
        with self.assertRaises(RuntimeError):
            exporter.filesystem_samples("/tmp")


if __name__ == "__main__":
    unittest.main()
