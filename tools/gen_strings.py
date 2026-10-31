#!/usr/bin/env python3
"""Генерирует app/src/main/res/values*/strings.xml из одного словаря.
Править переводы здесь (или подключить Weblate/Crowdin к res/values*/strings.xml)."""
import os, sys
from xml.sax.saxutils import escape

KEYS = """nav_main nav_log nav_settings app_tagline card_download hint_link btn_download hint_clipboard
toast_no_links toast_added card_jobs jobs_empty btn_pause btn_resume btn_cancel btn_retry btn_clear_done
state_queued state_running state_running_pct state_paused state_done state_failed state_canceled
card_behavior sw_show_window sw_quiet card_audio label_format hint_music_domains note_music_builtin sw_sponsor
card_video hint_max_height card_folders note_folders folder_video folder_audio folder_image folder_file
card_network hint_proxy hint_extra sw_libdl card_cookies cookies_none btn_web_login btn_import_cookies
btn_clear_cookies toast_cookies_added card_maintenance btn_update card_language btn_language lang_system
card_log btn_clear_log web_title web_hint_url btn_go btn_save_cookies toast_web_saved toast_web_none
notif_channel_progress notif_channel_errors notif_active notif_waiting notif_paused notif_failed
toast_saved toast_exists err_nothing""".split()

