"""Gaplin's pinned yt-dlp Python runner.

Derived from the MIT-licensed yt-dlp-android Java/Python bridge by
ffmpegkit-maintained. CLI-style options are translated explicitly so no
unsupported option is silently discarded.
"""
import json
import os
import tempfile
from importlib.metadata import PackageNotFoundError, version as package_version
import yt_dlp
from yt_dlp.version import __version__ as YT_DLP_VERSION

_JS_RUNTIME_PATH = None
_FFMPEG_PATH = None

_FLAG_MAP = {
    '-f': ('format', 'str'), '--format': ('format', 'str'),
    '--merge-output-format': ('merge_output_format', 'str'),
    '--embed-thumbnail': ('_embed_thumbnail', 'bool'),
    '--embed-metadata': ('_embed_metadata', 'bool'),
    '--convert-thumbnails': ('_convert_thumbnails', 'str'),
    '-x': ('_extract_audio', 'bool'), '--extract-audio': ('_extract_audio', 'bool'),
    '--audio-format': ('_audio_format', 'str'),
    '--audio-quality': ('_audio_quality', 'str'),
    '-o': ('outtmpl', 'str'), '--output': ('outtmpl', 'str'),
    '--cookies': ('cookiefile', 'str'), '--proxy': ('proxy', 'str'),
    '--add-header': ('_header', 'str'), '--user-agent': ('_user_agent', 'str'),
    '--extractor-args': ('_extractor_args', 'extractor_args'),
    '--retries': ('retries', 'int'),
    '--fragment-retries': ('fragment_retries', 'int'),
    '--extractor-retries': ('extractor_retries', 'int'),
    '--file-access-retries': ('file_access_retries', 'int'),
    '--concurrent-fragments': ('concurrent_fragment_downloads', 'int'),
    '--restrict-filenames': ('restrictfilenames', 'bool'),
    '--newline': ('newline', 'bool'),
    '--no-playlist': ('noplaylist', 'bool'),
    '--no-overwrites': ('nooverwrites', 'bool'),
    '--continue': ('continuedl', 'bool'),
    '--no-part': ('nopart', 'bool'),
    '-F': ('listformats', 'bool'),
}


def configure_js_runtime(executable_path, temp_directory):
    """Pin yt-dlp to the verified Android QuickJS executable and private temp directory."""
    global _JS_RUNTIME_PATH
    path = os.path.abspath(str(executable_path))
    temp_path = os.path.abspath(str(temp_directory))
    if not os.path.isfile(path) or not os.access(path, os.X_OK):
        raise RuntimeError("The packaged QuickJS runtime is missing or not executable")
    try:
        ejs_version = package_version("yt-dlp-ejs")
    except PackageNotFoundError as error:
        raise RuntimeError("The yt-dlp-ejs challenge solver package is missing from this APK") from error
    if ejs_version != "0.8.0":
        raise RuntimeError(
            "Incompatible yt-dlp-ejs package: expected 0.8.0, found " + ejs_version
        )
    os.makedirs(temp_path, exist_ok=True)
    if not os.access(temp_path, os.W_OK):
        raise RuntimeError("The private QuickJS temporary directory is not writable")
    os.environ["TMPDIR"] = temp_path
    tempfile.tempdir = temp_path
    _JS_RUNTIME_PATH = path
    return path


def configure_ffmpeg_runtime(executable_path):
    """Use the verified Android FFmpeg executable for yt-dlp merging and extraction."""
    global _FFMPEG_PATH
    path = os.path.abspath(str(executable_path))
    if not os.path.isfile(path) or not os.access(path, os.X_OK):
        raise RuntimeError("The packaged FFmpeg runtime is missing or not executable")
    _FFMPEG_PATH = path
    return path


def _parse_extractor_args(value):
    """Convert one CLI extractor-args value to yt-dlp's nested API structure."""
    ie_key, separator, raw_args = str(value).partition(':')
    if not separator or not ie_key.strip():
        raise ValueError('Invalid --extractor-args value')
    options = {}
    for token in raw_args.split(';') if raw_args else []:
        key, has_value, raw_value = token.partition('=')
        key = key.strip().replace('-', '_')
        if not key:
            continue
        options[key] = [v for v in raw_value.split(',') if v] if has_value else []
    return {ie_key.strip().lower(): options}


