import runpy
import sys
import types
import unittest
from pathlib import Path

RUNNER = Path(__file__).parents[2] / "main" / "python" / "ytdlp_runner.py"


class YtDlpRunnerOptionsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fake_module = types.ModuleType("yt_dlp")
        fake_module.YoutubeDL = object
        version_module = types.ModuleType("yt_dlp.version")
        version_module.__version__ = "test-version"
        sys.modules["yt_dlp"] = fake_module
        sys.modules["yt_dlp.version"] = version_module
        cls.runner = runpy.run_path(str(RUNNER))

    def test_retry_and_format_options_are_mapped(self):
        opts = self.runner["_cli_to_opts"]([
            "-f", "bestaudio",
            "--retries", "5",
            "--fragment-retries", "5",
            "--extractor-retries", "3",
            "--file-access-retries", "3",
            "--concurrent-fragments", "1",
            "--newline",
            "--no-overwrites",
        ])
        self.assertEqual(opts["format"], "bestaudio")
        self.assertEqual(opts["retries"], 5)
        self.assertEqual(opts["fragment_retries"], 5)
        self.assertEqual(opts["extractor_retries"], 3)
        self.assertEqual(opts["file_access_retries"], 3)
        self.assertEqual(opts["concurrent_fragment_downloads"], 1)
        self.assertTrue(opts["newline"])
        self.assertTrue(opts["nooverwrites"])

    def test_extractor_args_are_converted_to_python_structure(self):
        opts = self.runner["_cli_to_opts"]([
            "--extractor-args",
            "youtube:player_client=android;formats=incomplete",
        ])
        self.assertEqual(
            opts["extractor_args"],
            {"youtube": {"player_client": ["android"], "formats": ["incomplete"]}},
        )

    def test_unknown_options_are_not_silently_dropped(self):
        with self.assertRaisesRegex(ValueError, "Unsupported downloader option"):
            self.runner["_cli_to_opts"](["--made-up-option", "value"])

    def test_invalid_retry_values_are_rejected(self):
        with self.assertRaises(ValueError):
            self.runner["_cli_to_opts"](["--retries", "0"])


if __name__ == "__main__":
    unittest.main()
