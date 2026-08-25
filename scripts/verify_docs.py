#!/usr/bin/env python3

from pathlib import Path
import unittest

from docs_validation.main import main


def run_characterization_tests() -> bool:
    suite = unittest.defaultTestLoader.discover(
        str(Path(__file__).resolve().parent / "tests"),
        pattern="test_*.py",
    )
    return unittest.TextTestRunner(verbosity=1).run(suite).wasSuccessful()


if __name__ == "__main__":
    if not run_characterization_tests():
        raise SystemExit(1)
    raise SystemExit(main())
