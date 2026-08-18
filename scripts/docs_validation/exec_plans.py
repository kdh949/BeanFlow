from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from pathlib import Path
import re

from .common import ValidationError, read_text


METADATA = re.compile(
    r"\A# [^\n]+\n\n"
    r"> \*\*Status:\*\* `(ACTIVE|COMPLETED)`\n"
    r"> \*\*Kind:\*\* `(IMPLEMENTATION|ORCHESTRATION)`\n"
    r"> \*\*Implementation-Ready:\*\* `(true|false)`\n"
    r"> \*\*Writes-Migration:\*\* `(true|false)`\n"
    r"> \*\*Depends-On:\*\* (.+)\n"
    r"> \*\*Completed-At:\*\* `([^`]+)`\n"
)


@dataclass(frozen=True)
class ExecPlan:
    path: str
    status: str
    kind: str
    implementation_ready: bool
    writes_migration: bool
    dependencies: tuple[str, ...]


def load_exec_plans(root: Path) -> dict[str, ExecPlan]:
    plan_root = root / "docs/exec-plans"
    files = sorted(plan_root.glob("*/*.md"))
    if not files:
        raise ValidationError("No ExecPlans found under docs/exec-plans/{active,completed}")

    plans: dict[str, ExecPlan] = {}
    errors: list[str] = []
    for file in files:
        relative = file.relative_to(root).as_posix()
        match = METADATA.match(read_text(file))
        if match is None:
            errors.append(f"{relative}: canonical metadata must follow the title")
            continue
        status, kind, ready, writes, dependency_field, completed_at = match.groups()
        directory = file.parent.name
        expected_status = {"active": "ACTIVE", "completed": "COMPLETED"}.get(directory)
        if expected_status is None or status != expected_status:
            errors.append(f"{relative}: directory and Status disagree")
        if status == "ACTIVE" and completed_at != "—":
            errors.append(f"{relative}: ACTIVE Completed-At must be —")
        if status == "COMPLETED":
            try:
                date.fromisoformat(completed_at)
            except ValueError:
                errors.append(f"{relative}: COMPLETED Completed-At must be an ISO-8601 date")
        if kind == "ORCHESTRATION" and (ready != "false" or writes != "false"):
            errors.append(
                f"{relative}: ORCHESTRATION requires Implementation-Ready/Writes-Migration false"
            )
        if dependency_field == "—":
            dependencies: tuple[str, ...] = ()
        else:
            parsed = tuple(re.findall(r"`([^`]+)`", dependency_field))
            canonical = ", ".join(f"`{dependency}`" for dependency in parsed)
            if not parsed or dependency_field != canonical:
                errors.append(
                    f"{relative}: Depends-On must be — or comma-separated backtick paths"
                )
            dependencies = parsed
        plans[relative] = ExecPlan(
            path=relative,
            status=status,
            kind=kind,
            implementation_ready=ready == "true",
            writes_migration=writes == "true",
            dependencies=dependencies,
        )
    if errors:
        raise ValidationError("Invalid ExecPlan metadata:\n  " + "\n  ".join(errors))
    return plans


def validate_exec_plans(root: Path) -> int:
    plans = load_exec_plans(root)
    for plan in plans.values():
        for dependency in plan.dependencies:
            if dependency not in plans:
                raise ValidationError(f"ExecPlan dependency does not exist: {plan.path} -> {dependency}")
            if dependency == plan.path:
                raise ValidationError(f"ExecPlan cannot depend on itself: {plan.path}")

    active = {path: plan for path, plan in plans.items() if plan.status == "ACTIVE"}
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit(path: str, trail: tuple[str, ...]) -> None:
        if path in visiting:
            cycle_start = trail.index(path)
            raise ValidationError("Active ExecPlan dependency cycle: " + " -> ".join(trail[cycle_start:] + (path,)))
        if path in visited:
            return
        visiting.add(path)
        for dependency in active[path].dependencies:
            if dependency in active:
                visit(dependency, trail + (path,))
        visiting.remove(path)
        visited.add(path)

    for path in active:
        visit(path, ())
    return len(plans)
