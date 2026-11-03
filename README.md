<div align="center">

# SimpleVoiceChatPlate

Custom music discs, playlists, goat horn soundpad, and 3D wall acoustics via Simple Voice Chat.

[![Platform](https://img.shields.io/badge/Platform-Paper%2026.3-00ADD8?style=flat-square&logo=paper)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?style=flat-square&logo=openjdk)](https://adoptium.net)
[![Simple Voice Chat](https://img.shields.io/badge/Dependency-Simple%20Voice%20Chat-4CAF50?style=flat-square)](https://modrinth.com/plugin/simple-voice-chat)
[![Author](https://img.shields.io/badge/Author-Kxysl1k-6e5494?style=flat-square)](https://kxysl1k.netlify.app)

[English](#english) · [Українська](#українська)

</div>

---

## English

**SimpleVoiceChatPlate** lets players burn custom tracks and playlists onto vanilla Music Discs and Goat Horns, playing them directly through **Simple Voice Chat**. Players only need Simple Voice Chat installed on their client — no custom resource packs or extra mods.

### Features

- **48 kHz Opus Audio**: Encodes streams in `OpusEncoderMode.AUDIO` (full 20 Hz – 20 kHz range) with `soxr` resampling and optional EBU R128 loudness normalization.
- **3D Wall Acoustics**: Traces blocks between the audio source and each listener. Dense materials (stone, deepslate, obsidian, wool) muffle sound stronger than thin ones (glass, doors, leaves). Volume and low-pass filtering transition smoothly without sudden jumps around corners, sound bends through open doorways, and muffles underwater.
- **Playlists & Song Radio**: Supports playlists from YouTube, YouTube Music, SoundCloud, and Google Drive folders. If a playlist link is unavailable — or when using the `mix:` prefix — the plugin fetches clean Incognito track recommendations (`RDAMVM`) based on the song itself without account history bias.
- **Fast Streaming & Preloading**: Playback starts after ~1.5 seconds of buffering while the rest of the file downloads in the background. For playlists, the plugin preloads the beginning of adjacent tracks so switching forward or backward has no delay.
- **Vanilla Sound Suppression**: Removes vanilla `JUKEBOX_PLAYABLE` / `INSTRUMENT` item components and filters sound packets via `PacketEvents` so default Minecraft disc music never plays underneath custom audio.
- **Jukebox Player GUI (`Shift + Right-Click`)**: Track list pages, progress bar, `±5s` / `±15s` seeking, pause/resume, repeat (`Off` / `Playlist` / `Single`), shuffle, and persistent volume that stays saved when switching playlist tracks.
- **Goat Horn Settings**: Per-item volume, audible distance, max duration, and click mode (`toggle` or `restart`).
- **Amplifier Pyramid**: Building 1–4 layers of Iron, Copper, Gold, Diamond, Emerald, or Netherite blocks under a Jukebox increases its audible radius.

### Usage

#### Recording & Erasing
- **Command**: Hold a Music Disc or Goat Horn and run:
  ```text
  /svcplate burn <URL_or_ID> [Custom Name]
  ```
- **Anvil**: Put a Music Disc or Goat Horn in the left slot and rename it:
  ```text
  https://youtu.be/dQw4w9WgXcQ | &bTrack Title
  ```
- **Song Radio from a Single Track**: Add `mix:` or `radio:` before the link:
  ```text
  /svcplate burn mix:https://youtu.be/dQw4w9WgXcQ Radio Mix
  ```
- **YouTube Search**: Run `/svcplate search <query>` and click a result in chat to prefill the burn command.
- **Erase**: Hold the item and run `/svcplate erase`, or combine it with **Shears** in an Anvil.

#### Jukebox Pyramid Range
| Pyramid Layers | Default Range |
| :--- | :---: |
| 0 layers (Jukebox only) | 65 blocks |
| 1 layer (`3×3` blocks) | 95 blocks |
| 2 layers (`5×5` base) | 130 blocks |
| 3 layers (`7×7` base) | 170 blocks |
| 4 layers (`9×9` base) | 215 blocks |

### Commands & Permissions

**Aliases:** `/svcplate`, `/disc`

| Command | Description | Permission (Default) |
| :--- | :--- | :--- |
| `/svcplate help` | Show available commands | `simplevoicechatplate.command.help` (`true`) |
| `/svcplate burn <url> [name]` | Burn a track, playlist, or `mix:<url>` | `simplevoicechatplate.command.burn` (`true`) |
| `/svcplate erase` | Clear custom audio and restore vanilla item | `simplevoicechatplate.command.erase` (`true`) |
| `/svcplate search <query>` | Search YouTube in-game | `simplevoicechatplate.command.search` (`true`) |
| `/svcplate cancel` | Stop your active Goat Horn / portable audio | `simplevoicechatplate.command.cancel` (`true`) |
| `/svcplate horn <info\|volume\|distance\|duration\|mode\|notify>` | Configure Goat Horn parameters | `simplevoicechatplate.command.horn.*` (`true`) |
| `/svcplate cancel <player>` | Stop another player's active audio | `simplevoicechatplate.command.cancel.other` (`op`) |
| `/svcplate stoparound [radius]` | Stop and eject active audio in radius | `simplevoicechatplate.command.stoparound` (`op`) |
| `/svcplate status` | Check active streams, cache, and acoustics | `simplevoicechatplate.command.status` (`op`) |
| `/svcplate cache clear` | Clear disk and memory audio cache | `simplevoicechatplate.command.cache.clear` (`op`) |
| `/svcplate updatedeps` | Update the `yt-dlp` binary | `simplevoicechatplate.command.updatedeps` (`op`) |
| `/svcplate reload` | Reload `config.yml` and `lang/*.yml` | `simplevoicechatplate.command.reload` (`op`) |

**Additional Permissions:**
- `simplevoicechatplate.admin` (`op`) — Full admin access and unlimited item quotas.
- `simplevoicechatplate.anvil.burn` / `simplevoicechatplate.anvil.erase` (`true`) — Record or erase items in an Anvil.
- `simplevoicechatplate.burn.bypass` (`op`) — Bypass `require_burnable_tag` if enabled in config.
- `simplevoicechatplate.play.jukebox` / `.horn` / `.portable` (`true`) — Use custom jukeboxes, horns, or portable player.
- `simplevoicechatplate.<single|playlist|horn>.<N>` — Per-player item creation limits (e.g. `simplevoicechatplate.playlist.5`).

### Installation

1. Use **Paper 26.3** on **Java 21+**.
2. Install **[Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)** and **[PacketEvents](https://modrinth.com/plugin/packetevents)**.
3. Install **`ffmpeg`** on the host system (`yt-dlp` is downloaded and updated automatically).
4. Place `SimpleVoiceChatPlate.jar` into `plugins/` and start the server.

---

## Українська

**SimpleVoiceChatPlate** дозволяє записувати власні треки та плейлисти на звичайні музичні платівки й козячі роги з відтворенням через **Simple Voice Chat**. Гравцям достатньо мати клієнтський мод Simple Voice Chat — без додаткових модів чи ресурспаків.

### Можливості

- **Якість звуку 48 кГц Opus**: кодування у музичному режимі `OpusEncoderMode.AUDIO` (повний діапазон 20 Гц – 20 кГц), ресемплінг `soxr` та опціональна нормалізація гучності EBU R128.
- **3D-акустика крізь стіни**: плагін прораховує блоки між джерелом звуку та кожним гравцем. Щільні матеріали (камінь, глибокий сланець, обсидіан, вовна) глушать звук сильніше за тонкі (скло, двері, листя). Гучність та фільтрація змінюються плавно без різких стрибків на кутах, звук огинає відкриті двері та приглушується під водою.
- **Плейлисти та радіо за піснею**: підтримка плейлистів із YouTube, YouTube Music, SoundCloud і папок Google Drive. Якщо оригінальний плейлист недоступний або вказано префікс `mix:` — підбираються чисті Incognito-рекомендації (`RDAMVM`) за самою піснею без прив'язки до чужої історії переглядів.
- **Швидкий старт і предзагрузка**: відтворення починається через ~1.5 с буферизації паралельно з фоновим завантаженням. У плейлистах заздалегідь підвантажується початок сусідніх треків для перемикання без пауз.
- **Приглушення ванільних платівок**: видаляє ванільні компоненти `JUKEBOX_PLAYABLE` / `INSTRUMENT` і блокує звукові пакети через `PacketEvents`, тому стандартна музика Minecraft не накладається на кастомний трек.
- **Меню програвача (`Shift + ПКМ`)**: перегляд сторінок плейлиста, прогрес-бар, перемотка `±5 с` / `±15 с`, пауза, режими повтору (`Вимк` / `Плейлист` / `Одна пісня`), перемішування та збереження виставленої гучності при переході на наступні пісні.
- **Налаштування козячих рогів**: окрема гучність, дистанція, тривалість і режим натискання (`toggle` або `restart`) для кожного рога.
- **Піраміда дальності**: 1–4 шари блоків заліза, міді, золота, діамантів, смарагдів або незериту під програвачем збільшують радіус чутності.

### Використання

#### Запис та очищення
- **Командою**: візьміть платівку або козячий ріг у руку й введіть:
  ```text
  /svcplate burn <Посилання_або_ID> [Назва]
  ```
- **Через ковадло**: покладіть предмет у лівий слот і перейменуйте:
  ```text
  https://youtu.be/dQw4w9WgXcQ | &bНазва треку
  ```
- **Радіо на основі однієї пісні**: додайте префікс `mix:` або `radio:` перед посиланням:
  ```text
  /svcplate burn mix:https://youtu.be/dQw4w9WgXcQ Мій мікс
  ```
- **Пошук на YouTube**: введіть `/svcplate search <запит>` і натисніть на потрібний трек у чаті.
- **Очищення**: введіть `/svcplate erase` з предметом у руці або поєднайте його з **ножицями** в ковадлі.

#### Дальність піраміди під програвачем
| Шари піраміди | Стандартний радіус |
| :--- | :---: |
| 0 шарів (лише програвач) | 65 блоків |
| 1 шар (`3×3` блоки) | 95 блоків |
| 2 шари (основа `5×5`) | 130 блоків |
| 3 шари (основа `7×7`) | 170 блоків |
| 4 шари (основа `9×9`) | 215 блоків |

### Команди та права

**Аліаси:** `/svcplate`, `/disc`

| Команда | Опис | Право (За замовчуванням) |
| :--- | :--- | :--- |
| `/svcplate help` | Список доступних команд | `simplevoicechatplate.command.help` (`true`) |
| `/svcplate burn <url> [назва]` | Записати трек, плейлист або `mix:<url>` | `simplevoicechatplate.command.burn` (`true`) |
| `/svcplate erase` | Очистити запис і повернути ванільний предмет | `simplevoicechatplate.command.erase` (`true`) |
| `/svcplate search <запит>` | Пошук музики на YouTube | `simplevoicechatplate.command.search` (`true`) |
| `/svcplate cancel` | Зупинити власний звук рога / плеєра | `simplevoicechatplate.command.cancel` (`true`) |
| `/svcplate horn <info\|volume\|distance\|duration\|mode\|notify>` | Налаштування параметрів козячого рога | `simplevoicechatplate.command.horn.*` (`true`) |
| `/svcplate cancel <гравець>` | Зупинити активний звук іншого гравця | `simplevoicechatplate.command.cancel.other` (`op`) |
| `/svcplate stoparound [радіус]` | Зупинити всі джерела звуку в радіусі | `simplevoicechatplate.command.stoparound` (`op`) |
| `/svcplate status` | Перевірка потоків, кешу та 3D-акустики | `simplevoicechatplate.command.status` (`op`) |
| `/svcplate cache clear` | Очистити кеш аудіофайлів | `simplevoicechatplate.command.cache.clear` (`op`) |
| `/svcplate updatedeps` | Оновити бінарний файл `yt-dlp` | `simplevoicechatplate.command.updatedeps` (`op`) |
| `/svcplate reload` | Перезавантажити конфіг та мовні файли | `simplevoicechatplate.command.reload` (`op`) |

**Додаткові права:**
- `simplevoicechatplate.admin` (`op`) — Повний доступ адміністратора та безлімітні квоти.
- `simplevoicechatplate.anvil.burn` / `simplevoicechatplate.anvil.erase` (`true`) — Запис і стирання в ковадлі.
- `simplevoicechatplate.burn.bypass` (`op`) — Обхід вимоги чистих платівок (`require_burnable_tag`).
- `simplevoicechatplate.play.jukebox` / `.horn` / `.portable` (`true`) — Використання програвача, рогів і плеєра.
- `simplevoicechatplate.<single|playlist|horn>.<N>` — Ліміти на кількість записаних предметів (наприклад, `simplevoicechatplate.playlist.5`).

### Встановлення

1. Сервер **Paper 26.3** на **Java 21+**.
2. Встановіть **[Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat)** та **[PacketEvents](https://modrinth.com/plugin/packetevents)**.
3. Встановіть **`ffmpeg`** на хост-машині (`yt-dlp` завантажується й оновлюється автоматично).
4. Покладіть `SimpleVoiceChatPlate.jar` у папку `plugins/` і запустіть сервер.

---

<div align="center">
Author: <a href="https://kxysl1k.netlify.app">Kxysl1k</a> · <a href="https://github.com/kirilo987/SimpleVoiceChatPlate">GitHub</a>
</div>