L = {}
L["en"] = """Main|Journal|Settings|Share a link — download starts instantly|Download|Link(s)|Download|Leave empty to use the clipboard
No links found|Added: %1$d|Downloads|Nothing yet|Pause|Resume|Cancel|Retry|Clear finished
Queued|Downloading|Downloading %1$d%%|Paused|Done|Failed|Canceled
Behavior|Open window on Share|Quiet mode (no pop-ups, errors only)|Audio (music sites)|Format|Extra music domains, comma-separated|music.youtube.com, SoundCloud, Bandcamp and others are built in|Remove SponsorBlock segments
Video|Max height, px|Folders|Relative to internal storage. Images: Pictures or DCIM, video: Movies, audio: Music; Download/Documents work for any type.|Video|Audio|Images|Files
Network|Proxy (http:// or socks5://)|Extra yt-dlp arguments|Direct file links via libdl (experimental)|Cookies|No cookies yet|Sign in via browser|Import cookies.txt (several allowed)
Clear all|Cookies added: %1$d|Maintenance|Update yt-dlp and gallery-dl|Language|Language: %1$s|System
Journal|Clear journal|Sign in|Address|Go|Save cookies|Saved cookies for %1$s: %2$d|No cookies for this site yet — sign in first
Downloads|Download errors|Active downloads: %1$d|Waiting…|Paused|Download failed
Saved: %1$s|Already exists: %1$s|Nothing downloaded"""
L["ru"] = """Главная|Журнал|Настройки|Поделитесь ссылкой — загрузка начнётся сразу|Скачать|Ссылка(и)|Скачать|Оставьте пустым — возьму из буфера обмена
Ссылки не найдены|Добавлено: %1$d|Загрузки|Пока пусто|Пауза|Продолжить|Отмена|Повторить|Очистить завершённые
В очереди|Загрузка|Загрузка %1$d%%|На паузе|Готово|Ошибка|Отменено
Поведение|Открывать окно при Share|Тихий режим (без всплывающих, только ошибки)|Аудио (музыкальные сайты)|Формат|Доп. музыкальные домены через запятую|music.youtube.com, SoundCloud, Bandcamp и др. уже встроены|Вырезать сегменты SponsorBlock
Видео|Максимальная высота, px|Папки|Относительно внутренней памяти. Картинки: Pictures или DCIM, видео: Movies, аудио: Music; Download/Documents подходят для любого типа.|Видео|Аудио|Картинки|Файлы
Сеть|Прокси (http:// или socks5://)|Доп. аргументы yt-dlp|Прямые ссылки на файлы через libdl (экспериментально)|Cookies|Cookies не загружены|Войти через браузер|Импорт cookies.txt (можно несколько)
Очистить все|Добавлено cookies: %1$d|Обслуживание|Обновить yt-dlp и gallery-dl|Язык|Язык: %1$s|Системный
Журнал|Очистить журнал|Вход|Адрес|Перейти|Сохранить cookies|Cookies для %1$s сохранены: %2$d|Для этого сайта пока нет cookies — сначала войдите
Загрузки|Ошибки загрузки|Активных загрузок: %1$d|Ожидание…|На паузе|Не удалось скачать
Сохранено: %1$s|Уже есть: %1$s|Ничего не скачано"""
L["uz"] = """Asosiy|Jurnal|Sozlamalar|Havolani ulashing — yuklash darhol boshlanadi|Yuklab olish|Havola(lar)|Yuklab olish|Boʻsh qoldirsangiz, bufer ishlatiladi
Havola topilmadi|Qoʻshildi: %1$d|Yuklashlar|Hozircha yoʻq|Pauza|Davom ettirish|Bekor qilish|Qayta urinish|Tugaganlarni tozalash
Navbatda|Yuklanmoqda|Yuklanmoqda %1$d%%|Pauzada|Tayyor|Xatolik|Bekor qilindi
Xatti-harakat|Ulashganda oynani ochish|Sokin rejim (xabarsiz, faqat xatolar)|Audio (musiqa saytlari)|Format|Qoʻshimcha musiqa domenlari (vergul bilan)|music.youtube.com, SoundCloud, Bandcamp va boshqalar oʻrnatilgan|SponsorBlock boʻlaklarini olib tashlash
Video|Maksimal balandlik, px|Papkalar|Ichki xotiraga nisbatan. Rasmlar: Pictures yoki DCIM, video: Movies, audio: Music; Download/Documents har qanday tur uchun ishlaydi.|Video|Audio|Rasmlar|Fayllar
Tarmoq|Proksi (http:// yoki socks5://)|yt-dlp qoʻshimcha argumentlari|Toʻgʻridan-toʻgʻri fayl havolalari libdl orqali (tajribaviy)|Cookies|Hozircha cookies yoʻq|Brauzer orqali kirish|cookies.txt import (bir nechta mumkin)
Hammasini tozalash|Cookies qoʻshildi: %1$d|Xizmat|yt-dlp va gallery-dl yangilash|Til|Til: %1$s|Tizim
Jurnal|Jurnalni tozalash|Kirish|Manzil|Oʻtish|Cookies saqlash|%1$s uchun cookies saqlandi: %2$d|Bu sayt uchun cookies yoʻq — avval kiring
Yuklashlar|Yuklash xatolari|Faol yuklashlar: %1$d|Kutilmoqda…|Pauzada|Yuklab boʻlmadi
Saqlandi: %1$s|Allaqachon bor: %1$s|Hech narsa yuklanmadi"""
L["kk"] = """Басты|Журнал|Баптаулар|Сілтемемен бөлісіңіз — жүктеу бірден басталады|Жүктеу|Сілтеме(лер)|Жүктеу|Бос қалдырсаңыз, буфер қолданылады
Сілтеме табылмады|Қосылды: %1$d|Жүктеулер|Әзірге бос|Үзіліс|Жалғастыру|Бас тарту|Қайталау|Аяқталғандарды тазалау
Кезекте|Жүктелуде|Жүктелуде %1$d%%|Үзілісте|Дайын|Қате|Бас тартылды
Мінез-құлық|Бөлісу кезінде терезені ашу|Тыныш режим (хабарландырусыз, тек қателер)|Аудио (музыка сайттары)|Формат|Қосымша музыка домендері (үтір арқылы)|music.youtube.com, SoundCloud, Bandcamp және басқалары кірістірілген|SponsorBlock үзінділерін алып тастау
Бейне|Ең жоғары биіктік, px|Қалталар|Ішкі жадқа қатысты. Суреттер: Pictures немесе DCIM, бейне: Movies, аудио: Music; Download/Documents кез келген түрге жарайды.|Бейне|Аудио|Суреттер|Файлдар
Желі|Прокси (http:// немесе socks5://)|yt-dlp қосымша аргументтері|Файлға тікелей сілтемелер libdl арқылы (тәжірибелік)|Cookies|Cookies әзірге жоқ|Браузер арқылы кіру|cookies.txt импорттау (бірнешеу болады)
Барлығын тазалау|Cookies қосылды: %1$d|Қызмет көрсету|yt-dlp және gallery-dl жаңарту|Тіл|Тіл: %1$s|Жүйелік
Журнал|Журналды тазалау|Кіру|Мекенжай|Өту|Cookies сақтау|%1$s үшін cookies сақталды: %2$d|Бұл сайт үшін cookies жоқ — алдымен кіріңіз
Жүктеулер|Жүктеу қателері|Белсенді жүктеулер: %1$d|Күтуде…|Үзілісте|Жүктеу сәтсіз
Сақталды: %1$s|Бұрыннан бар: %1$s|Ештеңе жүктелмеді"""
L["tg"] = """Асосӣ|Журнал|Танзимот|Истинодро мубодила кунед — боргирӣ фавран оғоз мешавад|Боргирӣ|Истиноди(ҳо)|Боргирӣ|Агар холӣ монед, буфер истифода мешавад
Истинод ёфт нашуд|Илова шуд: %1$d|Боргириҳо|Ҳоло холӣ|Таваққуф|Идома додан|Бекор кардан|Такроран|Анҷомёфтаҳоро тоза кардан
Дар навбат|Боргирӣ шуда истодааст|Боргирӣ %1$d%%|Таваққуф|Тайёр|Хато|Бекор шуд
Рафтор|Ҳангоми мубодила тирезаро кушодан|Реҷаи ором (бе огоҳӣ, танҳо хатоҳо)|Аудио (сайтҳои мусиқӣ)|Формат|Доменҳои иловагии мусиқӣ (бо вергул)|music.youtube.com, SoundCloud, Bandcamp ва дигарон дарунсохт мебошанд|Қисмҳои SponsorBlock-ро нест кардан
Видео|Баландии ҳадди аксар, px|Ҷузвдонҳо|Нисбат ба хотираи дохилӣ. Тасвирҳо: Pictures ё DCIM, видео: Movies, аудио: Music; Download/Documents барои ҳар намуд мувофиқ аст.|Видео|Аудио|Тасвирҳо|Файлҳо
Шабака|Прокси (http:// ё socks5://)|Аргументҳои иловагии yt-dlp|Истиноди бевоситаи файл тавассути libdl (озмоишӣ)|Cookies|Ҳоло cookies нест|Воридшавӣ тавассути браузер|Воридоти cookies.txt (якчанд мумкин аст)
Ҳамаро тоза кардан|Cookies илова шуд: %1$d|Нигоҳубин|Навсозии yt-dlp ва gallery-dl|Забон|Забон: %1$s|Низом
Журнал|Тоза кардани журнал|Воридшавӣ|Нишонӣ|Рафтан|Захираи cookies|Cookies барои %1$s захира шуд: %2$d|Барои ин сайт cookies нест — аввал ворид шавед
Боргириҳо|Хатоҳои боргирӣ|Боргириҳои фаъол: %1$d|Интизорӣ…|Таваққуф|Боргирӣ нашуд
Захира шуд: %1$s|Аллакай ҳаст: %1$s|Чизе боргирӣ нашуд"""
L["be"] = """Галоўная|Журнал|Налады|Падзяліцеся спасылкай — загрузка пачнецца адразу|Загрузіць|Спасылка(і)|Загрузіць|Пакіньце пустым, каб узяць з буфера
Спасылак не знойдзена|Дададзена: %1$d|Загрузкі|Пакуль пуста|Паўза|Працягнуць|Скасаваць|Паўтарыць|Ачысціць завершаныя
У чарзе|Загрузка|Загрузка %1$d%%|На паўзе|Гатова|Памылка|Скасавана
Паводзіны|Адкрываць акно пры Share|Ціхі рэжым (без усплывальных, толькі памылкі)|Аўдыё (музычныя сайты)|Фармат|Дадатковыя музычныя дамены праз коску|music.youtube.com, SoundCloud, Bandcamp і іншыя ўжо ўбудаваны|Выдаляць сегменты SponsorBlock
Відэа|Максімальная вышыня, px|Папкі|Адносна ўнутранай памяці. Выявы: Pictures або DCIM, відэа: Movies, аўдыё: Music; Download/Documents падыходзяць для любога тыпу.|Відэа|Аўдыё|Выявы|Файлы
Сетка|Проксі (http:// або socks5://)|Дадатковыя аргументы yt-dlp|Прамыя спасылкі на файлы праз libdl (эксперыментальна)|Cookies|Cookies пакуль няма|Увайсці праз браўзер|Імпарт cookies.txt (можна некалькі)
Ачысціць усё|Дададзена cookies: %1$d|Абслугоўванне|Абнавіць yt-dlp і gallery-dl|Мова|Мова: %1$s|Сістэмная
Журнал|Ачысціць журнал|Уваход|Адрас|Перайсці|Захаваць cookies|Cookies для %1$s захаваны: %2$d|Для гэтага сайта няма cookies — спачатку ўвайдзіце
Загрузкі|Памылкі загрузкі|Актыўныя загрузкі: %1$d|Чаканне…|На паўзе|Загрузка не ўдалася
Захавана: %1$s|Ужо ёсць: %1$s|Нічога не загружана"""