def _cli_to_opts(cli_flags):
    opts = {}
    if _JS_RUNTIME_PATH:
        # Python API expects a mapping, not the CLI list representation.
        # Providing only QuickJS disables the unavailable Deno default.
        opts['js_runtimes'] = {'quickjs': {'path': _JS_RUNTIME_PATH}}
    else:
        raise RuntimeError('No supported JavaScript runtime is configured for YouTube extraction')
    if _FFMPEG_PATH:
        opts['ffmpeg_location'] = _FFMPEG_PATH
    else:
        raise RuntimeError('The packaged FFmpeg runtime is not configured')
    flags = list(cli_flags or [])
    i = 0
    while i < len(flags):
        flag = flags[i]
        mapping = _FLAG_MAP.get(flag)
        if mapping is None:
            raise ValueError('Unsupported downloader option: ' + str(flag))
        key, kind = mapping
        if kind == 'bool':
            opts[key] = True
            i += 1
            continue
        if i + 1 >= len(flags):
            raise ValueError('Missing value for downloader option: ' + flag)
        value = flags[i + 1]
        if kind == 'int':
            parsed = int(value)
            if parsed < 1:
                raise ValueError('Downloader retry and concurrency values must be positive')
            opts[key] = parsed
        elif kind == 'extractor_args':
            parsed_args = _parse_extractor_args(value)
            current = opts.setdefault('extractor_args', {})
            for ie_key, options in parsed_args.items():
                current.setdefault(ie_key, {}).update(options)
        elif key == '_header':
            name, separator, header_value = value.partition(':')
            if not separator or not name.strip():
                raise ValueError('Invalid --add-header value')
            opts.setdefault('http_headers', {})[name.strip()] = header_value.strip()
        elif key == '_user_agent':
            opts.setdefault('http_headers', {})['User-Agent'] = value
        else:
            opts[key] = value
        i += 2
    postprocessors = []
    thumbnail_format = opts.pop('_convert_thumbnails', None)
    if thumbnail_format:
        postprocessors.append({
            'key': 'FFmpegThumbnailsConvertor',
            'format': thumbnail_format,
            'when': 'before_dl',
        })

    if opts.pop('_extract_audio', False):
        postprocessors.append({
            'key': 'FFmpegExtractAudio',
            'preferredcodec': opts.pop('_audio_format', None),
            'preferredquality': opts.pop('_audio_quality', None),
            'nopostoverwrites': False,
        })

    if opts.pop('_embed_metadata', False):
        postprocessors.append({
            'key': 'FFmpegMetadata',
            'add_chapters': None,
            'add_metadata': True,
            'add_infojson': None,
        })

    if opts.pop('_embed_thumbnail', False):
        postprocessors.append({
            'key': 'EmbedThumbnail',
            'already_have_thumbnail': False,
        })
        opts['writethumbnail'] = True
        # Match CLI behavior without producing a separate playlist thumbnail.
        opts['_gaplin_suppress_playlist_thumbnail'] = True

    if postprocessors:
        opts.setdefault('postprocessors', []).extend(postprocessors)

    return opts


def _apply_output_template(opts, output_template):
    suppress_playlist_thumbnail = opts.pop('_gaplin_suppress_playlist_thumbnail', False)
    if not output_template:
        if suppress_playlist_thumbnail:
            current_template = opts.get('outtmpl')
            if isinstance(current_template, dict):
                current_template['pl_thumbnail'] = ''
            elif current_template:
                opts['outtmpl'] = {
                    'default': current_template,
                    'pl_thumbnail': '',
                }
        return

    if suppress_playlist_thumbnail:
        opts['outtmpl'] = {
            'default': output_template,
            'pl_thumbnail': '',
        }
    else:
        opts['outtmpl'] = output_template


def _progress_hook(progress_callback):
    if progress_callback is None:
        return []
    def hook(data):
        if data.get('status') == 'downloading':
            try:
                raw_pct = data.get('_percent_str', '0.0%')
                pct = float(str(raw_pct).strip().replace('%', ''))
                eta = int(data.get('eta') or 0)
                line = str(data.get('_default_template') or '')
                progress_callback.onProgressUpdate(pct, eta, line)
            except Exception:
                pass
    return [hook]


