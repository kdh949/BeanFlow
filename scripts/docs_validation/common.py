from __future__ import annotations

from pathlib import Path


class ValidationError(RuntimeError):
    """A repository contract is missing, malformed, or contradictory."""


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ValidationError(f"Cannot read {path}: {exc}") from exc
