#!/usr/bin/env python3
"""Summarize Gradle JUnit XML without hiding missing or malformed evidence."""

from __future__ import annotations

import argparse
import csv
import math
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class SuiteTiming:
    class_name: str
    tests: int
    failures: int
    errors: int
    skipped: int
    seconds: float


def integer_attr(root: ET.Element, name: str, source: Path) -> int:
    raw = root.attrib.get(name, "0")
    try:
        return int(raw)
    except ValueError as exc:
        raise ValueError(f"{source}: invalid {name}={raw!r}") from exc


def float_attr(root: ET.Element, name: str, source: Path) -> float:
    raw = root.attrib.get(name, "0")
    try:
        value = float(raw)
    except ValueError as exc:
        raise ValueError(f"{source}: invalid {name}={raw!r}") from exc
    if not math.isfinite(value) or value < 0:
        raise ValueError(f"{source}: invalid {name}={raw!r}")
    return value


def load_timings(results_dir: Path) -> list[SuiteTiming]:
    xml_files = sorted(results_dir.rglob("TEST-*.xml")) if results_dir.is_dir() else []
    if not xml_files:
        raise ValueError(f"No Gradle JUnit XML found under {results_dir}")

    timings: list[SuiteTiming] = []
    for xml_file in xml_files:
        try:
            root = ET.parse(xml_file).getroot()
        except ET.ParseError as exc:
            raise ValueError(f"Malformed JUnit XML: {xml_file}: {exc}") from exc
        if root.tag != "testsuite":
            raise ValueError(f"{xml_file}: expected testsuite root, got {root.tag}")
        class_name = root.attrib.get("name", "").strip()
        if not class_name:
            raise ValueError(f"{xml_file}: testsuite name is required")
        timings.append(
            SuiteTiming(
                class_name=class_name,
                tests=integer_attr(root, "tests", xml_file),
                failures=integer_attr(root, "failures", xml_file),
                errors=integer_attr(root, "errors", xml_file),
                skipped=integer_attr(root, "skipped", xml_file),
                seconds=float_attr(root, "time", xml_file),
            )
        )
    return sorted(timings, key=lambda item: (-item.seconds, item.class_name))


def write_tsv(output: Path, timings: list[SuiteTiming]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, delimiter="\t", lineterminator="\n")
        writer.writerow(("class_name", "tests", "failures", "errors", "skipped", "seconds"))
        for timing in timings:
            writer.writerow(
                (
                    timing.class_name,
                    timing.tests,
                    timing.failures,
                    timing.errors,
                    timing.skipped,
                    f"{timing.seconds:.3f}",
                )
            )


def append_summary(summary: Path | None, timings: list[SuiteTiming]) -> None:
    if summary is None:
        return
    totals = SuiteTiming(
        class_name="TOTAL",
        tests=sum(item.tests for item in timings),
        failures=sum(item.failures for item in timings),
        errors=sum(item.errors for item in timings),
        skipped=sum(item.skipped for item in timings),
        seconds=sum(item.seconds for item in timings),
    )
    summary.parent.mkdir(parents=True, exist_ok=True)
    with summary.open("a", encoding="utf-8") as stream:
        stream.write("\n### Gradle test timing\n")
        stream.write(
            f"- Classes: `{len(timings)}`; tests: `{totals.tests}`; failures: `{totals.failures}`; "
            f"errors: `{totals.errors}`; skipped: `{totals.skipped}`; class time sum: `{totals.seconds:.3f}s`\n\n"
        )
        stream.write("| Class | Seconds | Tests | Failures + errors | Skipped |\n")
        stream.write("|---|---:|---:|---:|---:|\n")
        for timing in timings[:20]:
            stream.write(
                f"| `{timing.class_name}` | {timing.seconds:.3f} | {timing.tests} | "
                f"{timing.failures + timing.errors} | {timing.skipped} |\n"
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--summary", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        timings = load_timings(args.results_dir)
        write_tsv(args.output, timings)
        append_summary(args.summary, timings)
    except (OSError, ValueError) as exc:
        print(f"Test timing evidence error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
