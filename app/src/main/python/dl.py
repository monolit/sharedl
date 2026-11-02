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
PKGS = {"yt-dlp": "yt_dlp", "gallery-dl": "gallery_dl", "yt-dlp-ejs": "yt_dlp_ejs"}
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


def _jar(path):
    if not path or not os.path.exists(path):
        return None
    import http.cookiejar
    jar = http.cookiejar.MozillaCookieJar()
    try:
        jar.load(path, ignore_discard=True, ignore_expires=True)
    except Exception:
        return None
    return jar


def _open(url, proxy=None, timeout=30, headers=None, cookies=None):
    handlers = []
    if _http_proxy(proxy):
        handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    jar = _jar(cookies)
    if jar is not None:
        handlers.append(urllib.request.HTTPCookieProcessor(jar))
    h = {"User-Agent": UA}
    h.update(headers or {})
    opener = urllib.request.build_opener(*handlers)
    return opener.open(urllib.request.Request(url, headers=h), timeout=timeout)


def _cur_version(mod):
    import importlib
    try:
        return importlib.import_module(mod + ".version").__version__
    except Exception:
        m = importlib.import_module(mod)
        return str(getattr(m, "version", None) or getattr(m, "__version__", "0"))


_PIN_RE = re.compile(r"yt-dlp-ejs==([0-9][0-9.]*)")


def _update_one(dist, mod, proxy, want=None):
    """want — точная версия (для yt-dlp-ejs: ту, что требует свежий yt-dlp). Возвращает (сообщение, requires_dist)."""
    cur = _cur_version(mod)
    url = f"https://pypi.org/pypi/{dist}/{want}/json" if want else f"https://pypi.org/pypi/{dist}/json"
    data = json.load(_open(url, proxy))
    target = data["info"]["version"]
    req = data["info"].get("requires_dist") or []
    if _vkey(target) <= _vkey(cur):
        return f"{dist} {cur} (актуально)", req
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
    return f"{dist} {cur} → {_cur_version(mod)}", req


def update_all(proxy=""):
    out, want = [], None
    for dist, mod in PKGS.items():  # yt-dlp раньше yt-dlp-ejs: оттуда берём нужную версию решателя
        try:
            msg, req = _update_one(dist, mod, proxy, want if dist == "yt-dlp-ejs" else None)
            out.append(msg)
            if dist == "yt-dlp":
                m = next((_PIN_RE.search(r) for r in req if _PIN_RE.search(r)), None)
                want = m.group(1) if m else None
        except Exception as e:
            out.append(f"{dist}: ошибка {_short(e)}")
    return "; ".join(out)


# ---------- вспомогательное ----------

def _clean(s):
    return ANSI.sub("", str(s)).strip()


def _short(e):
    lines = [l for l in _clean(e).splitlines() if l.strip()]
    text = lines[-1] if lines else ""
    if not text or text == "None":
        text = type(e).__name__ + (" (no extractor for this URL)" if type(e).__name__ == "NoExtractorError" else "")
    return text[:200]


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


# ---------- JS-движок для YouTube (WebView через мост из Kotlin) ----------

_BRIDGE = None


def set_js_bridge(bridge):
    global _BRIDGE
    _BRIDGE = bridge


def _register_jcp():
    """Регистрирует провайдер решения n/sig-челленджей, который гонит скрипт через WebView.
    Без JS-движка YouTube отдаёт только картинки/урезанные форматы."""
    if _BRIDGE is None:
        return
    from yt_dlp.extractor.youtube.jsc._registry import _jsc_providers
    if "WebView" in _jsc_providers.value:
        return
    from yt_dlp.extractor.youtube.jsc._builtin.ejs import EJSBaseJCP
    from yt_dlp.extractor.youtube.jsc.provider import (
        JsChallengeProviderError, register_preference, register_provider)

    @register_provider
    class WebViewJCP(EJSBaseJCP):
        PROVIDER_NAME = "webview"
        JS_RUNTIME_NAME = "webview"
        PROVIDER_VERSION = "1.0"
        BUG_REPORT_LOCATION = "https://github.com/monolit/sharedl"

        def is_available(self):
            return self._available and _BRIDGE is not None

        def _construct_stdin(self, player, preprocessed, requests, /):
            code = super()._construct_stdin(player, preprocessed, requests)
            return code.replace('"output_preprocessed": true', '"output_preprocessed": false')

        def _run_js_runtime(self, stdin, /):
            out = str(_BRIDGE.eval(stdin))
            text, sep, err = out.partition("\n#ERR#")
            if not text.strip() or text.strip() == "null":
                msg = err.strip()[:300] if sep else "no output from WebView"
                raise JsChallengeProviderError("JS error: " + msg)
            return text

    @register_preference(WebViewJCP)
    def _pref(provider, requests):
        return 1000


