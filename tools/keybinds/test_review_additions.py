import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from review_additions import FIELDS, review


class ReviewAdditionsTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.pack = Path(self.temporary.name)
        self.stock = self.pack / "tools/keybinds/stock.json"
        self.stock.parent.mkdir(parents=True)
        self.lock = self.pack / "tools/modified-lock.json"
        self.lock.write_text("{}\n", encoding="utf-8")
        self.preset = self.pack / "launcher/controls-preset.txt"
        self.preset.parent.mkdir()
        self.preset.write_text("# Published binding\nkey_key.jump:key.keyboard.space\n", encoding="utf-8")
        self.mods = self.pack / "mods"
        self.mods.mkdir()
        self.existing = self.mods / "existing.jar"
        self.existing.write_bytes(b"existing byte pin")
        self.stock.write_text(json.dumps({
            "sourceJars": {self.existing.name: hashlib.sha256(self.existing.read_bytes()).hexdigest()},
            "rows": [{"id": "key.jump"}],
        }), encoding="utf-8")
        self.baseline = self.tsv(("key.jump", "key.keyboard.space", "key.keyboard.space"))

    @staticmethod
    def tsv(*mappings):
        rows = ["\t".join(FIELDS)]
        for name, current, default in mappings:
            rows.append("\t".join([name, "key.categories.movement", "net.minecraft.client.KeyMapping",
                                   "IN_GAME", current, default]))
        return ("\n".join(rows) + "\n").encode()

    def test_new_native_names_are_attributed_without_writing_pack_files(self):
        new_jar = self.mods / "added.jar"
        with zipfile.ZipFile(new_jar, "w") as archive:
            archive.writestr("assets/added/lang/en_us.json", '{"key.added.move": "Dash"}')
            archive.writestr("assets/added/lang/ru_ru.json", '{"key.added.move": "Рывок"}'.encode())
        candidate = self.tsv(
            ("key.jump", "key.keyboard.space", "key.keyboard.space"),
            ("key.added.move", "key.keyboard.unknown", "key.keyboard.space"),
            ("key.added.slow", "scancode.32", "key.keyboard.space:SHIFT"),
        )
        before = {file: file.read_bytes() for file in (self.stock, self.preset)}
        result = review(self.pack, self.baseline, candidate)
        added = result["newNativeNames"]["key.added.move"]
        self.assertEqual(added["translationSources"], {"added.jar": {"en_us": ["Dash"], "ru_ru": ["Рывок"]}})
        self.assertEqual(added["authorDefaultsSharePresetInput"], ["key.jump"])
        self.assertEqual(added["authorDefaultsShareNewInput"], ["key.added.slow"])
        self.assertEqual(result["existingNativeNamesChanged"], {})
        self.assertEqual({file: file.read_bytes() for file in before}, before)

    def test_changed_old_defaults_and_duplicate_native_ids_are_visible(self):
        candidate = self.tsv(
            ("key.jump", "key.keyboard.space", "key.keyboard.j"),
            ("key.added.dash", "key.keyboard.j", "key.keyboard.j"),
            ("key.added.dash", "key.keyboard.h", "key.keyboard.h"),
        )
        result = review(self.pack, self.baseline, candidate)
        self.assertEqual(result["existingNativeNamesChanged"]["key.jump"]["candidate"][0]["defaultValue"], "key.keyboard.j")
        self.assertEqual(len(result["newNativeNames"]["key.added.dash"]["optionsRows"]), 2)

    def test_replaced_old_jar_blocks_additions_review(self):
        self.existing.write_bytes(b"different bytes")
        with self.assertRaisesRegex(ValueError, "without exact approval: existing.jar"):
            review(self.pack, self.baseline, self.baseline)

    def test_exact_approved_base_replacement_still_preserves_existing_mapping_evidence(self):
        old = self.existing.read_bytes()
        self.existing.unlink()
        replacement = self.mods / "compatible.jar"
        with zipfile.ZipFile(replacement, "w") as archive:
            archive.writestr("assets/compatible/lang/en_us.json", '{}')
        new_hash = hashlib.sha256(replacement.read_bytes()).hexdigest()
        self.lock.write_text(json.dumps({"baseJarOverrides": [{
            "original": {"path": "mods/existing.jar", "sha256": hashlib.sha256(old).hexdigest()},
            "replacement": {"path": "mods/compatible.jar", "sha256": new_hash},
        }]}), encoding="utf-8")
        report = review(self.pack, self.baseline, self.baseline)
        self.assertEqual(report["replacedSourceJars"], {"existing.jar": {"fileName": "compatible.jar", "sha256": new_hash}})
        self.assertEqual(report["existingNativeNamesChanged"], {})

    def test_reference_pack_can_preserve_published_controls_during_candidate_retries(self):
        reference = self.pack / "reference"
        for source in (self.stock, self.preset):
            destination = reference / source.relative_to(self.pack)
            destination.parent.mkdir(parents=True)
            destination.write_bytes(source.read_bytes())
        self.preset.write_text("key_key.unpublished:key.keyboard.o\n", encoding="utf-8")
        result = review(self.pack, self.baseline, self.baseline, reference)
        self.assertEqual(result["publishedPresetRows"], 1)
        self.assertEqual(result["publishedPresetNamesMissingFromCandidate"], [])


if __name__ == "__main__":
    unittest.main()
