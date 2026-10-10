from __future__ import annotations

import zipfile
import xml.etree.ElementTree as ElementTree
from pathlib import Path

from module_layout import REQUIRED_ARCH_FILES, REQUIRED_SOURCE_FILES

ROOT = Path(__file__).resolve().parents[1]
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
MODULE = ROOT / "dist" / "aimesim-pmm-ksu-v3.zip"
HCEF_SERVICE = ROOT / "app" / "src" / "main" / "res" / "xml" / "host_nfcf_service.xml"
ANDROID_NAMESPACE = "{http://schemas.android.com/apk/res/android}"


def require_archive(path: Path, entries: set[str]) -> None:
    if not path.is_file():
        raise SystemExit(f"missing artifact: {path}")
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
    missing = sorted(entries - names)
    if missing:
        raise SystemExit(f"{path.name} is missing: {', '.join(missing)}")


def require_hcef_metadata(path: Path, system_code: str, nfcid2: str, pmm: str) -> None:
    root = ElementTree.parse(path).getroot()
    expected = {
        "system-code-filter": system_code,
        "nfcid2-filter": nfcid2,
        "t3tPmm-filter": pmm,
    }
    for tag, value in expected.items():
        element = root.find(tag)
        if element is None or element.get(f"{ANDROID_NAMESPACE}name") != value:
            raise SystemExit(f"{path.name} does not declare {tag}={value}")


def main() -> None:
    require_hcef_metadata(HCEF_SERVICE, "4000", "02FE000000000000", "00F1000000014300")
    require_archive(APK, {
        "AndroidManifest.xml",
        "META-INF/xposed/java_init.list",
        "META-INF/xposed/module.prop",
        "META-INF/xposed/scope.list",
        "lib/arm64-v8a/libpmm.so",
    })
    with zipfile.ZipFile(APK) as archive:
        if "assets/xposed_init" in archive.namelist():
            raise SystemExit("legacy Xposed entry is still packaged")
        module_properties = archive.read("META-INF/xposed/module.prop").decode("utf-8")
        scope = archive.read("META-INF/xposed/scope.list").decode("utf-8").splitlines()
    if "minApiVersion=101" not in module_properties or "targetApiVersion=101" not in module_properties:
        raise SystemExit("APK does not target libxposed API 101")
    if scope != ["com.android.nfc"]:
        raise SystemExit(f"unexpected LSPosed scope: {scope}")
    require_archive(MODULE, set(REQUIRED_SOURCE_FILES + REQUIRED_ARCH_FILES))
    print(f"verified {APK}")
    print(f"verified {MODULE}")


if __name__ == "__main__":
    main()
