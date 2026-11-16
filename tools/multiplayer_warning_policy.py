"""Keep the Minecraft 1.20.1 warning disabled in the shipped default profile."""

import hashlib
import json
import re
import zipfile


CONFIG = "config/defaultsettings.json"
KEY = b"skipMultiplayerWarning:"
SETTING = KEY + b"true"
LINE = re.compile(rb"^" + KEY + rb"([^\r\n]*)(?=\r?$)", re.MULTILINE)


def profile_options(config):
    profile = json.loads(config)["mainProfile"]
    if not isinstance(profile, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", profile):
        raise ValueError("Review the new DefaultSettings profile name")
    return f"config/defaultsettings/{profile}/options.txt"


def option(raw):
    matches = list(LINE.finditer(raw))
    if len(matches) > 1:
        raise ValueError("Duplicate skipMultiplayerWarning in the default profile")
    return matches[0] if matches else None


def apply(raw):
    match = option(raw)
    if match:
        if match[1] not in (b"true", b"false"):
            raise ValueError("Invalid skipMultiplayerWarning in the default profile")
        return raw[:match.start()] + SETTING + raw[match.end():]
    newline = b"\r\n" if b"\r\n" in raw else b"\n"
    separator = newline if raw and not raw.endswith(b"\n") else b""
    return raw + separator + SETTING + newline


def validate(raw):
    match = option(raw)
    if match is None or match[1] != b"true":
        raise ValueError("The shipped default profile must set skipMultiplayerWarning:true")


def record(path, raw):
    return {"path": path, "sizeBytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}


def author_bytes(raw, locked):
    def matches(candidate):
        return record(locked["path"], candidate) == {k: locked[k] for k in ("path", "sizeBytes", "sha256")}

    if matches(raw):
        return raw
    validate(raw)
    match = option(raw)
    newline = b"\r\n" if b"\r\n" in raw else b"\n"
    candidates = [raw[:match.start()] + KEY + b"false" + raw[match.end():]]
    if raw[match.end():] == newline:
        prefix = raw[:match.start()]
        candidates.extend((prefix, prefix.removesuffix(newline)))
    for candidate in candidates:
        if matches(candidate) and apply(candidate) == raw:
            return candidate
    raise ValueError("Unknown DefaultSettings options bytes; restore the locked author input")


def verify_staged(out, manifest):
    if manifest.get("minecraftVersion") != "1.20.1":
        raise ValueError("Review the multiplayer warning policy for the new Minecraft version")
    assets = [asset for asset in manifest["assets"] if CONFIG in asset["covers"]]
    if len(assets) != 1 or assets[0]["kind"] != "zipRoot":
        raise ValueError("Missing staged DefaultSettings configuration")
    asset = assets[0]
    with zipfile.ZipFile(out / "assets" / asset["name"]) as archive:
        def read(path):
            if path not in asset["covers"] or archive.namelist().count(path) != 1:
                raise ValueError("Missing or ambiguous staged DefaultSettings file: " + path)
            raw = archive.read(path)
            if [row for row in manifest["files"] if row["path"] == path] != [record(path, raw)]:
                raise ValueError("Staged DefaultSettings file differs from manifest: " + path)
            return raw

        validate(read(profile_options(read(CONFIG))))