# ---------- yt-dlp ----------

class _YLog:
    def __init__(self, cb):
        self.cb = cb
        self.last_error = ""
        self.redirect = ""

    def debug(self, m):
        m = _clean(m)
        mr = re.search(r"Following redirect to (\S+)", m)
        if mr:
            self.redirect = mr.group(1)
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
    try:
        _register_jcp()
    except Exception as e:
        cb.onLog("JS provider: " + _short(e))
    ydl_opts = yt_dlp.parse_options(_ytdlp_args(out_dir, o, sponsor)).ydl_opts
    log = _YLog(cb)
    ydl_opts.update({"logger": log, "progress_hooks": [_hook(job, cb)], "noprogress": True})
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            rc = ydl.download([url])
    except yt_dlp.utils.DownloadCancelled:
        raise Paused()
    return rc, log.last_error, log.redirect


def _ytdlp(job, url, out_dir, o, cb, audio, info=None):
    info = {} if info is None else info
    sponsor = o.get("sponsor", True)
    rc, err, redirect = _ytdlp_once(job, url, out_dir, o, cb, sponsor)
    if rc and sponsor and re.search(r"sponsorblock|ffprobe", err, re.I):
        cb.onLog("SponsorBlock unavailable (ffprobe/API) - retrying without it")
        rc, err, redirect = _ytdlp_once(job, url, out_dir, o, cb, False)
    info["redirect"] = redirect
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
            st = job.DownloadJob(url).run()
            if st:
                cb.onLog(f"gallery-dl finished with status {st}")
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
        r = _open(url, o.get("proxy"), 8, {"Range": "bytes=0-0"}, o.get("cookies"))
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


def _fetch(job, url, out_dir, o, cb, probe=None, name=None, default_ext=None):
    """Скачивание файла с докачкой (.part + Range) и поддержкой паузы."""
    name = name or (probe or {}).get("name") or _name_from("", url, "")
    if default_ext and not os.path.splitext(name)[1]:
        name += default_ext
    cb.onTitle(name)
    path = os.path.join(out_dir, name)
    part = path + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    try:
        r = _open(url, o.get("proxy"), 30, {"Range": f"bytes={have}-"} if have else None, o.get("cookies"))
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


# ---------- запросы страниц через сетевой слой yt-dlp ----------

def _page_get(url, o, limit=1_500_000, headers=None):
    """GET как у экстракторов yt-dlp (его заголовки + cookies). -> (итоговый_url, текст)."""
    try:
        import yt_dlp
        from yt_dlp.networking import Request
        params = {"quiet": True, "no_warnings": True}
        if o.get("cookies") and os.path.exists(o["cookies"]):
            params["cookiefile"] = o["cookies"]
        if o.get("proxy"):
            params["proxy"] = o["proxy"]
        with yt_dlp.YoutubeDL(params) as ydl:
            r = ydl.urlopen(Request(url, headers=headers or {}))
            try:
                body = r.read(limit) if limit else b""
            finally:
                r.close()
            return r.url, body.decode("utf-8", "ignore")
    except Exception:
        r = _open(url, o.get("proxy"), 20, headers, o.get("cookies"))
        try:
            body = r.read(limit) if limit else b""
            return r.geturl(), body.decode("utf-8", "ignore")
        finally:
            r.close()


# ---------- короткие/«share»-ссылки ----------

def _needs_resolve(url):
    p = urllib.parse.urlsplit(url)
    host, path = (p.hostname or "").lower(), p.path
    return ((host.endswith("facebook.com") and path.startswith("/share/"))
            or host in ("fb.watch", "fb.me", "t.co", "redd.it")
            or (host.endswith("reddit.com") and re.match(r"^/(?:r|u|user)/[^/]+/s/[A-Za-z0-9]+", path) is not None)
            or ((host.endswith("threads.com") or host.endswith("threads.net")) and path.startswith("/share")))


def _resolve(url, o):
    """Идёт по редиректам и возвращает итоговый адрес (если не упёрлись в страницу входа)."""
    try:
        final, _ = _page_get(url, o, limit=0)
    except urllib.error.HTTPError as e:
        final = e.geturl() or url
    except Exception:
        return url
    if re.search(r"/login|/checkpoint|/accounts/login", final):
        return url
    return final


# ---------- посты YouTube с картинками ----------

YT_POST = re.compile(r"youtube\.com/(?:post/|[^?#]*community\?[^#]*\blb=)", re.I)


