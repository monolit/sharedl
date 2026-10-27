"""Загрузчик для ShareDL: yt-dlp -> gallery-dl -> og:image.
Вызывается из Kotlin через Chaquopy. Обновления yt-dlp/gallery-dl берутся из PyPI
как wheel и подключаются через sys.path (без пересборки APK)."""
import glob
import hashlib
import json
import logging
import os
import re
import shlex
import shutil
import sys
import threading
import types
import mimetypes
import urllib.error
import urllib.parse
import urllib.request

HOME = os.environ.get("HOME", ".")
UPD = os.path.join(HOME, "pyupdates")
os.makedirs(UPD, exist_ok=True)

UA = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/126.0 Mobile Safari/537.36")
PKGS = {"yt-dlp": "yt_dlp", "gallery-dl": "gallery_dl"}
IMG_RE = re.compile(r"\.(jpe?g|png|webp|gif|bmp)$", re.I)
SKIP_EXT = {".part", ".ytdl", ".json", ".temp", ".tmp", ".description", ".vtt", ".srt"}
AUDIO_EXT = {".m4a", ".mp3", ".opus", ".ogg", ".flac", ".wav", ".aac", ".mka"}
ANSI = re.compile(r"\x1b\[[0-9;]*m")
NON_FILE = {
    "application/json", "application/ld+json", "application/xhtml+xml", "application/xml",
    "application/javascript", "application/x-javascript", "application/rss+xml",
    "application/atom+xml", "application/vnd.apple.mpegurl", "application/x-mpegurl",
    "application/dash+xml",
}
_GAL = threading.Lock()  # gallery_dl.config глобальный


class Paused(Exception):
    """Загрузка остановлена пользователем (пауза/отмена)."""


_STOP = set()


def request_stop(job):
    _STOP.add(int(job))


def clear_stop(job):
    _STOP.discard(int(job))


def _check(job):
    if job in _STOP:
        raise Paused()


# ---------- самообновление пакетов ----------

def _vkey(v):
    return tuple(int(x) for x in re.findall(r"\d+", v))


def _dist_version(dist):
    try:
        from importlib.metadata import version
        return version(dist)
    except Exception:
        return None


_BUNDLED = {d: _dist_version(d) for d in PKGS}  # версии, вшитые в APK (до подмены sys.path)


def _whl_version(path):
    return os.path.basename(path).split("-")[1]


def _activate():
    for dist, mod in PKGS.items():
        best = None
        for f in glob.glob(os.path.join(UPD, mod + "-*.whl")):
            if _BUNDLED[dist] and _vkey(_whl_version(f)) <= _vkey(_BUNDLED[dist]):
                try:
                    os.remove(f)  # вшитая версия новее (после обновления APK)
                except OSError:
                    pass
                continue
            if best is None or _vkey(_whl_version(f)) > _vkey(_whl_version(best)):
                best = f
        if best and best not in sys.path:
            sys.path.insert(0, best)


_activate()


def _http_proxy(proxy):
    return proxy if proxy and proxy.startswith("http") else None


def _open(url, proxy=None, timeout=30, headers=None):
    handlers = []
    if _http_proxy(proxy):
        handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    h = {"User-Agent": UA}
    h.update(headers or {})
    opener = urllib.request.build_opener(*handlers)
    return opener.open(urllib.request.Request(url, headers=h), timeout=timeout)


def _cur_version(mod):
    import importlib
    return importlib.import_module(mod + ".version").__version__


def _update_one(dist, mod, proxy):
    cur = _cur_version(mod)
    data = json.load(_open(f"https://pypi.org/pypi/{dist}/json", proxy))
    latest = data["info"]["version"]
    if _vkey(latest) <= _vkey(cur):
        return f"{dist} {cur} (актуально)"
    wheel = next(u for u in data["urls"] if u["packagetype"] == "bdist_wheel")
    blob = _open(wheel["url"], proxy, timeout=120).read()
    if hashlib.sha256(blob).hexdigest() != wheel["digests"]["sha256"]:
        raise ValueError("sha256 не совпал")
    path = os.path.join(UPD, wheel["filename"])
    with open(path + ".tmp", "wb") as f:
        f.write(blob)
    os.replace(path + ".tmp", path)
    for old in glob.glob(os.path.join(UPD, mod + "-*.whl")):
        if old != path:
            os.remove(old)
    _activate()
    import importlib
    for name in list(sys.modules):
        if name == mod or name.startswith(mod + "."):
            del sys.modules[name]
    importlib.invalidate_caches()
    return f"{dist} {cur} → {_cur_version(mod)}"


