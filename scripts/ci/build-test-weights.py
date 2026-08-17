#!/usr/bin/env python3
"""Build deterministic median class weights from complete CI timing runs."""

from __future__ import annotations

import argparse
import csv
import math
import statistics
import sys
from pathlib import Path

TIMING_HEADER = ("class_name", "tests", "failures", "errors", "skipped", "seconds")


def read_run(run_dir: Path) -> dict[str, float]:
    timing_files = sorted(run_dir.rglob("gradle-test-timings.tsv")) if run_dir.is_dir() else []
    if not timing_files:
        raise ValueError(f"No timing TSV found under {run_dir}")

    timings: dict[str, float] = {}
    for timing_file in timing_files:
        with timing_file.open(encoding="utf-8", newline="") as stream:
            reader = csv.DictReader(stream, delimiter="\t")
            if tuple(reader.fieldnames or ()) != TIMING_HEADER:
                raise ValueError(f"{timing_file}: unexpected timing header")
            for row in reader:
                class_name = row["class_name"].strip()
                if not class_name:
                    raise ValueError(f"{timing_file}: class_name is required")
                if class_name in timings:
                    raise ValueError(f"{run_dir}: duplicate class timing: {class_name}")
                try:
                    seconds = float(row["seconds"])
                except ValueError as exc:
                    raise ValueError(f"{timing_file}: invalid seconds for {class_name}") from exc
                if not math.isfinite(seconds) or seconds < 0:
                    raise ValueError(f"{timing_file}: invalid seconds for {class_name}")
                timings[class_name] = seconds
    return timings


def build_weights(run_dirs: list[Path]) -> dict[str, float]:
    if len(run_dirs) != 3:
        raise ValueError(f"Exactly three timing runs are required, got {len(run_dirs)}")
    runs = [read_run(run_dir) for run_dir in run_dirs]
    expected_classes = set(runs[0])
    for index, run in enumerate(runs[1:], start=2):
        actual_classes = set(run)
        if actual_classes != expected_classes:
            raise ValueError(
                f"Run {index} class coverage differs: "
                f"missing={sorted(expected_classes - actual_classes)}, "
                f"unexpected={sorted(actual_classes - expected_classes)}"
            )
    return {
        class_name: statistics.median(run[class_name] for run in runs)
        for class_name in sorted(expected_classes)
    }


def write_weights(output: Path, weights: dict[str, float]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerow(("class_name", "median_seconds"))
        for class_name, seconds in weights.items():
            writer.writerow((class_name, f"{seconds:.3f}"))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", action="append", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        write_weights(args.output, build_weights(args.run_dir))
    except (OSError, ValueError) as exc:
        print(f"Test weight evidence error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
