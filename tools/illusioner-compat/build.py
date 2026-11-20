"""Build the deterministic ATM9 Modified Forge client compatibility agent."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parent
ASM_BUILD = ROOT.parent / "experimental-warnings"
PRIVATE_ASM = "dev/lanminecraft/pack/compat/asm"


def build(output, asm, tree, javac):
    pins = json.loads((ASM_BUILD / "build-inputs.json").read_text(encoding="utf-8"))
    for library, path in [("asm", asm), ("asm-tree", tree)]:
        blob = path.read_bytes()
        if len(blob) != pins[library]["sizeBytes"] or hashlib.sha256(blob).hexdigest() != pins[library]["sha256"]:
            raise ValueError("Unreviewed build input " + str(path))

    with tempfile.TemporaryDirectory(prefix="illusioner-compat-", dir=output.parent) as directory:
        classes = Path(directory)
        subprocess.run([str(javac), "--release", "17", "-encoding", "UTF-8", "-classpath",
                        os.pathsep.join(str(path) for path in (asm, tree)), "-d", str(classes),
                        str(ROOT / "IllusionerShootingStarAgent.java"), str(ROOT / "ShootingStarMixinBridge.java"),
                        str(ROOT / "LucentBlockChangePatch.java")], check=True)
        files = {path.relative_to(classes).as_posix(): path.read_bytes() for path in classes.rglob("*.class")}
        for path in (asm, tree):
            with zipfile.ZipFile(path) as library:
                files.update({name: library.read(name) for name in library.namelist()
                              if name.startswith("org/objectweb/asm/") and name.endswith(".class")})

    relocate = runpy.run_path(str(ASM_BUILD / "build.py"))["relocate"]
    files = {name.replace("org/objectweb/asm", PRIVATE_ASM): relocate(blob, PRIVATE_ASM.encode("ascii"))
             for name, blob in files.items()}
    files["META-INF/LICENSE.asm.txt"] = (ASM_BUILD / "LICENSE.asm.txt").read_bytes()
    files["META-INF/MANIFEST.MF"] = (
        b"Manifest-Version: 1.0\r\nPremain-Class: dev.lanminecraft.pack.IllusionerShootingStarAgent\r\n\r\n"
    )
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, blob in sorted(files.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            archive.writestr(info, blob)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--asm", type=Path, required=True)
    parser.add_argument("--tree", type=Path, required=True)
    parser.add_argument("--javac", type=Path, required=True)
    args = parser.parse_args()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    build(args.out, args.asm, args.tree, args.javac)
    print(hashlib.sha256(args.out.read_bytes()).hexdigest(), args.out.stat().st_size)
