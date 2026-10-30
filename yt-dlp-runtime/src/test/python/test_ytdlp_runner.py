import os
import runpy
import sys
import tempfile
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
        fake_module.utils = types.SimpleNamespace(DownloadError=RuntimeError)
        sys.modules["yt_dlp"] = fake_module
        sys.modules["yt_dlp.version"] = version_module
        cls.runner = runpy.run_path(str(RUNNER))
        handle, cls.fake_qjs = tempfile.mkstemp(prefix="gaplin-qjs-")
        os.close(handle)
        os.chmod(cls.fake_qjs, 0o700)
        cls.fake_temp_dir = tempfile.mkdtemp(prefix="gaplin-qjs-tmp-")
        handle, cls.fake_ffmpeg = tempfile.mkstemp(prefix="gaplin-ffmpeg-")
        os.close(handle)
        os.chmod(cls.fake_ffmpeg, 0o700)
        cls.runner["configure_js_runtime"](cls.fake_qjs, cls.fake_temp_dir)
        cls.runner["configure_ffmpeg_runtime"](cls.fake_ffmpeg)

    def test_quickjs_runtime_is_configured_as_python_api_mapping(self):
        opts = self.runner["_cli_to_opts"]([])
        self.assertEqual(
            opts["js_runtimes"],
            {"quickjs": {"path": self.fake_qjs}},
        )
        self.assertEqual(opts["ffmpeg_location"], self.fake_ffmpeg)

    def test_engine_version_reports_the_pinned_ejs_package(self):
        self.assertIn("yt-dlp-ejs 0.8.0", self.runner["get_version"]())
        self.assertIn("mutagen ", self.runner["get_version"]())

    def test_quickjs_temp_directory_is_private_and_configured(self):
        self.assertEqual(os.environ["TMPDIR"], self.fake_temp_dir)
        self.assertEqual(self.runner["tempfile"].gettempdir(), self.fake_temp_dir)

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

    def test_merge_container_preference_is_mapped(self):
        opts = self.runner["_cli_to_opts"](["--merge-output-format", "mp4/mkv"])
        self.assertEqual(opts["merge_output_format"], "mp4/mkv")

    def test_audio_extraction_flags_build_real_postprocessor(self):
        opts = self.runner["_cli_to_opts"]([
            "-x",
            "--audio-format", "mp3",
            "--audio-quality", "0",
        ])
        extract_audio = next(
            pp for pp in opts["postprocessors"]
            if pp["key"] == "FFmpegExtractAudio"
        )
        self.assertEqual(extract_audio["preferredcodec"], "mp3")
        self.assertEqual(extract_audio["preferredquality"], "0")
        self.assertFalse(extract_audio["nopostoverwrites"])
        self.assertNotIn("extractaudio", opts)
        self.assertNotIn("audioformat", opts)
        self.assertNotIn("audioquality", opts)

    def test_cover_art_and_metadata_flags_build_real_postprocessors(self):
        opts = self.runner["_cli_to_opts"]([
            "-x",
            "--audio-format", "mp3",
            "--audio-quality", "0",
            "--embed-thumbnail",
            "--embed-metadata",
            "--convert-thumbnails", "jpg",
        ])

        self.assertTrue(opts["writethumbnail"])
        postprocessors = opts["postprocessors"]
        self.assertEqual(
            [item["key"] for item in postprocessors],
            [
                "FFmpegThumbnailsConvertor",
                "FFmpegExtractAudio",
                "FFmpegMetadata",
                "EmbedThumbnail",
            ],
        )
        self.assertEqual(postprocessors[0]["format"], "jpg")
        self.assertEqual(postprocessors[0]["when"], "before_dl")
        self.assertEqual(postprocessors[1]["preferredcodec"], "mp3")
        self.assertEqual(postprocessors[1]["preferredquality"], "0")
        self.assertTrue(postprocessors[2]["add_metadata"])
        self.assertEqual(postprocessors[3]["already_have_thumbnail"], False)
        self.assertNotIn("embedthumbnail", opts)
        self.assertNotIn("addmetadata", opts)
        self.assertNotIn("convertthumbnails", opts)
        self.assertNotIn("extractaudio", opts)

    def test_cover_art_output_template_suppresses_separate_playlist_thumbnail(self):
        opts = self.runner["_cli_to_opts"](["--embed-thumbnail"])
        self.runner["_apply_output_template"](opts, "/tmp/gaplin/%(title)s [%(id)s].%(ext)s")
        self.assertEqual(
            opts["outtmpl"],
            {
                "default": "/tmp/gaplin/%(title)s [%(id)s].%(ext)s",
                "pl_thumbnail": "",
            },
        )
        self.assertNotIn("_gaplin_suppress_playlist_thumbnail", opts)

    def test_execute_debug_applies_thumbnail_safe_output_template(self):
        captured = {}
        original = self.runner["yt_dlp"].YoutubeDL

        class FakeYoutubeDL:
            def __init__(self, options):
                captured.update(options)

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, traceback):
                return False

            def download(self, urls):
                return 0

        try:
            self.runner["yt_dlp"].YoutubeDL = FakeYoutubeDL
            self.runner["execute_debug"](
                "https://example.com/media",
                "/tmp/gaplin/%(title)s [%(id)s].%(ext)s",
                ["--embed-thumbnail"],
            )
        finally:
            self.runner["yt_dlp"].YoutubeDL = original

        self.assertTrue(captured["writethumbnail"])
        self.assertEqual(
            captured["outtmpl"],
            {
                "default": "/tmp/gaplin/%(title)s [%(id)s].%(ext)s",
                "pl_thumbnail": "",
            },
        )

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

    def test_media_summary_detects_audio_only_and_muxed_formats(self):
        summary = self.runner["_summarize_media_info"]({
            "id": "video123",
            "title": "Allowed sample",
            "formats": [
                {"format_id": "140", "url": "https://cdn.example/audio", "acodec": "mp4a.40.2", "vcodec": "none"},
                {"format_id": "18", "url": "https://cdn.example/muxed", "acodec": "mp4a.40.2", "vcodec": "avc1.42001E"},
                {"format_id": "137", "url": "https://cdn.example/video", "acodec": "none", "vcodec": "avc1.640028", "height": 1080},
            ],
        })
        self.assertEqual(summary["id"], "video123")
        self.assertEqual(summary["audio_formats"], 2)
        self.assertEqual(summary["progressive_formats"], 1)
        self.assertEqual(summary["video_formats"], 2)
        self.assertEqual(summary["video_heights"], [1080])
        self.assertFalse(summary["is_playlist"])

    def test_media_summary_excludes_drm_and_unavailable_formats(self):
        summary = self.runner["_summarize_media_info"]({
            "id": "video456",
            "formats": [
                {"format_id": "drm", "url": "https://cdn.example/drm", "acodec": "aac", "vcodec": "avc1", "has_drm": True},
                {"format_id": "no-url", "acodec": "aac", "vcodec": "none"},
            ],
        })
        self.assertEqual(summary["audio_formats"], 0)
        self.assertEqual(summary["progressive_formats"], 0)
        self.assertEqual(summary["format_count"], 0)
        self.assertEqual(summary["video_formats"], 0)
        self.assertEqual(summary["video_heights"], [])

    def test_playlist_summary_reports_only_accessible_entries(self):
        summary = self.runner["_summarize_media_info"]({
            "_type": "playlist",
            "id": "playlist123",
            "title": "Playlist",
            "entries": [{"id": "one"}, None, {"id": "two"}],
        })
        self.assertTrue(summary["is_playlist"])
        self.assertEqual(summary["entry_count"], 2)

    def test_invalid_retry_values_are_rejected(self):
        with self.assertRaises(ValueError):
            self.runner["_cli_to_opts"](["--retries", "0"])


if __name__ == "__main__":
    unittest.main()
