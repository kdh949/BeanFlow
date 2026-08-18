from __future__ import annotations

from collections import Counter
from pathlib import Path
import re

from .common import ValidationError, read_text


POLICY_ID = re.compile(r"^## (BR-\d{2})\s+", re.MULTILINE)


def validate_policy_ids(root: Path) -> int:
    policy = root / "docs/product/business-policy-decisions.md"
    ids = POLICY_ID.findall(read_text(policy))
    if not ids:
        raise ValidationError("No Business Policy IDs found")
    duplicates = sorted(policy_id for policy_id, count in Counter(ids).items() if count > 1)
    if duplicates:
        raise ValidationError("Duplicate Business Policy IDs: " + ", ".join(duplicates))
    return len(ids)
