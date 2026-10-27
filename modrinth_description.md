# SkyBlock Translator — 1.2.2

Client-side Fabric translation for Hypixel SkyBlock. Supports Minecraft **1.21.11, 26.1.2, 26.2 and 26.3**. Choose the file matching your game version.

**Required:** [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.5+, [Fabric API](https://modrinth.com/mod/fabric-api) and [YACL](https://modrinth.com/mod/yacl), each for the correct Minecraft version. Java 21 for 1.21.11; Java 25 for 26.x. [Mod Menu](https://modrinth.com/mod/modmenu) is optional.

## English

Translate chat, NPC dialogue, item lore/names, inventory titles, entity names, Tab and the optional scoreboard. Russian and French dictionaries are included for offline matches; other phrases/languages use Google Free, DeepL API Free or Yandex Cloud. The language menu offers 30 languages, subject to provider support.

Open settings with **K** or `/translator`; use `/translator setlang fr` to select French. The default language is Russian and translation is limited to Hypixel by default. Hold Shift to bypass new translations; already translated chat history is not automatically restored. Separate-line chat mode retains the original message's interactions. Item-name translation can be disabled for easier Auction House/Bazaar searches.

Online translation sends text to the provider; DeepL/Yandex can fall back to Google. Player-name placeholders do not guarantee anonymization. Outgoing translation sends ordinary English chat messages in order. No guarantee of server approval or protection from bans is implied.

[English documentation](https://github.com/fridorin/Skyblock-Translator-Mod/blob/main/README.md)

## Русский

Клиентский перевод чата, диалогов NPC, названий и описаний предметов, заголовков инвентаря, имён сущностей, Tab и scoreboard. Русский и французский словари включены в мод; остальные фразы и языки переводятся онлайн.

Настройки: **K** или `/переводчик`. По умолчанию выбран русский и включён режим «Только на Hypixel». Shift приостанавливает новые переводы, но не восстанавливает историю чата. Перевод названий предметов можно отключить, чтобы сохранить исходные названия для поиска. Онлайн-перевод передаёт текст провайдеру, а при ошибках DeepL/Yandex возможен переход на Google. Гарантий одобрения сервером нет.

[Документация на русском](https://github.com/fridorin/Skyblock-Translator-Mod/blob/main/README_ru.md)

## Français

Traduction côté client du chat, des dialogues PNJ, des objets, des titres d'inventaire, des noms d'entités, de Tab et du scoreboard facultatif. Dictionnaires russe et français inclus ; les autres phrases/langues utilisent un service en ligne.

Paramètres : **K** ou `/translator`. Sélectionnez le français avec `/translator setlang fr`. Le russe et « Uniquement sur Hypixel » sont activés par défaut. Maj suspend les nouvelles traductions sans restaurer l'historique du chat. Désactivez la traduction des noms pour faciliter les recherches d'objets. La traduction en ligne transmet du texte au service et peut utiliser Google en secours. Aucune approbation du serveur n'est garantie.

[Documentation française](https://github.com/fridorin/Skyblock-Translator-Mod/blob/main/README_fr.md)

## Commands / Команды / Commandes

`/translator` · `/translator setlang fr` · `/translator setprovider GOOGLE_FREE` · `/translator toggle chat` · `/translator toggle tooltips` · `/translator toggle titles` · `/translator toggle scoreboard` · `/translator toggle npc` · `/translator toggle outgoing` · `/translator add Strength = Force` · `/translator lookup Strength` · `/translator clear-cache` · `/translator reload` · `/translator copy-hand` · `/translator update` · `/translator test`

## Links

- [GitHub repository](https://github.com/fridorin/Skyblock-Translator-Mod)
- [Report a bug](https://github.com/fridorin/Skyblock-Translator-Mod/issues/new)
- [1.2.2 changelog, including 1.2.1 fixes](https://github.com/fridorin/Skyblock-Translator-Mod/blob/main/release-notes/v1.2.2.md)
- [Hypixel modification rules](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications)
- [MIT license](https://github.com/fridorin/Skyblock-Translator-Mod/blob/main/LICENSE)