def _yt_initial_data(page):
    i = page.find("ytInitialData")
    while i != -1:
        j = page.find("{", i)
        if j == -1:
            return None
        try:
            return json.JSONDecoder().raw_decode(page[j:])[0]
        except ValueError:
            i = page.find("ytInitialData", i + 1)
    return None


def _collect_images(node, in_img=False, out=None):
    """Самые крупные превью из *ImageRenderer (backstageImageRenderer и т.п.); аватары автора не трогаем."""
    out = [] if out is None else out
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "thumbnails" and in_img and isinstance(v, list):
                best = max((t for t in v if isinstance(t, dict) and t.get("url")),
                           key=lambda t: (t.get("width") or 0) * (t.get("height") or 0), default=None)
                if best:
                    out.append(best["url"])
            else:
                _collect_images(v, in_img or k.endswith("ImageRenderer"), out)
    elif isinstance(node, list):
        for x in node:
            _collect_images(x, in_img, out)
    return out


def _youtube_post(job, url, out_dir, o, cb):
    _, page = _page_get(url, o, 3_000_000, {"Accept-Language": "en-US,en;q=0.9"})
    data = _yt_initial_data(page)
    if not data:
        return []
    imgs = []
    for u in _collect_images(data):
        u = re.sub(r"=[sw]\d+[^/]*$", "=s0", u)  # оригинальный размер
        if u not in imgs:
            imgs.append(u)
    for i, u in enumerate(imgs, 1):
        cb.onLog(f"YouTube post image {i}/{len(imgs)}")
        _fetch(job, u, out_dir, o, cb, name=f"youtube_post_{i}.jpg")
    return _collect(out_dir)


# ---------- og:video / og:image как последний вариант ----------

def _og_media(url, o):
    import html
    _, page = _page_get(url, o, 1_500_000)
    vids, imgs, tw = [], [], []
    for tag in re.findall(r"<meta\b[^>]*>", page, re.I):
        pm = re.search(r'(?:property|name)=["\']([^"\']+)["\']', tag, re.I)
        cm = re.search(r'content=["\']([^"\']*)["\']', tag, re.I)
        if not pm or not cm:
            continue
        key, val = pm.group(1).lower(), html.unescape(cm.group(1))
        if not val.startswith("http"):
            continue
        if key in ("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream"):
            vids.append(val)
        elif key in ("og:image", "og:image:url", "og:image:secure_url"):
            imgs.append(val)
        elif key in ("twitter:image", "twitter:image:src"):
            tw.append(val)
    media = vids[:1] or imgs or tw
    return list(dict.fromkeys(media))


# ---------- Threads ----------

def _is_threads(url):
    host = (urllib.parse.urlsplit(url).hostname or "").lower()
    return host.endswith("threads.com") or host.endswith("threads.net")


def _best_url(cands):
    c = [x for x in cands if isinstance(x, dict) and x.get("url")]
    return max(c, key=lambda x: (x.get("width") or 0) * (x.get("height") or 0))["url"] if c else None


def _post_media(post):
    """[(url, ext)] из поста Threads/Instagram-формата: видео приоритетнее картинки, карусель — по элементам."""
    out = []
    for it in (post.get("carousel_media") or [post]):
        vv = it.get("video_versions")
        if vv:
            u = _best_url(vv)
            if u:
                out.append((u, ".mp4"))
                continue
        u = _best_url((it.get("image_versions2") or {}).get("candidates") or [])
        if u:
            out.append((u, ".jpg"))
    return out


def _find_post(node, code):
    if isinstance(node, dict):
        if node.get("code") == code and any(k in node for k in ("image_versions2", "video_versions", "carousel_media")):
            if _post_media(node):
                return node
        for v in node.values():
            r = _find_post(v, code)
            if r:
                return r
    elif isinstance(node, list):
        for v in node:
            r = _find_post(v, code)
            if r:
                return r
    return None


def _threads_post(job, url, out_dir, o, cb):
    m = re.search(r"/post/([A-Za-z0-9_-]+)", urllib.parse.urlsplit(url).path)
    if not m:
        return []
    code = m.group(1)
    final, page = _page_get(url, o, 4_000_000, {"Accept-Language": "en-US,en;q=0.9"})
    if re.search(r"/login", final):
        cb.onLog("Threads asks to log in - sign in via browser (Settings -> Cookies)")
        return []
    post = None
    for blob in re.findall(r'<script[^>]*type="application/json"[^>]*>(.*?)</script>', page, re.S):
        try:
            data = json.loads(blob)
        except ValueError:
            continue
        post = _find_post(data, code)
        if post:
            break
    if not post:
        cb.onLog("Threads: post data not found in page")
        return []
    media = _post_media(post)
    for i, (u, ext) in enumerate(media, 1):
        cb.onLog(f"Threads media {i}/{len(media)}")
        _fetch(job, u, out_dir, o, cb, name=f"threads_{code}_{i}{ext}")
    return _collect(out_dir)


