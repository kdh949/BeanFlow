import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest


spec = importlib.util.spec_from_file_location("prepare_deployment", Path(__file__).with_name("prepare-doppler-deployment.py"))
prepare_deployment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare_deployment)


class DopplerDeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve() / "deployment/secrets"
        template = prepare_deployment.ROOT / "deploy/env/staging.env.example"
        self.values = dict(line.split("=", 1) for line in template.read_text().splitlines() if line)
        self.values.update({name: f"fixture-{name}" for name in prepare_deployment.COMMON_SECRETS})
        self.values.update({
            "BEANFLOW_SECRETS_DIR": str(self.directory),
            "BEANFLOW_IMAGE_TAG": "a" * 40,
            "BEANFLOW_KEYCLOAK_MODE": "external",
            "BEANFLOW_OPERATIONS_OIDC_AUTHORIZATION_SERVER_URL": "https://sso.example.test:5443",
            "BEANFLOW_OPERATIONS_OIDC_ISSUER_URI": "https://sso.example.test:5443/realms/beanflow",
            "BEANFLOW_OPERATIONS_OIDC_REALM": "beanflow",
            "BEANFLOW_OPERATIONS_OIDC_CLIENT_ID": "beanflow",
            "BEANFLOW_JWK_SET_URI": "https://sso.example.test:5443/realms/beanflow/protocol/openid-connect/certs",
            "BEANFLOW_VAULT_CA_PEM": "-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----\n",
        })

    def prepare(self):
        return prepare_deployment.prepare("staging", self.values)

    def test_external_preparation_omits_keycloak_secrets_and_preserves_pem(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            env_file = self.prepare()
        self.assertEqual(output.getvalue(), "")
        self.assertEqual({path.name for path in self.directory.iterdir()}, set(prepare_deployment.COMMON_SECRETS))
        self.assertEqual((self.directory / "BEANFLOW_VAULT_CA_PEM").read_text(), self.values["BEANFLOW_VAULT_CA_PEM"])
        for path in (env_file, *self.directory.iterdir()):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.directory.stat().st_mode), 0o700)
        self.assertIn("BEANFLOW_KEYCLOAK_MODE=external\n", env_file.read_text())
        for name in prepare_deployment.COMMON_SECRETS:
            self.assertNotIn(f"{name}=", env_file.read_text())

    def test_missing_secret_writes_nothing(self):
        del self.values["BEANFLOW_VAULT_CA_PEM"]
        with self.assertRaisesRegex(SystemExit, "BEANFLOW_VAULT_CA_PEM"):
            self.prepare()
        self.assertFalse(self.directory.exists())

    def test_missing_external_client_writes_nothing(self):
        del self.values["BEANFLOW_OPERATIONS_OIDC_CLIENT_ID"]
        with self.assertRaisesRegex(SystemExit, "BEANFLOW_OPERATIONS_OIDC_CLIENT_ID"):
            self.prepare()
        self.assertFalse(self.directory.exists())

    def test_unknown_mode_writes_nothing(self):
        self.values["BEANFLOW_KEYCLOAK_MODE"] = "automatic"
        with self.assertRaisesRegex(SystemExit, "must be bundled or external"):
            self.prepare()
        self.assertFalse(self.directory.exists())

    def test_bundled_mode_still_requires_keycloak_password(self):
        self.values.pop("BEANFLOW_KEYCLOAK_MODE")
        with self.assertRaisesRegex(SystemExit, "BEANFLOW_KEYCLOAK_DB_PASSWORD"):
            self.prepare()
        self.assertFalse(self.directory.exists())

    def test_repeated_snapshot_keeps_secret_inode_for_existing_container_mounts(self):
        self.prepare()
        path = self.directory / "BEANFLOW_POSTGRES_PASSWORD"
        inode = path.stat().st_ino
        self.prepare()
        self.assertEqual(path.stat().st_ino, inode)

    def test_existing_generator_trailing_newline_does_not_rotate_secret(self):
        self.prepare()
        path = self.directory / "BEANFLOW_POSTGRES_PASSWORD"
        path.write_text(path.read_text() + "\n")
        inode = path.stat().st_ino
        self.prepare()
        self.assertEqual(path.stat().st_ino, inode)
        self.assertTrue(path.read_text().endswith("\n"))

    def test_changed_existing_password_does_not_replace_any_files(self):
        env_file = self.prepare()
        before = env_file.read_text()
        self.values["BEANFLOW_POSTGRES_PASSWORD"] = "different-password"
        self.values["BEANFLOW_IMAGE_TAG"] = "b" * 40
        with self.assertRaisesRegex(SystemExit, "Existing secret differs"):
            self.prepare()
        self.assertEqual(env_file.read_text(), before)
        self.assertNotEqual((self.directory / "BEANFLOW_POSTGRES_PASSWORD").read_text(), "different-password")

    def test_secret_symlink_is_rejected_without_changing_target(self):
        self.directory.mkdir(parents=True)
        target = self.directory.parent / "original"
        target.write_text("do-not-touch")
        (self.directory / "BEANFLOW_POSTGRES_PASSWORD").symlink_to(target)
        with self.assertRaisesRegex(SystemExit, "regular file"):
            self.prepare()
        self.assertEqual(target.read_text(), "do-not-touch")

    def test_dotenv_interpolation_is_rejected(self):
        self.values["BEANFLOW_PUBLIC_ORIGIN"] = "https://$OTHER_HOST"
        with self.assertRaisesRegex(SystemExit, "dotenv interpolation"):
            self.prepare()
        self.assertFalse(self.directory.exists())

    def test_repository_secret_directory_is_rejected(self):
        self.values["BEANFLOW_SECRETS_DIR"] = str(prepare_deployment.ROOT / ".test-secrets")
        with self.assertRaisesRegex(SystemExit, "outside the repository"):
            self.prepare()


