# ShareDL

Android: поделитесь ссылкой — файл скачается сразу (yt-dlp + gallery-dl на Chaquopy, ffmpeg из youtubedl-android).

## Сборка
Android Studio → Open → Run, либо GitHub Actions (`.github/workflows/build.yml`).
На машине сборки нужен Python 3.13 (как в `app/build.gradle.kts`).

## Подпись релиза через GitHub Secrets
```bash
keytool -genkeypair -v -keystore release.jks -alias sharedl -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks     # результат → секрет KEYSTORE_BASE64
```
Секреты репозитория: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Без секретов release собирается отладочным ключом. Храните release.jks в надёжном месте:
обновления можно ставить только поверх APK, подписанного тем же ключом.

## Переводы
Источник — `tools/gen_strings.py` (en, ru, uz, kk, tg, be). Запуск: `python3 tools/gen_strings.py`.
Для совместной работы можно подключить Weblate или Crowdin к `app/src/main/res/values*/strings.xml`.
Узбекский, казахский, таджикский и белорусский переводы стоит вычитать носителям.

## libdl
`app/src/main/python/libdl.py` — копия github.com/monolit/libdl (MIT), включается переключателем «libdl (экспериментально)».
