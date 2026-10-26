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


def _open(url, proxy=None, timeout=30):
    handlers = []
    if _http_proxy(proxy):
        handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    opener = urllib.request.build_opener(*handlers)
    return opener.open(urllib.request.Request(url, headers={"User-Agent": UA}), timeout=timeout)


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

    def debug(self, m):
        m = _clean(m)
        if m.startswith("[debug]") or (m.startswith("[download]") and "%" in m):
            return
        self.cb.onLog(m[:160])

    info = debug

    def warning(self, m):
        self.cb.onLog("⚠ " + _clean(m)[:160])

    def error(self, m):
        self.cb.onLog("✖ " + _clean(m)[:200])


def _hook(cb):
    def h(d):
        if d.get("status") == "downloading":
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            if total:
                cb.onProgress(100.0 * d.get("downloaded_bytes", 0) / total)
    return h


def _ytdlp_args(out_dir, o):
    args = ["-o", os.path.join(out_dir, "%(title).100s [%(id)s].%(ext)s"),
            "--windows-filenames", "--no-mtime", "--no-playlist", "--retries", "5"]
    if o.get("cookies") and os.path.exists(o["cookies"]):
        args += ["--cookies", o["cookies"]]
    if o.get("ffmpeg") and os.path.exists(o["ffmpeg"]):
        args += ["--ffmpeg-location", o["ffmpeg"]]
    if o.get("proxy"):
        args += ["--proxy", o["proxy"]]
    if o.get("music"):
        args += ["-f", "bestaudio/best", "-x", "--audio-format", o.get("audio_fmt", "m4a"),
                 "--audio-quality", "0", "--embed-metadata", "--embed-thumbnail",
                 "--convert-thumbnails", "jpg"]
    else:
        args += ["-f", "bv*+ba/b", "-S", f"res:{o.get('max_h', 1080)},ext:mp4:m4a",
                 "--merge-output-format", "mp4"]
    if o.get("extra"):
        args += shlex.split(o["extra"])
    return args


def _ytdlp(url, out_dir, o, cb):
    import yt_dlp
    ydl_opts = yt_dlp.parse_options(_ytdlp_args(out_dir, o)).ydl_opts
    ydl_opts.update({"logger": _YLog(cb), "progress_hooks": [_hook(cb)], "noprogress": True})
    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        ydl.download([url])


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


# ---------- прямые картинки / og:image ----------

def _direct(url, out_dir, o):
    r = _open(url, o.get("proxy"))
    ct = r.headers.get("Content-Type", "")
    ext = "png" if "png" in ct else "webp" if "webp" in ct else "gif" if "gif" in ct else "jpg"
    name = os.path.splitext(os.path.basename(urllib.parse.urlsplit(url).path))[0][:80] or "image"
    with open(os.path.join(out_dir, f"{name}.{ext}"), "wb") as f:
        shutil.copyfileobj(r, f)


def _og_image(url, o):
    import html
    page = _open(url, o.get("proxy")).read(500_000).decode("utf-8", "ignore")
    m = (re.search(r'<meta[^>]+(?:property|name)=["\']og:image["\'][^>]+content=["\']([^"\']+)', page, re.I)
         or re.search(r'<meta[^>]+content=["\']([^"\']+)["\'][^>]+(?:property|name)=["\']og:image["\']', page, re.I))
    return html.unescape(m.group(1)) if m else None


# ---------- точка входа ----------

def download(url, out_dir, opts_json, cb):
    """Возвращает JSON-список путей скачанных файлов."""
    o = json.loads(opts_json)
    os.makedirs(out_dir, exist_ok=True)
    _env(o)
    url = url.strip()
    music = bool(o.get("music"))

    if IMG_RE.search(urllib.parse.urlsplit(url).path):
        _direct(url, out_dir, o)
        files = _collect(out_dir)
    elif music:
        _ytdlp(url, out_dir, o, cb)  # ошибки пробрасываем наверх
        files = _collect(out_dir, audio_only=True)
    else:
        files = []
        try:
            _ytdlp(url, out_dir, o, cb)
            files = _collect(out_dir)
        except Exception as e:
            cb.onLog("yt-dlp: " + _short(e))
        if not files:
            cb.onLog("Пробую gallery-dl")
            try:
                _gallery(url, out_dir, o, cb)
                files = _collect(out_dir)
            except Exception as e:
                cb.onLog("gallery-dl: " + _short(e))
        if not files:
            try:
                og = _og_image(url, o)
                if og:
                    cb.onLog("Пробую og:image")
                    _direct(og, out_dir, o)
                    files = _collect(out_dir)
            except Exception as e:
                cb.onLog("og:image: " + _short(e))

    if not files:
        raise RuntimeError("Ничего не скачано")
    return json.dumps(files)
