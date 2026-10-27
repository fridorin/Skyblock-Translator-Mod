"""Check documentation links, release metadata, JSON and localization consistency.

Run with --online to also request every external documentation URL.
"""
from pathlib import Path
from urllib.parse import unquote, urlsplit
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from concurrent.futures import ThreadPoolExecutor
import argparse
import json
import re
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[1]


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def anchors(text):
    result = set(re.findall(r'<a\s+id="([^"]+)"', text))
    for heading in re.findall(r"^#{1,6}\s+(.+)$", text, re.M):
        slug = re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")
        result.add(slug)
    return result


def check_url(url):
    headers = {"User-Agent": "Skyblock-Translator documentation check (+https://github.com/fridorin/Skyblock-Translator-Mod)"}
    try:
        with urlopen(Request(url, headers=headers), timeout=25) as response:
            response.read(256)
            return url, response.status, None
    except HTTPError as error:
        return url, error.code, str(error)
    except (URLError, TimeoutError, OSError) as error:
        return url, None, str(error)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--online", action="store_true")
    args = parser.parse_args()
    errors = []
    warnings = []
    metadata = tomllib.loads((ROOT / "stonecutter.properties.toml").read_text(encoding="utf-8"))
    version = metadata["mod"]["version"]
    notes = ROOT / "release-notes" / f"v{version}.md"
    if not notes.is_file():
        errors.append(f"Missing release notes: {notes.name}")
    documents = list(ROOT.glob("*.md")) + list((ROOT / "release-notes").glob("*.md"))
    external = set()
    repo_prefix = "https://github.com/fridorin/Skyblock-Translator-Mod/blob/main/"
    for document in documents:
        text = document.read_text(encoding="utf-8")
        if document.name.startswith("README") or document.name == "modrinth_description.md":
            if version not in text:
                errors.append(f"{document.name}: missing current version {version}")
        for target in re.findall(r"\]\(([^\s)]+)\)", text):
            if target.startswith(repo_prefix):
                # Check main-branch links against the checkout, including new files before push.
                target = target[len(repo_prefix):]
            parsed = urlsplit(target)
            if parsed.scheme in ("https", "http"):
                external.add(target)
                continue
            file = (document.parent / unquote(parsed.path)).resolve() if parsed.path else document
            if not file.is_file():
                errors.append(f"{document.name}: missing link target {target}")
            elif parsed.fragment and unquote(parsed.fragment) not in anchors(file.read_text(encoding="utf-8")):
                errors.append(f"{document.name}: missing anchor {target}")
        for example in re.findall(r"```json\s*\n(.*?)\n```", text, re.S):
            try:
                json.loads(example, object_pairs_hook=unique_object)
            except (ValueError, json.JSONDecodeError) as error:
                errors.append(f"{document.name}: invalid JSON example: {error}")
    resources = ROOT / "src/main/resources/assets/skyblock_translator"
    parsed_json = {}
    for file in list((ROOT / "dictionaries").glob("*.json")) + list(resources.rglob("*.json")) + list((ROOT / "src/main/resources").glob("*.json")):
        try:
            parsed_json[file] = json.loads(file.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
        except ValueError as error:
            errors.append(f"{file.relative_to(ROOT)}: {error}")
    for lang in ("ru", "fr"):
        source = ROOT / "dictionaries" / f"dictionary_{lang}.json"
        bundled = resources / "dictionary" / source.name
        if source in parsed_json and bundled in parsed_json and parsed_json[source] != parsed_json[bundled]:
            errors.append(f"Bundled and downloadable {lang} dictionaries differ")
    base = parsed_json.get(resources / "lang/en_us.json", {})
    for locale in ("ru_ru", "fr_fr"):
        localized = parsed_json.get(resources / f"lang/{locale}.json", {})
        if set(localized) != set(base):
            errors.append(f"Localization keys differ: {locale}")
        for key in set(localized) & set(base):
            if len(re.findall(r"%(?:\d+\$)?[sd]", localized[key])) != len(re.findall(r"%(?:\d+\$)?[sd]", base[key])):
                errors.append(f"Localization argument count differs: {locale}/{key}")
    sources = "\n".join(file.read_text(encoding="utf-8") for file in (ROOT / "src/main/java").rglob("*.java"))
    gui_keys = {"gui.skyblock_translator." + key for key in re.findall(r'translate\("([^"]+)"', sources)}
    if gui_keys - set(base):
        errors.append(f"Missing GUI strings: {sorted(gui_keys - set(base))}")
    if args.online:
        # GitHub main links were checked locally; external requests remain independent of an unpublished commit.
        with ThreadPoolExecutor(max_workers=6) as pool:
            for url, status, error in pool.map(check_url, sorted(external)):
                if status in (404, 410):
                    errors.append(f"Broken external URL ({status}): {url}")
                elif error:
                    warnings.append(f"Could not verify URL ({status}): {url}: {error}")
                else:
                    print(f"HTTP {status}: {url}")
    for warning in warnings:
        print("WARNING:", warning)
    for error in errors:
        print("ERROR:", error)
    print(f"Checked {len(documents)} documents, {len(parsed_json)} JSON files, {len(external)} external URLs; {len(errors)} errors, {len(warnings)} network warnings.")
    return bool(errors)


if __name__ == "__main__":
    sys.exit(main())
