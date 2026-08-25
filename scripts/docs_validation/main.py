from __future__ import annotations

from pathlib import Path
import sys

from .common import ValidationError
from .exec_plans import validate_exec_plans
from .links import validate_links
from .openapi import validate_openapi
from .policies import validate_policy_ids


def validate_repository(root: Path) -> None:
    markdown_count = validate_links(root)
    plan_count = validate_exec_plans(root)
    policy_count = validate_policy_ids(root)
    openapi = validate_openapi(root)
    adr_count = len(list((root / "docs/adr").glob("ADR-*.md")))

    print(
        "OpenAPI YAML and semantic checks passed "
        f"(target {openapi.target_paths} paths/{openapi.target_operations} operations, "
        f"runtime {openapi.runtime_paths} paths/{openapi.runtime_operations} operations, "
        f"{openapi.schemas} schemas; semantic contracts "
        f"{openapi.contract_operations} operations/{openapi.contract_schemas} schemas)."
    )
    print(
        f"Validated {policy_count} unique business policies, {adr_count} ADRs, "
        f"{markdown_count} Markdown files, and {plan_count} ExecPlans."
    )


def main() -> int:
    try:
        validate_repository(Path.cwd())
    except ValidationError as exc:
        print(f"Documentation validation failed: {exc}", file=sys.stderr)
        return 1
    return 0