# ---------- маршрутизация ----------

def _gallery_first(url):
    """gallery-dl знает ссылку, а yt-dlp — нет (только общий Generic): сразу идём в gallery-dl."""
    try:
        from gallery_dl import extractor as gx
        if gx.find(url) is None:
            return False
        from yt_dlp.extractor import gen_extractor_classes
        return not any(c.suitable(url) for c in gen_extractor_classes() if c.ie_key() != "Generic")
    except Exception:
        return False


def _blocked_hint(url, info, o, cb):
    """Сайт ответил 401/403/429 или «Blocked»: чаще всего анонимные запросы с VPN/дата-центра режутся."""
    errs = " ".join(info.get("errors", []))
    if not re.search(r"\b(401|403|429)\b|blocked|log in|login", errs, re.I):
        return
    host = (urllib.parse.urlsplit(url).hostname or "this site").replace("www.", "")
    if o.get("cookies") and os.path.exists(o["cookies"]):
        have = host in open(o["cookies"], encoding="utf-8", errors="ignore").read()
    else:
        have = False
    cb.onLog(f"⚠ {host} blocked the request. "
             + ("Cookies for it look stale - sign in again via browser (Settings -> Cookies). "
                if have else "Sign in via browser (Settings -> Cookies -> the site chip) to get cookies. ")
             + "If a VPN is on, try excluding ShareDL from it or set a proxy with a clean IP.")


def _chain(job, url, out_dir, o, cb, steps, info):
    files = []
    for step in steps:
        try:
            if step == "ytdlp":
                files = _ytdlp(job, url, out_dir, o, cb, False, info)
            else:
                cb.onLog("gallery-dl")
                _gallery(url, out_dir, o, cb)
                files = _collect(out_dir)
        except Paused:
            raise
        except Exception as e:
            cb.onLog(f"{'yt-dlp' if step == 'ytdlp' else 'gallery-dl'}: " + _short(e))
            info.setdefault("errors", []).append(str(e))
        if files:
            break
    return files


def _og_step(job, url, out_dir, o, cb, info=None):
    try:
        media = _og_media(url, o)
        host = (urllib.parse.urlsplit(url).hostname or "page").replace("www.", "")
        for i, u in enumerate(media, 1):
            cb.onLog(f"og:media {i}/{len(media)}")
            ext = ".mp4" if re.search(r"\.mp4(\?|$)", u) else ".jpg"
            _fetch(job, u, out_dir, o, cb, name=f"{host}_{i}{ext}")
        return _collect(out_dir)
    except Paused:
        raise
    except Exception as e:
        cb.onLog("og:media: " + _short(e))
        if info is not None:
            info.setdefault("errors", []).append(str(e))
        return []


def _page(job, url, out_dir, o, cb):
    if _needs_resolve(url):
        final = _resolve(url, o)
        if final != url:
            cb.onLog("Resolved: " + final)
            url = final
        else:
            cb.onLog("Share link not resolved here - letting yt-dlp follow redirects")
    files = []
    if _is_threads(url):
        try:
            files = _threads_post(job, url, out_dir, o, cb)
        except Paused:
            raise
        except Exception as e:
            cb.onLog("threads: " + _short(e))
    if not files and YT_POST.search(url):
        try:
            files = _youtube_post(job, url, out_dir, o, cb)
        except Paused:
            raise
        except Exception as e:
            cb.onLog("youtube post: " + _short(e))
    info = {}
    if not files:
        steps = ["gallery", "ytdlp"] if _gallery_first(url) else ["ytdlp", "gallery"]
        files = _chain(job, url, out_dir, o, cb, steps, info)
    # yt-dlp сам прошёл по редиректу (например, facebook.com/share/... -> пост): пробуем gallery-dl и og уже по нему
    target = info.get("redirect") or ""
    if not files and target and target != url:
        cb.onLog("Retrying with redirect target")
        files = _chain(job, target, out_dir, o, cb, ["gallery"], info)
        if not files:
            files = _og_step(job, target, out_dir, o, cb, info)
    if not files:
        files = _og_step(job, url, out_dir, o, cb, info)
    if not files:
        _blocked_hint(url, info, o, cb)
    return files


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
            files = _page(job, url, out_dir, o, cb)

    if not files:
        raise RuntimeError("Nothing downloaded")
    return json.dumps(files)
