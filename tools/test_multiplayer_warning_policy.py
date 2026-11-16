import json
from pathlib import Path
import tempfile
import unittest
import zipfile

import multiplayer_warning_policy as policy


class MultiplayerWarningPolicyTests(unittest.TestCase):
    def test_author_updates_change_only_the_warning_line_and_are_repeatable(self):
        for raw, expected in (
            (b"lang:ru_ru\r\nskipMultiplayerWarning:false\r\nfov:0.5\r\n",
             b"lang:ru_ru\r\nskipMultiplayerWarning:true\r\nfov:0.5\r\n"),
            (b"lang:en_us\r\nfov:0.5", b"lang:en_us\r\nfov:0.5\r\nskipMultiplayerWarning:true\r\n"),
            (b"fov:0.2\n", b"fov:0.2\nskipMultiplayerWarning:true\n"),
            (b"skipMultiplayerWarning:true", b"skipMultiplayerWarning:true"),
        ):
            with self.subTest(raw=raw):
                locked = policy.record("options.txt", raw)
                self.assertEqual(policy.apply(raw), expected)
                self.assertEqual(policy.apply(expected), expected)
                self.assertEqual(policy.author_bytes(expected, locked), raw)
                with self.assertRaises(ValueError):
                    policy.author_bytes(expected + b"\nunknown:change", locked)

    def test_publication_checks_the_active_profile_inside_the_staged_archive(self):
        config = json.dumps({"mainProfile": "Next"}).encode()
        profile = policy.profile_options(config)
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            (out / "assets").mkdir()
            asset = {"name": "config.zip", "kind": "zipRoot", "covers": [policy.CONFIG, profile]}
            for raw, error in (
                (policy.SETTING + b"\n", None),
                (b"skipMultiplayerWarning:false\n", "must set"),
                (b"lang:en_us\n", "must set"),
                (b"skipMultiplayerWarning:true\nskipMultiplayerWarning:false\n", "Duplicate"),
                (None, "Missing"),
            ):
                with self.subTest(raw=raw):
                    files = {policy.CONFIG: config, profile: raw}
                    with zipfile.ZipFile(out / "assets/config.zip", "w") as archive:
                        for path, content in files.items():
                            if content is not None:
                                archive.writestr(path, content)
                    manifest = {"minecraftVersion": "1.20.1", "assets": [asset], "files": [
                        policy.record(path, content) for path, content in files.items() if content is not None
                    ]}
                    if error:
                        with self.assertRaisesRegex(ValueError, error):
                            policy.verify_staged(out, manifest)
                    else:
                        policy.verify_staged(out, manifest)