def update_all(proxy=""):
    out = []
    for dist, mod in PKGS.items():
        try:
            out.append(_update_one(dist, mod, proxy))
        except Exception as e:
            out.append(f"{dist}: ошибка {_short(e)}")
    return "; ".join(out)


# ---------- вспомогательное ----------

def _clean(s):
    return ANSI.sub("", str(s)).strip()


def _short(e):
    lines = [l for l in _clean(e).splitlines() if l.strip()]
    return (lines[-1] if lines else type(e).__name__)[:200]


def _collect(d, audio_only=False):
    out = []
    for root, _, files in os.walk(d):
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if ext in SKIP_EXT or (audio_only and ext not in AUDIO_EXT):
                continue
            out.append(os.path.join(root, f))
    return sorted(out)


def _env(o):
    if o.get("tmp"):
        os.environ["TMPDIR"] = o["tmp"]
    libs = o.get("ffmpeg_libs")
    if libs:  # ffmpeg запускается как дочерний процесс — ему нужны его .so
        old = os.environ.get("LD_LIBRARY_PATH", "")
        if libs not in old.split(":"):
            os.environ["LD_LIBRARY_PATH"] = libs + (":" + old if old else "")


# ---------- yt-dlp ----------

class _YLog:
    def __init__(self, cb):
        self.cb = cb
        self.last_error = ""

    def debug(self, m):
        m = _clean(m)
        if m.startswith("[debug]") or (m.startswith("[download]") and "%" in m):
            return
        self.cb.onLog(m[:160])

    info = debug

    def warning(self, m):
        self.cb.onLog("⚠ " + _clean(m)[:160])

    def error(self, m):
        self.last_error = _clean(m)
        self.cb.onLog("✖ " + self.last_error[:200])


def _hook(job, cb):
    seen = {"title": False}

    def h(d):
        _check_ytdlp(job)
        if not seen["title"]:
            t = (d.get("info_dict") or {}).get("title")
            if t:
                seen["title"] = True
                cb.onTitle(str(t))
        if d.get("status") == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            if total:
                cb.onProgress(100.0 * d.get("downloaded_bytes", 0) / total)
    return h


def _check_ytdlp(job):
    if job in _STOP:
        from yt_dlp.utils import DownloadCancelled
        raise DownloadCancelled("paused")


# обложка: центральный квадрат (как в ручной команде пользователя)
# кавычки внутри нужны: yt-dlp делает shlex.split, а ffmpeg должен получить выражения в '...' (запятые)
_COVER_PPA = "ThumbnailsConvertor:-vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\""


def _ytdlp_args(out_dir, o, sponsor=True):
    args = ["-o", os.path.join(out_dir, "%(title).120s.%(ext)s"),
            "--windows-filenames", "--no-mtime", "--no-playlist", "--retries", "5",
            "--continue", "--add-metadata"]
    if o.get("cookies") and os.path.exists(o["cookies"]):
        args += ["--cookies", o["cookies"]]
    if o.get("ffmpeg") and os.path.exists(o["ffmpeg"]):
        args += ["--ffmpeg-location", o["ffmpeg"]]
    if o.get("proxy"):
        args += ["--proxy", o["proxy"]]
    if o.get("music"):
        args += ["-f", "bestaudio/best", "-x", "--audio-format", o.get("audio_fmt", "m4a"),
                 "--audio-quality", "0", "--embed-thumbnail",
                 "--convert-thumbnails", "png", "--ppa", _COVER_PPA]
    else:
        args += ["-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b",
                 "-S", f"res:{o.get('max_h', 1080)}",
                 "--merge-output-format", "mp4",
                 "--embed-thumbnail", "--convert-thumbnails", "jpg"]
    if sponsor:
        args += ["--sponsorblock-remove", "all"]
    if o.get("extra"):
        args += shlex.split(o["extra"])
    return args


