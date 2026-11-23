# SkyBlock Translator

Client-side Fabric translation mod for Hypixel SkyBlock. Current release: **1.3.0**.

[Modrinth](https://modrinth.com/mod/skyblock-translator) · [GitHub](https://github.com/fridorin/Skyblock-Translator-Mod) · [Releases](https://github.com/fridorin/Skyblock-Translator-Mod/releases) · [Issues](https://github.com/fridorin/Skyblock-Translator-Mod/issues)

[English](README.md) · [Русский](README_ru.md) · [Français](README_fr.md)

[Installation](#installation) · [Features](#features) · [Commands](#commands) · [Providers](#providers) · [Dictionaries](#dictionaries) · [Troubleshooting](#troubleshooting) · [Building](#building) · [Publishing](#publishing)

<a id="installation"></a>
## Installation and supported versions

Install [Fabric Loader](https://fabricmc.net/use/installer/) **0.19.5 or newer**. Download the translator JAR for your exact Minecraft target from [Modrinth](https://modrinth.com/mod/skyblock-translator/versions) or [GitHub Releases](https://github.com/fridorin/Skyblock-Translator-Mod/releases/latest), then place it in `.minecraft/mods`.

Install [Fabric API](https://modrinth.com/mod/fabric-api) and [YetAnotherConfigLib (YACL)](https://modrinth.com/mod/yacl) for the same Minecraft version. [Mod Menu](https://modrinth.com/mod/modmenu) is optional. Install the mod on the client; it is not a server plugin.

| Minecraft | Java | Fabric API ≥ | YACL ≥ |
| --- | --- | --- | --- |
| 1.21.11 | 21 | 0.141.6 | 3.8.2 |
| 26.1.2 | 25 | 0.155.3 | 3.9.7 |
| 26.2 | 25 | 0.161.0 | 3.9.7 |
| 26.3 | 25 | 0.166.2 | 3.9.7 |

Choose each dependency's build for the matching Minecraft version; the table lists minimum library versions, not interchangeable JARs. Minecraft 26.3 is the latest stable target supported by this release. Snapshots are not supported.

<a id="features"></a>
## Features and settings

- Translate incoming chat, NPC dialogue, item names and lore, inventory titles, entity names, the tab list, and the optional scoreboard.
- Russian and French dictionaries are bundled for offline matches. Other phrases and languages use the selected online provider. The language menu offers 30 languages; provider availability varies.
- Exact user dictionary entries take priority over the downloaded dictionary. Patterns cover common stats and changing numbers.
- Chat modes: `SEPARATE_LINE`, `APPEND`, `INLINE`, `REPLACE`. Click, hover and Shift-click insertion stay on the original text. In `REPLACE`, interactive originals stay visible alongside the translation.
- Hold Shift to bypass new translations when `Disable on Shift` is enabled. This does not retroactively restore already translated chat history.
- Open settings with **K**, `/translator`, or Mod Menu. The toggle hotkey is unassigned until configured. The default target language is Russian.
- `Only on Hypixel` is enabled by default. Disable it deliberately if you want to test in singleplayer or on another server.
- Item names, enchantments, ability names/descriptions and rarity have separate switches. Description detection follows standard blank-line-separated lore sections; custom layouts may differ.
- With outgoing translation enabled, an attempted non-English message becomes an editable English draft. Review it and press **Send**. Use `/translator draft` to reopen a pending draft. Drafts are limited to one per connection, and commands beginning with `/` are sent normally.

Item-name translation can make Auction House/Bazaar searches less convenient. Disable it if you need the original names. Online requests can fail or be rate-limited; offline dictionary matches remain available.

<a id="commands"></a>
## Commands

The Russian alias is `/переводчик`.

| Command | Effect |
| --- | --- |
| `/translator` | Open settings. |
| `/translator toggle chat` | Toggle incoming chat translation. |
| `/translator toggle tooltips` | Toggle item lore translation. |
| `/translator toggle titles` | Toggle inventory titles. |
| `/translator toggle scoreboard` | Toggle scoreboard translation. |
| `/translator toggle npc` | Toggle NPC dialogue. |
| `/translator toggle outgoing` | Toggle outgoing chat translation. |
| `/translator setlang fr` | Set the target language, reload dictionaries and caches. `en` disables translation. |
| `/translator setprovider GOOGLE_FREE` | Select `GOOGLE_FREE`, `DEEPL_FREE` or `YANDEX`. |
| `/translator add Strength = Сила` | Save a custom translation. |
| `/translator lookup Strength` | Look up an exact dictionary entry and its source. |
| `/translator clear-cache` | Clear the translation cache and rendered component cache. |
| `/translator reload` | Reload config, target-language dictionary and user dictionary. |
| `/translator copy-hand` | Copy a JSON template for the held item's original tooltip. |
| `/translator update` | Download the target-language dictionary from GitHub. Only Russian and French are provided in this repository. |
| `/translator test` | Test the selected provider directly with a sample message. |
| `/translator status` | Show language, provider, dictionary state, pending requests and the last error category. |
| `/translator draft` | Reopen the editable outgoing chat draft. |

<a id="providers"></a>
## Providers and privacy

- `GOOGLE_FREE`: keyless Google translation endpoint. It has no availability guarantee and may throttle requests.
- `DEEPL_FREE`: enter a DeepL API Free key in settings. See [DeepL authentication](https://developers.deepl.com/docs/getting-started/auth).
- `YANDEX`: enter a Yandex Cloud API key with Translate permissions. See [Yandex authentication](https://yandex.cloud/en/docs/translate/api-ref/authentication).

Local-only mode uses bundled, downloaded and user dictionaries already on the computer. It pauses online requests, provider tests and dictionary downloads. In online mode, configure network access separately for chat, NPCs, tooltips and world text. Google fallback after a network error or HTTP 5xx is opt-in. Missing keys, authentication failures, quota and rate limits are reported by the selected provider. A new configuration starts with translation of direct messages and outgoing chat disabled. Existing saved settings are preserved. Detected player names, commands and numbers may be masked in the translation pipeline, so treat text sent for translation as potentially identifiable. Keep the local file containing your API key out of bug reports.

The mod operates on the client, but outgoing translation sends ordinary chat messages. No guarantee of server approval or protection from bans is implied. Check [Hypixel's modification rules](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications).

<a id="dictionaries"></a>
## Dictionaries and configuration

Files are stored under `.minecraft/config/skyblock_translator/`:

- `config.json`: feature switches, target language and provider credentials.
- `user_dictionary.json`: custom translations shared by your selected target languages. Use translations appropriate for the current language.
- `dictionary_ru.json` / `dictionary_fr.json`: local downloaded dictionaries. Automatic downloads can update these files; put personal overrides in `user_dictionary.json`.
- `cache_v2_<language>.json`: online results stored by language, actual provider and channel, with a seven-day expiry. Chat and NPC entries are memory-only by default. Earlier cache files are left on disk and excluded from lookups because they lack provider and channel information.

User dictionary diagnostics in settings show the local match, its source and invalid regular-expression replacements. DeepL and Yandex have separate API keys. A legacy key is assigned to the previously selected paid provider when that provider has no key already. A key with no known owner is retained as unassigned and stays out of requests.

Example user dictionary:

```json
{
  "strength": "Сила",
  "r:health: \\+(\\d+)": "Здоровье: +$1"
}
```

`r:` keys are Java regular expressions; replacement groups use `$1`, `$2`, etc. JSON requires escaped backslashes. Invalid patterns/replacement references are skipped. Run `/translator reload` after editing. Contribute global entries through [GitHub pull requests](https://github.com/fridorin/Skyblock-Translator-Mod/pulls); the downloadable and bundled dictionaries must stay identical.

<a id="troubleshooting"></a>
## Troubleshooting

If settings or translations seem wrong, check the target language, feature switches, Shift state and `Only on Hypixel`, then run `/translator reload`. Clear old API translations with `/translator clear-cache`. Use `/translator test` to check the currently selected provider and its credentials.

For startup crashes, use the JAR and dependencies matching your Minecraft version. The scoreboard startup crash in 1.2.0 was fixed in 1.2.1. Update to 1.3.0 for the current compatibility fixes. Attach the Minecraft version, mod/dependency versions and a sanitized crash report to [a new issue](https://github.com/fridorin/Skyblock-Translator-Mod/issues/new).

<a id="building"></a>
## Build and test

Use JDK 25 to run Gradle; Java toolchains provision Java 21 for Minecraft 1.21.11. The Gradle wrapper and dependency downloads require internet access.

```sh
./gradlew :1.21.11:build :26.1.2:build :26.2:build :26.3:build
./gradlew :26.3:runClient
python scripts/check_project.py
python scripts/check_jars.py
python scripts/check_project.py --online
```

The source build uses the dependency versions in `stonecutter.properties.toml`. Run the Python checks with Python 3.11 or newer.

On Windows use `gradlew.bat` instead of `./gradlew`. JARs are written to `versions/<minecraft>/build/libs/`; release files exclude sources/dev JARs. Stonecutter version branches share `src/main`; do not edit generated sources under `versions/*/build`.

<a id="publishing"></a>
## Release maintenance

The version is defined in [stonecutter.properties.toml](stonecutter.properties.toml). Add `release-notes/v<version>.md`, pass all four builds and the documentation checks, then push the matching `v<version>` tag. [GitHub Actions](https://github.com/fridorin/Skyblock-Translator-Mod/actions) publishes four JARs and SHA-256 checksums to GitHub, then publishes the four Modrinth versions and synchronizes the project description.

Store the Modrinth token as the repository secret `MODRINTH_TOKEN`. Publishing needs `Create versions`; syncing the public description also needs `Write projects`. Never commit the token. The workflow reads [modrinth_description.md](modrinth_description.md) for the public project description.

Licensed under [MIT](LICENSE). See [1.3.0 release notes](release-notes/v1.3.0.md).
