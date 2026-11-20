"""Compare two actual Forge Options captures before extending the pack preset.

Run the native ``keybindings`` request on the previous and candidate clients.
Use the last published stock and preset as the reference for every candidate.
Shared physical keys and JAR translations are review leads. Read the native
context and mod behavior before assigning a preset role to a new mapping.
"""

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

from check_preset import read


FIELDS = ("id", "category", "mappingClass", "context", "nativeValue", "defaultValue")
KEY = re.compile(r"(?:key\.(?:keyboard|mouse)|scancode)\.[^\s:]+(?::(?:SHIFT|CONTROL|ALT))?")
LABEL = re.compile(r"assets/[^/]+/lang/(en_us|ru_ru)\.json")
UNKNOWN = "key.keyboard.unknown"


def capture(payload):
    reader = csv.DictReader(io.StringIO(payload.decode("utf-8-sig")), delimiter="\t", quoting=csv.QUOTE_NONE)
    if tuple(reader.fieldnames or ()) != FIELDS:
        raise ValueError("Unexpected native keybindings.tsv header")
    mappings = {}
    for row in reader:
        if set(row) != set(FIELDS) or any(not row[field] for field in FIELDS):
            raise ValueError("Incomplete native KeyMapping row")
        name = row["id"]
        if any(character.isspace() or character == ":" for character in name):
            raise ValueError("Invalid native KeyMapping ID: " + repr(name))
        if any(not KEY.fullmatch(row[field]) for field in ("nativeValue", "defaultValue")):
            raise ValueError("Unexpected forge input serialization for " + name)
        mappings.setdefault(name, []).append(row)
    if not mappings:
        raise ValueError("Native KeyMapping capture is empty")
    return mappings


def source_changes(mods, previous_jars, approved_overrides):
    actual = {}
    for jar in sorted(mods.glob("*.jar")):
        with jar.open("rb") as stream:
            actual[jar.name] = hashlib.file_digest(stream, "sha256").hexdigest()
    unchanged = {name for name, digest in previous_jars.items() if actual.get(name) == digest}
    replacements = {}
    replacement_names = set()
    for override in approved_overrides:
        original = override["original"]
        replacement = override["replacement"]
        old = original["path"].removeprefix("mods/")
        new = replacement["path"].removeprefix("mods/")
        if previous_jars.get(old) != original["sha256"] or old in unchanged:
            continue
        if actual.get(new) != replacement["sha256"] or (new != old and old in actual):
            continue
        if old in replacements or new in replacement_names or (new != old and new in previous_jars):
            raise ValueError("Overlapping approved control source JAR substitution: " + old)
        replacements[old] = {"fileName": new, "sha256": actual[new]}
        replacement_names.add(new)
    unexpected = previous_jars.keys() - unchanged - replacements.keys()
    if unexpected:
        raise ValueError("Source JAR removed or replaced without exact approval: " + ", ".join(sorted(unexpected)))
    added = {name: actual[name] for name in actual if name not in previous_jars and name not in replacement_names}
    return added, replacements


def translation_sources(mods, jars, names):
    sources = {name: {} for name in names}
    for name in jars:
        with zipfile.ZipFile(mods / name) as archive:
            for member in archive.namelist():
                match = LABEL.fullmatch(member)
                if not match:
                    continue
                try:
                    entries = json.loads(archive.read(member).decode("utf-8-sig"))
                except ValueError:
                    continue
                if not isinstance(entries, dict):
                    continue
                for mapping in names & entries.keys():
                    label = entries[mapping]
                    if isinstance(label, str) and label:
                        sources[mapping].setdefault(name, {}).setdefault(match[1], []).append(label)
    return sources


def physical_shares(value, assigned):
    key = value.partition(":")[0]
    if key == UNKNOWN:
        return []
    return sorted(name for name, other in assigned.items() if other.partition(":")[0] == key)


def review(pack, baseline, candidate, reference_pack=None):
    reference_pack = pack if reference_pack is None else reference_pack
    stock = json.loads((reference_pack / "tools/keybinds/stock.json").read_text(encoding="utf-8-sig"))
    lock = json.loads((pack / "tools/modified-lock.json").read_text(encoding="utf-8-sig"))
    preset = read((reference_pack / "launcher/controls-preset.txt").read_text(encoding="utf-8-sig"))
    if set(preset) != {row["id"] for row in stock["rows"]}:
        raise ValueError("Published preset and stock ID sets differ")
    previous = capture(baseline)
    current = capture(candidate)
    mods = pack / "mods"
    new_jars, replacements = source_changes(mods, stock["sourceJars"], lock.get("baseJarOverrides", []))
    new_names = current.keys() - previous.keys()
    removed_names = previous.keys() - current.keys()
    changed_jars = new_jars.keys() | {row["fileName"] for row in replacements.values()}
    translations = translation_sources(mods, sorted(changed_jars), new_names)
    new_defaults = {name: current[name][0]["defaultValue"] for name in new_names if len(current[name]) == 1}
    changed = {
        name: {"baseline": previous[name], "candidate": current[name]}
        for name in sorted(previous.keys() & current.keys()) if previous[name] != current[name]
    }
    additions = {}
    for name in sorted(new_names):
        default = new_defaults.get(name, UNKNOWN)
        additions[name] = {
            "optionsRows": current[name],
            "translationSources": translations[name],
            "authorDefaultsSharePresetInput": physical_shares(default, preset),
            "authorDefaultsShareNewInput": [other for other in physical_shares(default, new_defaults) if other != name],
        }
    return {
        "schemaVersion": 1,
        "baselineCaptureSha256": hashlib.sha256(baseline).hexdigest(),
        "candidateCaptureSha256": hashlib.sha256(candidate).hexdigest(),
        "publishedPresetRows": len(preset),
        "baselineNativeNames": len(previous),
        "candidateNativeNames": len(current),
        "publishedPresetNamesMissingFromBaseline": sorted(preset.keys() - previous.keys()),
        "publishedPresetNamesMissingFromCandidate": sorted(preset.keys() - current.keys()),
        "nativeNamesRemoved": sorted(removed_names),
        "existingNativeNamesChanged": changed,
        "addedSourceJars": new_jars,
        "replacedSourceJars": replacements,
        "newNativeNames": additions,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", required=True, type=Path, help="Prepared candidate with exact JARs and lock")
    parser.add_argument("--reference-pack", type=Path, help="Last published stock and preset, if separate from the candidate")
    parser.add_argument("--baseline-tsv", required=True, type=Path)
    parser.add_argument("--candidate-tsv", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path, help="New JSON report in the QA evidence directory")
    args = parser.parse_args()
    report = review(args.pack, args.baseline_tsv.read_bytes(), args.candidate_tsv.read_bytes(), args.reference_pack)
    with args.out.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(f"{len(report['newNativeNames'])} new names, {len(report['nativeNamesRemoved'])} removed, "
          f"{len(report['existingNativeNamesChanged'])} changed, {len(report['addedSourceJars'])} new JARs, "
          f"{len(report['replacedSourceJars'])} replaced JARs")


if __name__ == "__main__":
    main()
