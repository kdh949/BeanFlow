from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import unittest


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from docs_validation.common import ValidationError
from docs_validation.exec_plans import validate_exec_plans
from docs_validation.links import CANONICAL_INDEX_TARGETS, validate_links
from docs_validation.openapi import load_and_validate, operation_count
from docs_validation.policies import validate_policy_ids


class RepositoryFixture(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write(self, relative: str, content: str = "") -> Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def create_canonical_index(self) -> None:
        links: list[str] = []
        for relative in sorted(CANONICAL_INDEX_TARGETS):
            self.write(relative, "# Contract\n")
            target = Path(relative)
            index_relative = Path("..") / target if target.parts[0] != "docs" else Path(*target.parts[1:])
            links.append(f"- [contract]({index_relative.as_posix()})")
        self.write("docs/index.md", "# Index\n\n" + "\n".join(links) + "\n")


class LinkValidationTest(RepositoryFixture):
    def test_canonical_index_and_resolved_relative_links_pass(self) -> None:
        self.create_canonical_index()
        self.write("docs/guide.md", "[policy](product/business-policy-decisions.md)\n")

        self.assertGreaterEqual(validate_links(self.root), 6)

    def test_broken_relative_link_fails(self) -> None:
        self.create_canonical_index()
        self.write("docs/guide.md", "[missing](missing.md)\n")

        with self.assertRaisesRegex(ValidationError, "Broken relative documentation links"):
            validate_links(self.root)


class ExecPlanValidationTest(RepositoryFixture):
    def plan(
        self,
        relative: str,
        *,
        status: str = "ACTIVE",
        dependencies: str = "—",
        completed_at: str = "—",
    ) -> None:
        self.write(
            relative,
            "# Fixture plan\n\n"
            f"> **Status:** `{status}`\n"
            "> **Kind:** `IMPLEMENTATION`\n"
            "> **Implementation-Ready:** `true`\n"
            "> **Writes-Migration:** `false`\n"
            f"> **Depends-On:** {dependencies}\n"
            f"> **Completed-At:** `{completed_at}`\n",
        )

    def test_active_and_completed_metadata_with_existing_dependency_pass(self) -> None:
        completed = "docs/exec-plans/completed/base.md"
        self.plan(completed, status="COMPLETED", completed_at="2026-08-19")
        self.plan("docs/exec-plans/active/current.md", dependencies=f"`{completed}`")

        self.assertEqual(validate_exec_plans(self.root), 2)

    def test_active_dependency_cycle_fails(self) -> None:
        first = "docs/exec-plans/active/first.md"
        second = "docs/exec-plans/active/second.md"
        self.plan(first, dependencies=f"`{second}`")
        self.plan(second, dependencies=f"`{first}`")

        with self.assertRaisesRegex(ValidationError, "dependency cycle"):
            validate_exec_plans(self.root)

    def test_missing_dependency_fails(self) -> None:
        self.plan(
            "docs/exec-plans/active/current.md",
            dependencies="`docs/exec-plans/completed/missing.md`",
        )

        with self.assertRaisesRegex(ValidationError, "does not exist"):
            validate_exec_plans(self.root)


class PolicyValidationTest(RepositoryFixture):
    def test_unique_policy_ids_pass_without_hard_coded_count(self) -> None:
        self.write(
            "docs/product/business-policy-decisions.md",
            "# Policies\n\n## BR-01 First\n\n## BR-47 Last\n",
        )

        self.assertEqual(validate_policy_ids(self.root), 2)

    def test_duplicate_policy_id_fails(self) -> None:
        self.write(
            "docs/product/business-policy-decisions.md",
            "# Policies\n\n## BR-01 First\n\n## BR-01 Duplicate\n",
        )

        with self.assertRaisesRegex(ValidationError, "Duplicate Business Policy IDs"):
            validate_policy_ids(self.root)


class OpenApiValidationTest(RepositoryFixture):
    def test_openapi_31_document_passes(self) -> None:
        path = self.write(
            "openapi.yaml",
            "openapi: 3.1.0\ninfo:\n  title: Fixture\n  version: 1.0.0\npaths: {}\n",
        )

        self.assertEqual(load_and_validate(path)["openapi"], "3.1.0")

    def test_wrong_openapi_version_fails(self) -> None:
        path = self.write(
            "openapi.yaml",
            "openapi: 3.0.3\ninfo:\n  title: Fixture\n  version: 1.0.0\npaths: {}\n",
        )

        with self.assertRaisesRegex(ValidationError, "version must be 3.1.0"):
            load_and_validate(path)

    def test_runtime_path_item_references_count_target_operations(self) -> None:
        self.write(
            "target.yaml",
            """
paths:
  /things:
    get:
      responses: {}
""".lstrip(),
        )
        runtime = {"paths": {"/things": {"$ref": "./target.yaml#/paths/~1things"}}}

        self.assertEqual(operation_count(runtime, self.root / "runtime.yaml"), 1)


if __name__ == "__main__":
    unittest.main()
