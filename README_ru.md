# SkyBlock Translator

Клиентский Fabric-мод для перевода Hypixel SkyBlock. Текущая версия: **1.2.2**.

[Modrinth](https://modrinth.com/mod/skyblock-translator) · [GitHub](https://github.com/fridorin/Skyblock-Translator-Mod) · [Releases](https://github.com/fridorin/Skyblock-Translator-Mod/releases) · [Issues](https://github.com/fridorin/Skyblock-Translator-Mod/issues)

[English](README.md) · [Русский](README_ru.md) · [Français](README_fr.md)

[Установка](#installation) · [Возможности](#features) · [Команды](#commands) · [Провайдеры](#providers) · [Словари](#dictionaries) · [Проблемы](#troubleshooting) · [Сборка](#building)

<a id="installation"></a>
## Установка и совместимость

Установите [Fabric Loader](https://fabricmc.net/use/installer/) **0.19.5 или новее**. Скачайте JAR для своей версии Minecraft с [Modrinth](https://modrinth.com/mod/skyblock-translator/versions) или [GitHub Releases](https://github.com/fridorin/Skyblock-Translator-Mod/releases/latest) и положите его в `.minecraft/mods`.

Обязательны [Fabric API](https://modrinth.com/mod/fabric-api) и [YetAnotherConfigLib (YACL)](https://modrinth.com/mod/yacl), собранные для той же версии Minecraft. [Mod Menu](https://modrinth.com/mod/modmenu) необязателен. Мод устанавливается на клиент.

| Minecraft | Java | Fabric API ≥ | YACL ≥ |
| --- | --- | --- | --- |
| 1.21.11 | 21 | 0.141.5 | 3.8.1 |
| 26.1.2 | 25 | 0.155.3 | 3.9.7 |
| 26.2 | 25 | 0.161.0 | 3.9.7 |
| 26.3 | 25 | 0.161.0 | 3.9.7 |

В таблице указаны минимальные версии библиотек. Их JAR для разных версий игры не взаимозаменяемы. Актуальная стабильная поддерживаемая версия — Minecraft 26.3. Снапшоты не поддерживаются.

<a id="features"></a>
## Возможности и настройки

- Перевод чата, диалогов NPC, названий и описаний предметов, заголовков инвентаря, имён сущностей, Tab и необязательного scoreboard.
- Русский и французский словари включены в мод. Отсутствующие фразы и другие языки переводятся через интернет; меню содержит 30 языков, доступность зависит от провайдера.
- Точные записи пользовательского словаря имеют приоритет. Шаблоны помогают переводить характеристики с меняющимися числами.
- Режимы чата: `SEPARATE_LINE`, `APPEND`, `INLINE`, `REPLACE`. Первые три сохраняют оригинал. В `SEPARATE_LINE` исходные клики и наведения остаются в оригинальной части сообщения.
- Shift временно отключает новые переводы, если включена соответствующая настройка. Уже изменённая история чата автоматически не восстанавливается.
- Настройки открываются клавишей **K**, командой `/translator` или через Mod Menu. Клавишу общего переключателя нужно назначить самостоятельно. Язык по умолчанию — русский.
- «Только на Hypixel» включено по умолчанию. Отключите настройку для проверки в одиночной игре или на другом сервере.
- Названия, зачарования, редкость, названия и описания способностей имеют отдельные переключатели. Разделы описаний распознаются по стандартной структуре lore с пустыми строками; нестандартные предметы могут отличаться.
- Исходящие сообщения не на английском могут переводиться на английский. Порядок отправки сохраняется; после смены соединения старые сообщения не отправляются. Команды с `/` не переводятся.

Перевод названий предметов может мешать поиску на Auction House/Bazaar — отключите его, если нужны исходные названия. Онлайн-сервисы могут отвечать с ошибками или ограничивать запросы; словарные переводы работают без них.

<a id="commands"></a>
## Команды

Алиас `/переводчик` ведёт к тем же командам.

| Команда | Действие |
| --- | --- |
| `/translator` | Открыть настройки. |
| `/translator toggle chat` | Переключить перевод входящего чата. |
| `/translator toggle tooltips` | Переключить перевод описаний предметов. |
| `/translator toggle titles` | Переключить перевод заголовков инвентаря. |
| `/translator toggle scoreboard` | Переключить перевод scoreboard. |
| `/translator toggle npc` | Переключить перевод диалогов NPC. |
| `/translator toggle outgoing` | Переключить перевод исходящего чата. |
| `/translator setlang fr` | Сменить язык, перезагрузить словари и кэш. `en` отключает перевод. |
| `/translator setprovider GOOGLE_FREE` | Выбрать `GOOGLE_FREE`, `DEEPL_FREE` или `YANDEX`. |
| `/translator add Strength = Сила` | Сохранить свой перевод. |
| `/translator lookup Strength` | Найти точную запись и её источник. |
| `/translator clear-cache` | Очистить сохранённый кэш переводов и кэш отображения. |
| `/translator reload` | Перезагрузить конфиг, словарь языка и пользовательский словарь. |
| `/translator copy-hand` | Скопировать JSON-шаблон исходного описания предмета в руке. |
| `/translator update` | Скачать словарь с GitHub. В репозитории есть только русский и французский. |
| `/translator test` | Проверить онлайн-перевод с учётом резервного провайдера. |

<a id="providers"></a>
## Провайдеры и данные

- `GOOGLE_FREE`: Google без ключа. Доступность не гарантируется, возможны ограничения частоты запросов.
- `DEEPL_FREE`: ключ DeepL API Free в настройках. [Авторизация DeepL](https://developers.deepl.com/docs/getting-started/auth).
- `YANDEX`: API-ключ Yandex Cloud с правами Translate. [Авторизация Yandex Cloud](https://yandex.cloud/en/docs/translate/api-ref/authentication).

При ошибках DeepL/Yandex возможен переход на Google. Онлайн-перевод отправляет текст выбранному сервису. Найденные имена игроков, команды и числа защищаются плейсхолдерами, но это не гарантирует анонимность. Ключ хранится в локальном конфиге — не прикладывайте его к отчётам об ошибках.

Мод работает на клиенте, а перевод исходящего чата отправляет обычные сообщения. Гарантий одобрения сервером или защиты от блокировок нет. Проверьте [правила Hypixel о модификациях](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications).

<a id="dictionaries"></a>
## Словари и файлы

Папка: `.minecraft/config/skyblock_translator/`.

- `config.json`: переключатели, язык и ключ провайдера.
- `user_dictionary.json`: личные переводы. Файл общий для всех выбранных языков; его содержимое должно подходить текущему языку.
- `dictionary_ru.json`, `dictionary_fr.json`: скачанные словари. Автообновление может их менять; свои исправления храните в пользовательском словаре.
- `cache_<language>.json`: кэш онлайн-переводов конкретного языка.

```json
{
  "strength": "Сила",
  "r:health: \\+(\\d+)": "Здоровье: +$1"
}
```

Ключи с `r:` — регулярные выражения Java. Группы подставляются как `$1`, `$2`; обратные слеши нужно экранировать для JSON. Некорректные шаблоны и ссылки на группы пропускаются. После редактирования выполните `/translator reload`. Общие переводы можно предложить через [pull request](https://github.com/fridorin/Skyblock-Translator-Mod/pulls); словари в `dictionaries` и ресурсах мода должны совпадать.

<a id="troubleshooting"></a>
## Если что-то не работает

Проверьте язык, переключатели, Shift и настройку «Только на Hypixel». Выполните `/translator reload`, при необходимости `/translator clear-cache`. Команда `/translator test` проверяет онлайн-путь; успешный резервный Google не подтверждает правильность платного ключа.

При падении на старте проверьте соответствие JAR и зависимостей версии игры. Падение scoreboard из 1.2.0 исправлено в 1.2.1; установите 1.2.2 для дополнительных исправлений. В [новом issue](https://github.com/fridorin/Skyblock-Translator-Mod/issues/new) укажите версии игры, мода, библиотек и приложите очищенный от личных данных crash report.

<a id="building"></a>
## Сборка и выпуск

Запускайте Gradle на JDK 25. Toolchains автоматически предоставляют Java 21 для Minecraft 1.21.11. Нужен интернет для зависимостей.

```sh
./gradlew :1.21.11:build :26.1.2:build :26.2:build :26.3:build
./gradlew :26.3:runClient
python scripts/check_project.py
python scripts/check_project.py --online
```

На Windows используйте `gradlew.bat`. Готовые файлы: `versions/<minecraft>/build/libs/`; для релиза берётся JAR без `sources`/`dev`. Общий код находится в `src/main`; сгенерированные файлы в `versions/*/build` не редактируются.

Версия задаётся в [stonecutter.properties.toml](stonecutter.properties.toml). После проверок добавляется changelog `release-notes/v<version>.md` и отправляется тег `v<version>`. [GitHub Actions](https://github.com/fridorin/Skyblock-Translator-Mod/actions) публикует GitHub Release, затем отдельные версии Modrinth. Секрет `MODRINTH_TOKEN` должен иметь права `Create versions`, а для синхронизации описания — ещё `Write projects`. Токен нельзя добавлять в код.

[Изменения 1.2.2](release-notes/v1.2.2.md) включают исправления 1.2.1, которые ранее не выходили на Modrinth. [Описание Modrinth](modrinth_description.md) обновляется при выпуске. Лицензия: [MIT](LICENSE).
