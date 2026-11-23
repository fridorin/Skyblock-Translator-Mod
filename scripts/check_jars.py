"""Verify the built Minecraft variants and optionally collect release artifacts."""

from pathlib import Path
import argparse
import hashlib
import json
import shutil
import struct
import sys
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def check_jar(path, mod, dependencies, variant):
    with zipfile.ZipFile(path) as jar:
        entries = set(jar.namelist())
        if len(entries) != len(jar.infolist()):
            raise ValueError("duplicate archive entries")
        bad_entry = jar.testzip()
        if bad_entry:
            raise ValueError(f"corrupt archive entry: {bad_entry}")

        metadata = json.loads(jar.read("fabric.mod.json"))
        java = 21 if variant["build"] == "1.21.11" else 25
        expected_fields = {
            "schemaVersion": 1,
            "id": mod["id"],
            "version": mod["version"],
            "environment": "client",
        }
        for field, expected in expected_fields.items():
            if metadata.get(field) != expected:
                raise ValueError(f"{field}: expected {expected!r}, got {metadata.get(field)!r}")

        expected_dependencies = {
            "minecraft": variant["compatibility"],
            "java": f">={java}",
            "fabricloader": f">={mod['fabric_loader_min']}",
            "fabric-api": ">=" + dependencies["fabric_api"].split("+", 1)[0],
            "yet_another_config_lib_v3": ">=" + dependencies["yacl"].split("+", 1)[0],
        }
        for name, expected in expected_dependencies.items():
            actual = metadata["depends"].get(name)
            if actual != expected:
                raise ValueError(f"dependency {name}: expected {expected!r}, got {actual!r}")

        required_classes = {
            entrypoint
            for entrypoints in metadata["entrypoints"].values()
            for entrypoint in entrypoints
        }
        if "ru.fridorin.translator.TranslatorModClient" not in metadata["entrypoints"]["client"]:
            raise ValueError("missing client initializer")
        if not metadata["mixins"]:
            raise ValueError("missing mixin configuration")
        for config_path in metadata["mixins"]:
            mixins = json.loads(jar.read(config_path))
            if mixins["compatibilityLevel"] != f"JAVA_{java}":
                raise ValueError(f"incorrect Java compatibility in {config_path}")
            required_classes.update(f"{mixins['package']}.{name}" for name in mixins["client"])
        for name in sorted(required_classes):
            if name.replace(".", "/") + ".class" not in entries:
                raise ValueError(f"missing entrypoint or mixin class: {name}")
        for name in entries:
            if name.endswith(".class"):
                with jar.open(name) as class_file:
                    magic, _, major = struct.unpack(">IHH", class_file.read(8))
                if magic != 0xCAFEBABE or major != java + 44:
                    raise ValueError(f"{name} does not target Java {java}")

        assets = f"assets/{mod['id']}"
        resources = [
            metadata["icon"],
            *(f"{assets}/lang/{locale}.json" for locale in ("en_us", "fr_fr", "ru_ru")),
            *(f"{assets}/dictionary/dictionary_{locale}.json" for locale in ("fr", "ru")),
        ]
        for name in resources:
            if name not in entries:
                raise ValueError(f"missing resource: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--matrix", action="store_true", help="print the release matrix as JSON")
    mode.add_argument("--collect", type=Path, help="copy verified jars and write checksums to this directory")
    args = parser.parse_args()
    config = tomllib.loads((ROOT / "stonecutter.properties.toml").read_text(encoding="utf-8"))
    mod = config["mod"]
    variants = [
        {
            "build": mc,
            "compatibility": values["mod"]["mc_compat"],
            "releases": "\n".join(values["mod"]["mc_releases"]),
        }
        for mc, values in config.items()
        if isinstance(values, dict) and "mc_releases" in values.get("mod", {})
    ]
    if not variants:
        parser.error("no Minecraft variants are configured")
    if args.matrix:
        print(json.dumps(variants, separators=(",", ":")))
        return 0

    jars = []
    for variant in variants:
        mc = variant["build"]
        jar = ROOT / "versions" / mc / "build" / "libs" / f"{mod['id']}-{mod['version']}+mc{mc}.jar"
        try:
            check_jar(jar, mod, config[mc]["deps"], variant)
        except (KeyError, OSError, ValueError, struct.error, zipfile.BadZipFile) as error:
            print(f"FAIL {jar.name}: {error}", file=sys.stderr)
            return 1
        print(f"PASS {jar.name}")
        jars.append(jar)

    if args.collect:
        args.collect.mkdir(parents=True, exist_ok=True)
        checksums = []
        for jar in jars:
            artifact = shutil.copyfile(jar, args.collect / jar.name)
            with artifact.open("rb") as handle:
                checksums.append(f"{hashlib.file_digest(handle, 'sha256').hexdigest()}  {jar.name}\n")
        (args.collect / "SHA256SUMS.txt").write_text("".join(checksums), encoding="ascii", newline="\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
