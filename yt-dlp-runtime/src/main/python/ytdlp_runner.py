"""Gaplin's pinned yt-dlp Python runner.

Derived from the MIT-licensed yt-dlp-android Java/Python bridge by
ffmpegkit-maintained. CLI-style options are translated explicitly so no
unsupported option is silently discarded.
"""
import yt_dlp
from yt_dlp.version import __version__ as YT_DLP_VERSION

_FLAG_MAP = {
    '-f': ('format', 'str'), '--format': ('format', 'str'),
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
    return opts


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
    return YT_DLP_VERSION


def execute(url, output_template, cli_flags, progress_callback=None):
    opts = _cli_to_opts(cli_flags)
    if output_template:
        opts['outtmpl'] = output_template
    opts['progress_hooks'] = _progress_hook(progress_callback)
    with yt_dlp.YoutubeDL(opts) as downloader:
        return downloader.download([url])


def execute_debug(url, output_template, cli_flags, log_callback=None, progress_callback=None):
    opts = _cli_to_opts(cli_flags)
    if output_template:
        opts['outtmpl'] = output_template
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
