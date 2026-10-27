"""Update the project's public description using the GitHub Actions secret."""
from pathlib import Path
import json
import os
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def main():
    token = os.environ.get("MODRINTH_TOKEN", "").strip()
    if not token:
        raise SystemExit("Missing MODRINTH_TOKEN repository secret. Add a Modrinth token with Create versions and Write projects permissions.")
    root = Path(__file__).resolve().parents[1]
    repo = "https://github.com/fridorin/Skyblock-Translator-Mod"
    payload = {
        "body": (root / "modrinth_description.md").read_text(encoding="utf-8"),
        "description": "Client-side Fabric translation for Hypixel SkyBlock. Bundled Russian/French dictionaries and online translation for other phrases and languages.",
        "source_url": repo,
        "issues_url": repo + "/issues",
        "wiki_url": repo + "/blob/main/README.md",
    }
    request = Request("https://api.modrinth.com/v2/project/IryQN0Fj",
                      data=json.dumps(payload, ensure_ascii=False).encode("utf-8"), method="PATCH",
                      headers={"Authorization": token, "Content-Type": "application/json",
                               "User-Agent": "fridorin/Skyblock-Translator-Mod (GitHub Actions)"})
    try:
        with urlopen(request, timeout=30) as response:
            if response.status != 204:
                raise SystemExit(f"Unexpected Modrinth response: HTTP {response.status}")
    except HTTPError as error:
        if error.code in (401, 403):
            raise SystemExit("Modrinth rejected the token. Use the project's owner/member account and grant Write projects in addition to Create versions.") from None
        raise SystemExit(f"Modrinth description update failed: HTTP {error.code}") from None
    print("Modrinth description and documentation links updated.")


if __name__ == "__main__":
    main()