class OidcDiscoveryTest(unittest.TestCase):
    def validate(self, body):
        return subprocess.run(
            [sys.executable, str(Path(__file__).with_name("validate-oidc-discovery.py"))],
            input=body, text=True, capture_output=True,
            env={**os.environ,
                 "BEANFLOW_OPERATIONS_OIDC_ISSUER_URI": "https://sso.example.test:5443/realms/beanflow",
                 "BEANFLOW_JWK_SET_URI": "https://sso.example.test:5443/realms/beanflow/protocol/openid-connect/certs"},
            check=False,
        )

    def metadata(self):
        return {
            "issuer": "https://sso.example.test:5443/realms/beanflow",
            "jwks_uri": "https://sso.example.test:5443/realms/beanflow/protocol/openid-connect/certs",
            "code_challenge_methods_supported": ["plain", "S256"],
        }

    def test_matching_public_discovery_is_accepted(self):
        result = self.validate(json.dumps(self.metadata()))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_different_issuer_or_keys_are_rejected(self):
        for field in ("issuer", "jwks_uri"):
            with self.subTest(field=field):
                metadata = self.metadata()
                metadata[field] = "https://other.example.test/untrusted"
                result = self.validate(json.dumps(metadata))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("differs from deployment settings", result.stderr)
                self.assertNotIn("other.example.test", result.stderr)

    def test_missing_or_malformed_pkce_support_is_rejected(self):
        for methods in ([], ["plain"], None, "S256"):
            with self.subTest(methods=methods):
                metadata = self.metadata()
                metadata["code_challenge_methods_supported"] = methods
                result = self.validate(json.dumps(metadata))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("must support PKCE S256", result.stderr)

    def test_non_json_or_non_object_is_rejected_without_echoing_payload(self):
        for body in ("<html>private-proxy-message</html>", "[]", "null"):
            with self.subTest(body=body):
                result = self.validate(body)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("private-proxy-message", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
