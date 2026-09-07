#!/usr/bin/env python3
"""Emit only non-secret deployment/resource settings needed to compare load runs."""
import json
import subprocess
import time
import shlex


def main():
    names = ["api", "frontend", "postgres", "toss-driver", "alloy"]
    containers = json.loads(subprocess.check_output(["docker", "inspect"] + ["beanflow-perf-" + name + "-1" for name in names]))
    by = dict(zip(names, containers))
    api = by["api"]
    env = dict(item.split("=", 1) for item in api["Config"]["Env"])
    resources = {name: {key: container["HostConfig"].get(key) for key in ["Memory", "NanoCpus", "CpuQuota", "CpuPeriod", "CpusetCpus"]}
                 for name, container in by.items()}
    runtime = {"resources": resources, "dependency_images": {name: c["Image"] for name, c in by.items() if name != "api"},
               "api_options": {key: env.get(key) for key in ["SPRING_PROFILES_ACTIVE", "OTEL_JAVAAGENT_ENABLED",
                   "OTEL_TRACES_SAMPLER_ARG", "OTEL_PYROSCOPE_START_PROFILING", "PYROSCOPE_PROFILER_EVENT", "PYROSCOPE_PROFILING_INTERVAL"]}}
    runtime["jvm_memory_options"] = [option for option in shlex.split(env.get("JAVA_TOOL_OPTIONS", ""))
                                     if option.startswith(("-XX:MaxRAMPercentage=", "-XX:InitialRAMPercentage=", "-Xmx", "-Xms"))]
    print(json.dumps({"captured_ms": int(time.time() * 1000), "deployment_id": api["Image"], "runtime": runtime}, indent=2))


if __name__ == "__main__":
    main()
