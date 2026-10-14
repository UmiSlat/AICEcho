from __future__ import annotations

import shutil
import stat
import tempfile
import zipfile
from pathlib import Path

from module_layout import REQUIRED_ARCH_FILES, validate_source, validate_staged

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "ksu-module"
NATIVE_STAGE = ROOT / "app" / "build" / "ksu-module" / "arm64-v8a"
OUTPUT = ROOT / "dist" / "aicecho-pmm-ksu-v3.zip"


def add_file(archive: zipfile.ZipFile, source: Path, name: str) -> None:
    info = zipfile.ZipInfo(name)
    info.compress_type = zipfile.ZIP_DEFLATED
    executable = name.endswith(".sh") or name.startswith("bin/")
    mode = 0o755 if executable else 0o644
    info.external_attr = (stat.S_IFREG | mode) << 16
    info.create_system = 3
    archive.writestr(info, source.read_bytes())


def main() -> None:
    missing = validate_source(SOURCE)
    if missing:
        raise SystemExit(f"module source is incomplete: {', '.join(missing)}")

    with tempfile.TemporaryDirectory(prefix="aicecho-module-") as temporary:
        stage = Path(temporary)
        for source in SOURCE.rglob("*"):
            if source.is_file():
                target = stage / source.relative_to(SOURCE)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
        for name in REQUIRED_ARCH_FILES:
            source = NATIVE_STAGE / name
            if not source.is_file():
                raise SystemExit(f"native build output is missing: {source}")
            target = stage / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

        missing = validate_staged(stage)
        if missing:
            raise SystemExit(f"staged module is incomplete: {', '.join(missing)}")
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(OUTPUT, "w") as archive:
            for source in sorted(path for path in stage.rglob("*") if path.is_file()):
                add_file(archive, source, source.relative_to(stage).as_posix())
    print(OUTPUT)


if __name__ == "__main__":
    main()