def get_version():
    try:
        ejs_version = package_version('yt-dlp-ejs')
    except PackageNotFoundError:
        ejs_version = 'ausente'
    try:
        mutagen_version = package_version('mutagen')
    except PackageNotFoundError:
        mutagen_version = 'ausente'
    return (
        YT_DLP_VERSION
        + ' + yt-dlp-ejs ' + ejs_version
        + ' + mutagen ' + mutagen_version
    )


def execute(url, output_template, cli_flags, progress_callback=None):
    opts = _cli_to_opts(cli_flags)
    _apply_output_template(opts, output_template)
    opts['progress_hooks'] = _progress_hook(progress_callback)
    with yt_dlp.YoutubeDL(opts) as downloader:
        return downloader.download([url])


def execute_debug(url, output_template, cli_flags, log_callback=None, progress_callback=None):
    opts = _cli_to_opts(cli_flags)
    _apply_output_template(opts, output_template)
    opts['progress_hooks'] = _progress_hook(progress_callback)
    if log_callback is not None:
        class Logger:
            def debug(self, message):
                level = 'DEBUG' if str(message).startswith('[debug]') else 'INFO'
                log_callback.onLog(level, str(message))
            def warning(self, message):
                log_callback.onLog('WARNING', str(message))
            def error(self, message):
                log_callback.onLog('ERROR', str(message))
        opts['logger'] = Logger()
    with yt_dlp.YoutubeDL(opts) as downloader:
        return downloader.download([url])


def _summarize_media_info(info):
    """Return safe, non-secret metadata used to decide whether a media item can be downloaded."""
    if not info:
        raise yt_dlp.utils.DownloadError("The source returned no media metadata")

    entries = info.get('entries')
    is_playlist = info.get('_type') in ('playlist', 'multi_video') or entries is not None
    if is_playlist:
        valid_entries = [entry for entry in (entries or []) if entry]
        return {
            'id': str(info.get('id') or ''),
            'title': str(info.get('title') or 'Playlist'),
            'is_playlist': True,
            'entry_count': len(valid_entries),
            'availability': str(info.get('availability') or ''),
            'audio_formats': 0,
            'video_formats': 0,
            'video_heights': [],
            'progressive_formats': 0,
        }

    formats = info.get('formats') or []
    available = [
        item for item in formats
        if item.get('url') and not item.get('has_drm')
    ]
    audio_formats = [
        item for item in available
        if item.get('acodec') not in (None, 'none')
    ]
    video_formats = [
        item for item in available
        if item.get('vcodec') not in (None, 'none')
    ]
    video_heights = sorted({
        int(item.get('height')) for item in video_formats
        if item.get('height') and str(item.get('height')).isdigit()
    })
    progressive_formats = [
        item for item in available
        if item.get('vcodec') not in (None, 'none')
        and item.get('acodec') not in (None, 'none')
    ]

    return {
        'id': str(info.get('id') or ''),
        'title': str(info.get('title') or 'Mídia'),
        'is_playlist': False,
        'entry_count': 1,
        'availability': str(info.get('availability') or ''),
        'audio_formats': len(audio_formats),
        'video_formats': len(video_formats),
        'video_heights': video_heights,
        'progressive_formats': len(progressive_formats),
        'format_count': len(available),
    }


def inspect_media(url, cli_flags=None):
    """
    Validate a media URL without downloading it.
    This surfaces deleted/private/geo-restricted items before they enter the queue.
    """
    opts = _cli_to_opts(cli_flags or [])
    opts.update({
        'quiet': True,
        'no_warnings': True,
        'skip_download': True,
        'ignoreerrors': False,
        'retries': opts.get('retries', 2),
        'extractor_retries': opts.get('extractor_retries', 2),
        'socket_timeout': 20,
    })
    with yt_dlp.YoutubeDL(opts) as downloader:
        info = downloader.extract_info(url, download=False)
    return json.dumps(_summarize_media_info(info), ensure_ascii=False)
