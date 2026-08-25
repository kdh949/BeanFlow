from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from .common import ValidationError
from .openapi_contracts import validate_semantic_contracts


@dataclass(frozen=True)
class OpenApiStats:
    target_paths: int
    target_operations: int
    runtime_paths: int
    runtime_operations: int
    schemas: int
    contract_operations: int
    contract_schemas: int


def resolve_json_pointer(document: object, fragment: str) -> object:
    current = document
    for raw_token in fragment.removeprefix("/").split("/"):
        token = raw_token.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, dict) or token not in current:
            raise ValidationError(f"OpenAPI reference fragment does not exist: #{fragment}")
        current = current[token]
    return current


def resolve_path_item(item: object, owner: Path) -> dict:
    if not isinstance(item, dict):
        raise ValidationError(f"OpenAPI path item must be an object: {owner}")
    reference = item.get("$ref")
    if reference is None:
        return item
    if not isinstance(reference, str) or "#" not in reference:
        raise ValidationError(f"OpenAPI path reference must include a fragment: {reference!r}")
    relative_file, fragment = reference.split("#", maxsplit=1)
    referenced_path = owner if not relative_file else owner.parent / relative_file
    referenced = load_yaml(referenced_path.resolve())
    resolved = resolve_json_pointer(referenced, fragment)
    if not isinstance(resolved, dict):
        raise ValidationError(f"OpenAPI path reference must resolve to an object: {reference}")
    return resolved


def operation_count(document: dict, owner: Path) -> int:
    methods = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
    return sum(
        1
        for raw_item in document.get("paths", {}).values()
        for method in resolve_path_item(raw_item, owner)
        if method.lower() in methods
    )


@lru_cache(maxsize=None)
def load_yaml(path: Path) -> dict:
    try:
        import yaml
    except ImportError as exc:
        raise ValidationError(
            "Docs validation dependencies are unavailable; install scripts/ci/requirements-docs.txt"
        ) from exc
    try:
        with path.open(encoding="utf-8") as stream:
            document = yaml.safe_load(stream)
    except (OSError, yaml.YAMLError) as exc:
        raise ValidationError(f"Cannot load OpenAPI YAML {path}: {exc}") from exc
    if not isinstance(document, dict):
        raise ValidationError(f"OpenAPI root must be an object: {path}")
    return document


def load_and_validate(path: Path) -> dict:
    try:
        import yaml
        from openapi_spec_validator import validate
        from openapi_spec_validator.validation.exceptions import OpenAPIValidationError
    except ImportError as exc:
        raise ValidationError(
            "Docs validation dependencies are unavailable; install scripts/ci/requirements-docs.txt"
        ) from exc
    try:
        document = load_yaml(path)
        if document.get("openapi") != "3.1.0":
            raise ValidationError(f"OpenAPI version must be 3.1.0: {path}")
        validate(document, base_uri=path.resolve().as_uri())
        return document
    except ValidationError:
        raise
    except (OSError, yaml.YAMLError, OpenAPIValidationError, TypeError, ValueError) as exc:
        raise ValidationError(f"OpenAPI validation failed for {path}: {exc}") from exc


def validate_openapi(root: Path) -> OpenApiStats:
    target = load_and_validate(root / "openapi/beanflow-v1.yaml")
    runtime = load_and_validate(root / "openapi/beanflow-v1-runtime.yaml")
    contract_operations, contract_schemas = validate_semantic_contracts(target)
    return OpenApiStats(
        target_paths=len(target.get("paths", {})),
        target_operations=operation_count(target, root / "openapi/beanflow-v1.yaml"),
        runtime_paths=len(runtime.get("paths", {})),
        runtime_operations=operation_count(runtime, root / "openapi/beanflow-v1-runtime.yaml"),
        schemas=len(target.get("components", {}).get("schemas", {})),
        contract_operations=contract_operations,
        contract_schemas=contract_schemas,
    )
