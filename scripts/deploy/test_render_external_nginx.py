import unittest

from render_external_nginx import render


class ExternalNginxTest(unittest.TestCase):
    def setUp(self):
        self.values = {
            "BEANFLOW_PUBLIC_ORIGIN": "https://app.example.test:5443",
            "BEANFLOW_AISTOR_PUBLIC_ENDPOINT": "https://app.example.test:5443/",
            "BEANFLOW_AISTOR_ENDPOINT": "http://aistor:9000",
            "BEANFLOW_AISTOR_BUCKET": "beanflow.staging",
        }

    def test_same_origin_renders_specific_bucket_and_signing_authority(self):
        config = render(self.values)
        self.assertIn(r"^/beanflow\.staging/(stores|menus|campaigns)/", config)
        self.assertIn("Host app.example.test:5443;", config)
        self.assertIn("set $beanflow_aistor_upstream http://aistor:9000;", config)
        self.assertNotIn("${", config)

    def test_default_https_port_is_canonicalized(self):
        self.values["BEANFLOW_PUBLIC_ORIGIN"] = "https://app.example.test"
        self.values["BEANFLOW_AISTOR_PUBLIC_ENDPOINT"] = "https://app.example.test:443"
        self.assertIn("Host app.example.test;", render(self.values))

    def test_separate_origin_needs_no_local_media_route(self):
        self.values["BEANFLOW_AISTOR_PUBLIC_ENDPOINT"] = "https://objects.example.test"
        self.assertNotIn("$beanflow_aistor_upstream", render(self.values))

    def test_invalid_proxy_inputs_are_rejected(self):
        for key, value in (
            ("BEANFLOW_AISTOR_ENDPOINT", ""),
            ("BEANFLOW_AISTOR_ENDPOINT", "http://user:secret@aistor:9000"),
            ("BEANFLOW_AISTOR_ENDPOINT", "http://aistor:9000/prefix"),
            ("BEANFLOW_AISTOR_ENDPOINT", "http://aistor:99999"),
            ("BEANFLOW_AISTOR_ENDPOINT", "http://aistor;include"),
            ("BEANFLOW_AISTOR_ENDPOINT", "http://aistor\n"),
            ("BEANFLOW_AISTOR_PUBLIC_ENDPOINT", "https://app.example.test?secret=value"),
            ("BEANFLOW_AISTOR_BUCKET", "beanflow/(.*)"),
            ("BEANFLOW_AISTOR_BUCKET", "api"),
            ("BEANFLOW_AISTOR_BUCKET", ""),
            ("BEANFLOW_AISTOR_ENDPOINT", "https://app.example.test:5443"),
        ):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                render({**self.values, key: value})


if __name__ == "__main__":
    unittest.main()
