# 🌍 SkyBlock Translator (Fabric Mod)

[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.11%20%7C%2026.1.2%20%7C%2026.2-blue.svg?logo=minecraft&color=62B036)](https://www.minecraft.net/)
[![Loader](https://img.shields.io/badge/Loader-Fabric-lightgrey.svg?logo=fabric&color=E2DBCE)](https://fabricmc.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](https://github.com/fridorin/Skyblock-Translator-Mod/blob/main/LICENSE)
[![GitHub](https://img.shields.io/badge/GitHub-Repository-blue.svg?logo=github)](https://github.com/fridorin/Skyblock-Translator-Mod)

**SkyBlock Translator** is a high-performance, client-side translation mod for Minecraft 1.21.11, 26.1.2, and 26.2, custom-built for **Hypixel SkyBlock**. It translates chat messages, item lore, chest titles, holograms, player tab lists, and entities from English to your target language (default is Russian, with support for 30+ other languages) in real-time.

---

### 🌐 Описание на русском (Russian Summary)
**SkyBlock Translator** — это клиентский Fabric-мод для автоматического перевода с английского на русский (и другие языки) в реальном времени, оптимизированный под **Hypixel SkyBlock**.

Подробная документация, инструкции и базы перевода доступны на русском языке в **[GitHub README_ru.md](https://github.com/fridorin/Skyblock-Translator-Mod/blob/main/README_ru.md)**.

---

## 🌟 Key Features

* ⚡ **Asynchronous & Non-Blocking**: Network requests run on a background thread pool. Your game FPS will never drop or stutter while waiting for a translation.
* 📴 **Instant Offline Translation**: Item stats, common phrases, and enchantments translate instantly offline using local dictionary tables and regex matching.
* 💬 **Smart Chat Display Modes**:
  * `Separate Line (Recommended)`: Outputs translation as a sub-line below the original message, **preserving all original clickable links** (coop invites, auction links, profile IDs).
  * `Hover to Reveal`: Hover over any translated chat message to see the original English text.
* 🛡️ **Technical Spam Filters**: Automatically filters out combat damage numbers (`12,450 crit`), mod prefixes (NEU, Skytils, SBA), cooldowns, UUIDs, dates, and server IP addresses to conserve API limits.
* 🛑 **Shift Bypass (Panic Button)**: Hold down the **Shift** key while reading chat or browsing items to instantly view original English text.
* 🧹 **One-Click Cache Management**: Clear disk caches instantly in-game with sound confirmation and toast notifications.

---

## 🛠️ In-Game Commands

All commands can be executed using `/translator` (or Russian alias `/переводчик`).

| Command | Description |
| :--- | :--- |
| `/translator` | Opens the graphical settings GUI (requires `YetAnotherConfigLib`). |
| `/translator add <en> = <ru>` | Saves a custom phrase mapping directly to your `user_dictionary.json`. |
| `/translator lookup <phrase>` | Checks if a term is translated and shows its source (User vs GitHub). |
| `/translator toggle <option>` | Toggles a specific config parameter on or off. |
| `/translator setlang <lang>` | Changes target language (e.g. `ru`, `de`, `es`). |
| `/translator setprovider <API>`| Swaps translation engines (`GOOGLE_FREE`, `DEEPL_FREE`, `YANDEX`). |
| `/translator clear-cache` | Clears local translation cache with sound and toast notification. |
| `/translator reload` | Reloads config and local user dictionary files. |
| `/translator copy-hand` | Copies clean JSON translation template of the item in hand. |
| `/translator update` | Pulls the latest translation dictionary from GitHub immediately. |
| `/translator test` | Validates API credentials and checks server latency. |

---

## 🎮 Recommended Configuration

Configure settings in the `/translator` YACL GUI menu:

* ⚙️ **Main Settings**:
  * `Enable Translator`: Master toggle **[Recommended]**
  * `Disable on Shift`: Hold Shift to reveal raw English text **[Recommended]**
  * `Regex Translation (Offline)`: High-performance offline stats translation **[Recommended]**
* 💬 **Chat Settings**:
  * `Translate Chat`: Master chat switch **[Recommended]**
  * `Chat Display Mode`: `Separate Line` **[Safest / Recommended]**
  * `Hover to Show Original`: Displays original text on hover **[Recommended]**
  * `Auto-translate Outgoing`: Translates your outgoing chat (RU ➔ EN) with anti-spam delay **[Experimental]**
* 🖥️ **Interface & Lore**:
  * `Item Descriptions (Lore)`: Translates item lore and stats **[Recommended]**
  * `Item Names`: Translates item titles **[Not Recommended — keep OFF to preserve Auction House/Bazaar search]**
  * `Translate Enchantment Descriptions`: Translates what an enchantment does **[Recommended]**
  * `Menu/Inventory Titles`: Translates chest titles **[Recommended]**

---

## 🔑 Translation Providers

1. **Google Free (Default)**: Free, keyless, works out of the box without any setup.
2. **DeepL API Free**: Supports up to 500,000 chars/month for free. Obtain a free key from [deepl.com](https://www.deepl.com/) and paste it into `/translator` config.
3. **Yandex Cloud Translate**: Supports Yandex Cloud Translate API credentials.

---

## 🔒 Safety & Rules Compliance

* 🎮 **Hypixel Safe**: 100% client-side. The mod does not send suspicious custom packets to the server.
* ⏱️ **Human Typing Emulation**: Outgoing chat translations are queued with a randomized delay (100ms–300ms) to mimic organic typing and avoid watchdog spam triggers.
* 🛡️ **Thread Safe**: All chat appends and UI updates run on the main Minecraft loop scheduler, preventing race conditions and rendering glitches.

---

## 🔗 Links & Resources

* 📜 **Full Documentation**: [GitHub Repository](https://github.com/fridorin/Skyblock-Translator-Mod)
* 🇷🇺 **Russian README**: [README_ru.md](https://github.com/fridorin/Skyblock-Translator-Mod/blob/main/README_ru.md)
* 🇫🇷 **French README**: [README_fr.md](https://github.com/fridorin/Skyblock-Translator-Mod/blob/main/README_fr.md)
* 🐛 **Report Issues & Suggest Translations**: [GitHub Issues Tracker](https://github.com/fridorin/Skyblock-Translator-Mod/issues)