KEYS += ["tile_label", "card_quick", "btn_add_tile", "note_quick"]
EXTRA = {
 "en": ["Download from clipboard", "Quick access", "Add tile to Quick Settings", "The tile and the long-press shortcut on the app icon download links from the clipboard. ShareDL also appears in the system Share menu."],
 "ru": ["Скачать из буфера", "Быстрый доступ", "Добавить плитку в шторку", "Плитка и ярлык по долгому нажатию на иконку скачивают ссылки из буфера обмена. ShareDL также виден в системном меню «Поделиться»."],
 "uz": ["Buferdan yuklab olish", "Tezkor kirish", "Plitkani tezkor sozlamalarga qoʻshish", "Plitka va ilova belgisini uzoq bosganda chiqadigan yorliq havolalarni buferdan yuklaydi. ShareDL tizimning «Ulashish» menyusida ham koʻrinadi."],
 "kk": ["Буферден жүктеу", "Жылдам қолжетімділік", "Плитканы жылдам параметрлерге қосу", "Плитка мен қолданба белгішесін ұзақ басқанда шығатын таңбаша сілтемелерді буферден жүктейді. ShareDL жүйенің «Бөлісу» мәзірінде де көрінеді."],
 "tg": ["Боргирӣ аз буфер", "Дастрасии зуд", "Илова кардани плитка ба танзимоти зуд", "Плитка ва нишонаи тасвири барнома (бо пахшкунии дароз) истиноднамоҳоро аз буфер боргирӣ мекунанд. ShareDL дар менюи низомии «Мубодила» низ намоён аст."],
 "be": ["Загрузіць з буфера", "Хуткі доступ", "Дадаць плітку ў шторку", "Плітка і ярлык па доўгім націсканні на значок загружаюць спасылкі з буфера абмену. ShareDL таксама бачны ў сістэмным меню «Падзяліцца»."],
}

FOLDERS = {"en": "values", "ru": "values-ru", "uz": "values-uz", "kk": "values-kk", "tg": "values-tg", "be": "values-be"}

def esc(s):
    s = escape(s).replace("'", "\\'").replace('"', '\\"')
    if s.startswith(("@", "?")):
        s = "\\" + s
    return s

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
for lang, blob in L.items():
    vals = [v for line in blob.split("\n") for v in line.split("|")]
    vals += EXTRA[lang]
    if len(vals) != len(KEYS):
        sys.exit(f"{lang}: {len(vals)} значений, ожидалось {len(KEYS)}")
    d = os.path.join(root, FOLDERS[lang]); os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "strings.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
        if lang == "en":
            f.write('    <string name="app_name" translatable="false">ShareDL</string>\n')
        for k, v in zip(KEYS, vals):
            f.write(f'    <string name="{k}">{esc(v)}</string>\n')
        f.write('</resources>\n')
print("ok:", len(KEYS), "ключей ×", len(L), "языков")
