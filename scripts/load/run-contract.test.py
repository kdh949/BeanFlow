import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("runner", Path(__file__).with_name("run.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
annotation_spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-annotation.py"))
publisher = importlib.util.module_from_spec(annotation_spec)
annotation_spec.loader.exec_module(publisher)


class RunContractTest(unittest.TestCase):
    def test_compare_refuses_different_load_conditions(self):
        baseline = {"test_id": "before", "conditions": {"rate": "1"}, "dataset_id": "v1", "base_url": "https://test.invalid",
                    "script_sha256": "same", "generator": {"cpu_count": 4}, "k6_version": "fixed", "deployment_id": "before", "target_runtime": {"resources": "fixed"}}
        current = {**baseline, "test_id": "after", "deployment_id": "after"}
        self.assertTrue(runner.compare(current, baseline)["comparable"])
        current["conditions"] = {"rate": "10"}
        self.assertEqual(runner.compare(current, baseline)["different_conditions"], ["conditions"])

    def test_missing_measurement_remains_unknown(self):
        self.assertIsNone(runner.metric({}, "dropped_iterations", "count"))
        self.assertEqual(runner.metric({"metrics": {"dropped_iterations": {"values": {"count": 0}}}}, "dropped_iterations", "count"), 0)

    def test_annotation_rejects_extra_fields_and_invalid_intervals(self):
        value = {"time": 1000, "timeEnd": 2000, "tags": ["beanflow-load", "run-1"], "text": "run-1: PASSED"}
        self.assertEqual(publisher.validate(value), value)
        for bad in [{**value, "url": "https://unexpected.invalid"}, {**value, "timeEnd": 999},
                    {**value, "tags": ["unrelated", "run-1"]}]:
            with self.assertRaises(ValueError):
                publisher.validate(bad)


if __name__ == "__main__":
    unittest.main()