def _ytdlp_once(job, url, out_dir, o, cb, sponsor):
    import yt_dlp
    ydl_opts = yt_dlp.parse_options(_ytdlp_args(out_dir, o, sponsor)).ydl_opts
    log = _YLog(cb)
    ydl_opts.update({"logger": log, "progress_hooks": [_hook(job, cb)], "noprogress": True})
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            rc = ydl.download([url])
    except yt_dlp.utils.DownloadCancelled:
        raise Paused()
    return rc, log.last_error


def _ytdlp(job, url, out_dir, o, cb, audio):
    sponsor = o.get("sponsor", True)
    rc, err = _ytdlp_once(job, url, out_dir, o, cb, sponsor)
    if rc and sponsor and re.search(r"sponsorblock|ffprobe", err, re.I):
        cb.onLog("SponsorBlock unavailable (ffprobe/API) - retrying without it")
        rc, err = _ytdlp_once(job, url, out_dir, o, cb, False)
    files = _collect(out_dir, audio_only=audio)
    if rc and not files:
        raise RuntimeError(err or "yt-dlp failed")
    if rc:
        cb.onLog("⚠ finished with warnings: " + err[:100])
    return files


# ---------- gallery-dl ----------

class _LogH(logging.Handler):
    def __init__(self, cb):
        super().__init__(logging.INFO)
        self.cb = cb

    def emit(self, r):
        try:
            self.cb.onLog(_clean(r.getMessage())[:160])
        except Exception:
            pass


def _gallery(url, out_dir, o, cb):
    from gallery_dl import config, job
    with _GAL:
        config.clear()
        config.set(("extractor",), "base-directory", out_dir)
        config.set(("extractor",), "directory", [])  # плоско, без подпапок
        if o.get("cookies") and os.path.exists(o["cookies"]):
            config.set(("extractor",), "cookies", o["cookies"])
        if o.get("proxy"):
            config.set(("extractor",), "proxy", o["proxy"])
        root = logging.getLogger()
        h, old = _LogH(cb), root.level
        root.addHandler(h)
        root.setLevel(logging.INFO)
        try:
            job.DownloadJob(url).run()
        finally:
            root.removeHandler(h)
            root.setLevel(old)


# ---------- прямые ссылки на файлы ----------

def _name_from(cd, url, ctype):
    name = None
    m = re.search(r"filename\*\s*=\s*[^']*'[^']*'([^;]+)", cd or "", re.I)
    if m:
        name = urllib.parse.unquote(m.group(1).strip().strip('"'))
    if not name:
        m = re.search(r'filename\s*=\s*"?([^";]+)"?', cd or "", re.I)
        if m:
            name = m.group(1).strip()
    if not name:
        name = urllib.parse.unquote(os.path.basename(urllib.parse.urlsplit(url).path)) or "file"
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", name).strip(" .")[:150] or "file"
    if not os.path.splitext(name)[1] and ctype:
        ext = mimetypes.guess_extension(ctype)
        if ext:
            name += ext
    return name


def _probe(url, o):
    """Выясняет, ведёт ли ссылка прямо на файл. None — не удалось/не файл."""
    try:
        r = _open(url, o.get("proxy"), 8, {"Range": "bytes=0-0"})
    except Exception:
        return None
    try:
        h = r.headers
        ctype = (h.get("Content-Type") or "").split(";")[0].strip().lower()
        cd = h.get("Content-Disposition") or ""
        is_file = bool(re.search(r"attachment", cd, re.I)) or (
            bool(ctype) and not ctype.startswith("text/") and ctype not in NON_FILE)
        if not is_file:
            return None
        return {"name": _name_from(cd, r.geturl(), ctype), "ctype": ctype}
    finally:
        try:
            r.close()
        except Exception:
            pass


