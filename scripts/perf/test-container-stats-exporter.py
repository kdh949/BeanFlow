import importlib.util
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("exporter", Path(__file__).with_name("container-stats-exporter.py"))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class ContainerStatsTest(unittest.TestCase):
    def test_cpu_memory_and_unlimited_quota(self):
        stats = {"memory_stats": {"usage": 1000, "stats": {"inactive_file": 200}},
                 "cpu_stats": {"system_cpu_usage": 200, "online_cpus": 4, "cpu_usage": {"total_usage": 50}},
                 "precpu_stats": {"system_cpu_usage": 100, "cpu_usage": {"total_usage": 25}}}
        detail = {"HostConfig": {"Memory": 2048, "NanoCpus": 0}, "RestartCount": 2, "State": {"Running": True}}
        result = exporter.samples("api", stats, detail)
        self.assertIn('beanflow_container_cpu_cores{service="api"} 1.0', result)
        self.assertIn('beanflow_container_memory_working_set_bytes{service="api"} 800', result)
        self.assertIn('beanflow_container_cpu_limit_cores{service="api"} 0.0', result)
        self.assertIn('beanflow_container_restart_count{service="api"} 2', result)
        stats["cpu_stats"]["system_cpu_usage"] = 100
        with self.assertRaises(RuntimeError):
            exporter.samples("api", stats, detail)

    def test_missing_service_is_failed_collection(self):
        with patch.object(exporter, "docker_get", return_value=[]):
            with self.assertRaises(RuntimeError):
                exporter.collect("beanflow-perf")


if __name__ == "__main__":
    unittest.main()
