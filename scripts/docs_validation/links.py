from __future__ import annotations

from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

from .common import ValidationError, read_text


MARKDOWN_LINK = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
EXCLUDED_DIRECTORIES = {".git", ".gradle", "build", "node_modules"}
CANONICAL_INDEX_TARGETS = {
    "docs/architecture/failure-semantics.md",
    "docs/decisions/README.md",
    "docs/product/business-policy-decisions.md",
    "docs/testing/definition-of-done.md",
    "openapi/beanflow-v1-runtime.yaml",
    "openapi/beanflow-v1.yaml",
}


def markdown_files(root: Path) -> list[Path]:
    return sorted(
        path
        for path in root.rglob("*.md")
        if not any(part in EXCLUDED_DIRECTORIES for part in path.relative_to(root).parts)
    )


def local_link_targets(document: Path) -> list[Path]:
    targets: list[Path] = []
    for raw_target in MARKDOWN_LINK.findall(read_text(document)):
        target = raw_target.strip()
        if target.startswith("<") and target.endswith(">"):
            target = target[1:-1]
        target = target.split(maxsplit=1)[0]
        parsed = urlsplit(target)
        if parsed.scheme or parsed.netloc or target.startswith("#"):
            continue
        relative = unquote(parsed.path)
        if not relative:
            continue
        targets.append((document.parent / relative).resolve())
    return targets


def validate_links(root: Path) -> int:
    index = root / "docs/index.md"
    if not index.is_file():
        raise ValidationError("docs/index.md is required")

    index_targets = local_link_targets(index)
    indexed_paths = {
        target.relative_to(root.resolve()).as_posix()
        for target in index_targets
        if target.is_relative_to(root.resolve())
    }
    missing_canonical = sorted(CANONICAL_INDEX_TARGETS - indexed_paths)
    if missing_canonical:
        raise ValidationError(
            "docs/index.md must link every canonical current document: "
            + ", ".join(missing_canonical)
        )

    broken: list[str] = []
    documents = markdown_files(root)
    for document in documents:
        for target in local_link_targets(document):
            if not target.exists():
                broken.append(f"{document.relative_to(root)} -> {target}")
    if broken:
        raise ValidationError("Broken relative documentation links:\n  " + "\n  ".join(broken))
    return len(documents)