def _fetch(job, url, out_dir, o, cb, probe=None):
    """Скачивание файла с докачкой (.part + Range) и поддержкой паузы."""
    name = (probe or {}).get("name") or _name_from("", url, "")
    cb.onTitle(name)
    path = os.path.join(out_dir, name)
    part = path + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    try:
        r = _open(url, o.get("proxy"), 30, {"Range": f"bytes={have}-"} if have else None)
    except urllib.error.HTTPError as e:
        if e.code == 416 and have:  # уже докачано
            os.replace(part, path)
            return
        raise
    with r:
        length = r.headers.get("Content-Length")
        if getattr(r, "status", 200) == 206:
            mode, total = "ab", (have + int(length) if length else None)
        else:
            mode, have, total = "wb", 0, (int(length) if length else None)
        done = have
        with open(part, mode) as f:
            while True:
                _check(job)
                chunk = r.read(256 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                done += len(chunk)
                if total:
                    cb.onProgress(100.0 * done / total)
    os.replace(part, path)


class _Bar:
    """Подмена tqdm.rich для libdl: прогресс и пауза вместо консольного бара."""

    def __init__(self, job, cb, total):
        self.job, self.cb, self.total, self.done = job, cb, total or 0, 0

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False

    def update(self, n=1):
        self.done += n
        _check(self.job)
        if self.total:
            self.cb.onProgress(100.0 * self.done / self.total)

    def set_description(self, d=None, *a, **k):
        if d:
            self.cb.onTitle(str(d))

    def set_postfix(self, *a, **k):
        pass


def _libdl(job, url, out_dir, o, cb):
    """Экспериментально: libdl (github.com/monolit/libdl) для прямых ссылок."""
    sys.modules.setdefault("tqdm", types.ModuleType("tqdm"))
    shim = types.ModuleType("tqdm.rich")
    shim.trange = lambda *a, **k: _Bar(job, cb, a[0] if a else 0)
    sys.modules["tqdm.rich"] = shim
    import libdl
    libdl.download(url, path=out_dir, headers={"User-Agent": libdl.USERAGENT})


def _file(job, url, out_dir, o, cb, probe=None):
    if o.get("libdl"):
        try:
            cb.onLog("libdl (experimental)")
            return _libdl(job, url, out_dir, o, cb)
        except Paused:
            raise
        except Exception as e:
            cb.onLog("libdl: " + _short(e) + " -> built-in downloader")
    _fetch(job, url, out_dir, o, cb, probe)


def _og_image(url, o):
    import html
    page = _open(url, o.get("proxy")).read(500_000).decode("utf-8", "ignore")
    m = (re.search(r'<meta[^>]+(?:property|name)=["\']og:image["\'][^>]+content=["\']([^"\']+)', page, re.I)
         or re.search(r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+(?:property|name)=["\']og:image["\']', page, re.I))
    return html.unescape(m.group(1)) if m else None


# ---------- точка входа ----------

def download(job, url, out_dir, opts_json, cb):
    """Возвращает JSON-список путей скачанных файлов. Paused — пауза/отмена."""
    job = int(job)
    o = json.loads(opts_json)
    os.makedirs(out_dir, exist_ok=True)
    _env(o)
    url = url.strip()
    music = bool(o.get("music"))
    _check(job)

    if IMG_RE.search(urllib.parse.urlsplit(url).path):
        _file(job, url, out_dir, o, cb)
        files = _collect(out_dir)
    else:
        probe = None if music else _probe(url, o)
        if probe:
            cb.onLog("Direct file link: " + probe["name"])
            _file(job, url, out_dir, o, cb, probe)
            files = _collect(out_dir)
        elif music:
            files = _ytdlp(job, url, out_dir, o, cb, True)  # ошибки пробрасываем наверх
        else:
            files = []
            try:
                files = _ytdlp(job, url, out_dir, o, cb, False)
            except Paused:
                raise
            except Exception as e:
                cb.onLog("yt-dlp: " + _short(e))
            if not files:
                cb.onLog("Trying gallery-dl")
                try:
                    _gallery(url, out_dir, o, cb)
                    files = _collect(out_dir)
                except Exception as e:
                    cb.onLog("gallery-dl: " + _short(e))
            if not files:
                try:
                    og = _og_image(url, o)
                    if og:
                        cb.onLog("Trying og:image")
                        _fetch(job, og, out_dir, o, cb)
                        files = _collect(out_dir)
                except Paused:
                    raise
                except Exception as e:
                    cb.onLog("og:image: " + _short(e))

    if not files:
        raise RuntimeError("Nothing downloaded")
    return json.dumps(files)
