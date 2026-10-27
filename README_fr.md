# SkyBlock Translator

Mod Fabric côté client pour traduire Hypixel SkyBlock. Version actuelle : **1.2.2**.

[Modrinth](https://modrinth.com/mod/skyblock-translator) · [GitHub](https://github.com/fridorin/Skyblock-Translator-Mod) · [Releases](https://github.com/fridorin/Skyblock-Translator-Mod/releases) · [Issues](https://github.com/fridorin/Skyblock-Translator-Mod/issues)

[English](README.md) · [Русский](README_ru.md) · [Français](README_fr.md)

[Installation](#installation) · [Fonctions](#features) · [Commandes](#commands) · [Services](#providers) · [Dictionnaires](#dictionaries) · [Dépannage](#troubleshooting) · [Compilation](#building)

<a id="installation"></a>
## Installation et compatibilité

Installez [Fabric Loader](https://fabricmc.net/use/installer/) **0.19.5 ou ultérieur**. Placez le JAR correspondant à votre version de Minecraft dans `.minecraft/mods`. Téléchargements : [Modrinth](https://modrinth.com/mod/skyblock-translator/versions) ou [GitHub Releases](https://github.com/fridorin/Skyblock-Translator-Mod/releases/latest).

[Fabric API](https://modrinth.com/mod/fabric-api) et [YetAnotherConfigLib (YACL)](https://modrinth.com/mod/yacl) sont obligatoires, avec leurs versions correspondant à Minecraft. [Mod Menu](https://modrinth.com/mod/modmenu) est facultatif. Ce mod s'installe sur le client.

| Minecraft | Java | Fabric API ≥ | YACL ≥ |
| --- | --- | --- | --- |
| 1.21.11 | 21 | 0.141.5 | 3.8.1 |
| 26.1.2 | 25 | 0.155.3 | 3.9.7 |
| 26.2 | 25 | 0.161.0 | 3.9.7 |
| 26.3 | 25 | 0.161.0 | 3.9.7 |

Le tableau indique les versions minimales des bibliothèques ; leurs JAR ne sont pas interchangeables entre versions de Minecraft. Minecraft 26.3 est la dernière version stable prise en charge. Les snapshots ne sont pas pris en charge.

<a id="features"></a>
## Fonctions et paramètres

- Traduction du chat, des dialogues PNJ, des noms et descriptions d'objets, des titres d'inventaire, des noms d'entités, de la liste Tab et du scoreboard facultatif.
- Dictionnaires russe et français inclus. Les phrases absentes et les autres langues passent par le service en ligne. Le menu propose 30 langues ; leur disponibilité dépend du service.
- Les entrées exactes du dictionnaire personnel sont prioritaires. Des modèles gèrent les statistiques et nombres variables.
- Modes de chat : `SEPARATE_LINE`, `APPEND`, `INLINE`, `REPLACE`. Les trois premiers gardent l'original. `SEPARATE_LINE` conserve les actions originales de clic et de survol dans la partie d'origine.
- Maintenez Maj pour suspendre les nouvelles traductions si cette option est activée. L'historique déjà traduit n'est pas restauré automatiquement.
- Ouvrez les paramètres avec **K**, `/translator` ou Mod Menu. La touche d'activation générale n'est pas attribuée par défaut. La langue par défaut est le russe.
- « Uniquement sur Hypixel » est activé par défaut. Désactivez-le pour tester en solo ou sur un autre serveur.
- Interrupteurs distincts pour noms d'objets, enchantements, rareté, noms et descriptions de capacités. La détection des descriptions suit les sections de lore séparées par des lignes vides ; les formats personnalisés peuvent différer.
- Le chat sortant non anglais peut être traduit en anglais. Les messages gardent leur ordre et ne sont pas envoyés après un changement de connexion. Les commandes commençant par `/` sont exclues.

La traduction des noms peut gêner les recherches Auction House/Bazaar. Désactivez-la pour conserver les noms d'origine. Les services en ligne peuvent échouer ou limiter les requêtes ; les correspondances du dictionnaire restent disponibles hors ligne.

<a id="commands"></a>
## Commandes

Alias russe : `/переводчик`.

| Commande | Fonction |
| --- | --- |
| `/translator` | Ouvrir les paramètres. |
| `/translator toggle chat` | Activer/désactiver le chat entrant. |
| `/translator toggle tooltips` | Activer/désactiver les descriptions d'objets. |
| `/translator toggle titles` | Activer/désactiver les titres d'inventaire. |
| `/translator toggle scoreboard` | Activer/désactiver le scoreboard. |
| `/translator toggle npc` | Activer/désactiver les dialogues PNJ. |
| `/translator toggle outgoing` | Activer/désactiver le chat sortant. |
| `/translator setlang fr` | Changer la langue et recharger dictionnaires/cache. `en` désactive la traduction. |
| `/translator setprovider GOOGLE_FREE` | Choisir `GOOGLE_FREE`, `DEEPL_FREE` ou `YANDEX`. |
| `/translator add Strength = Force` | Enregistrer une traduction personnelle. |
| `/translator lookup Strength` | Rechercher une entrée exacte et sa source. |
| `/translator clear-cache` | Effacer le cache des traductions et de l'affichage. |
| `/translator reload` | Recharger configuration, dictionnaire de langue et dictionnaire personnel. |
| `/translator copy-hand` | Copier un modèle JSON du texte original de l'objet tenu. |
| `/translator update` | Télécharger le dictionnaire GitHub. Seuls le russe et le français sont fournis ici. |
| `/translator test` | Tester la traduction en ligne, y compris le service de secours. |

<a id="providers"></a>
## Services et confidentialité

- `GOOGLE_FREE` : point d'accès Google sans clé ; disponibilité non garantie et limitations possibles.
- `DEEPL_FREE` : saisissez votre clé API Free. [Authentification DeepL](https://developers.deepl.com/docs/getting-started/auth).
- `YANDEX` : clé API Yandex Cloud avec droits Translate. [Authentification Yandex](https://yandex.cloud/en/docs/translate/api-ref/authentication).

En cas d'erreur DeepL/Yandex, Google peut servir de secours. La traduction en ligne transmet le texte au service concerné. Les noms connus des joueurs, commandes et nombres sont protégés par des marqueurs lorsqu'ils sont détectés ; cela ne garantit pas l'anonymat. La clé est enregistrée dans le fichier local de configuration : ne partagez pas ce fichier dans un rapport de bug.

Le mod fonctionne côté client ; la traduction sortante envoie des messages de chat ordinaires. Aucune approbation du serveur ni protection contre un bannissement n'est garantie. Consultez les [règles Hypixel concernant les modifications](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications).

<a id="dictionaries"></a>
## Dictionnaires et fichiers

Dossier : `.minecraft/config/skyblock_translator/`.

- `config.json` : options, langue et identifiants du service.
- `user_dictionary.json` : traductions personnelles partagées entre les langues sélectionnées ; adaptez-les à la langue actuelle.
- `dictionary_ru.json`, `dictionary_fr.json` : dictionnaires téléchargés. Les mises à jour automatiques peuvent les modifier ; utilisez le dictionnaire personnel pour vos corrections.
- `cache_<language>.json` : traductions en ligne enregistrées par langue.

```json
{
  "strength": "Force",
  "r:health: \\+(\\d+)": "Santé : +$1"
}
```

Les clés `r:` sont des expressions régulières Java. Références de groupes : `$1`, `$2`. Les barres obliques inverses doivent être échappées en JSON. Les expressions et références invalides sont ignorées. Rechargez avec `/translator reload`. Proposez les corrections globales par [pull request](https://github.com/fridorin/Skyblock-Translator-Mod/pulls) ; les dictionnaires téléchargeables et intégrés doivent correspondre.

<a id="troubleshooting"></a>
## Dépannage

Vérifiez la langue, les interrupteurs, la touche Maj et « Uniquement sur Hypixel », puis `/translator reload`. Effacez les traductions obsolètes avec `/translator clear-cache`. `/translator test` vérifie la traduction en ligne ; le succès du secours Google ne valide pas nécessairement la clé du service choisi.

En cas de plantage au démarrage, vérifiez la version du JAR et des dépendances. Le plantage du scoreboard de 1.2.0 a été corrigé en 1.2.1 ; installez 1.2.2 pour les corrections supplémentaires. Joignez les versions du jeu, du mod et des bibliothèques ainsi qu'un rapport de plantage sans données personnelles à [un nouveau ticket](https://github.com/fridorin/Skyblock-Translator-Mod/issues/new).

<a id="building"></a>
## Compiler et publier

Lancez Gradle avec JDK 25. Les toolchains fournissent Java 21 pour Minecraft 1.21.11. Internet est nécessaire au téléchargement des dépendances.

```sh
./gradlew :1.21.11:build :26.1.2:build :26.2:build :26.3:build
./gradlew :26.3:runClient
python scripts/check_project.py
python scripts/check_project.py --online
```

Sous Windows, utilisez `gradlew.bat`. Résultats : `versions/<minecraft>/build/libs/`. Les JAR `sources`/`dev` ne sont pas les fichiers de distribution. Le code partagé est dans `src/main` ; ne modifiez pas les sources générées sous `versions/*/build`.

La version est définie dans [stonecutter.properties.toml](stonecutter.properties.toml). Ajoutez `release-notes/v<version>.md`, passez les vérifications, puis poussez le tag correspondant. [GitHub Actions](https://github.com/fridorin/Skyblock-Translator-Mod/actions) publie GitHub Release puis les versions Modrinth séparées. Le secret `MODRINTH_TOKEN` nécessite `Create versions` et, pour synchroniser la description, `Write projects`. Ne commitez jamais ce secret.

Les [notes 1.2.2](release-notes/v1.2.2.md) incluent les corrections 1.2.1 non encore publiées sur Modrinth. La [description Modrinth](modrinth_description.md) est synchronisée lors de la publication. Licence [MIT](LICENSE).
