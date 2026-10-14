from __future__ import annotations

from pathlib import Path

REQUIRED_SOURCE_FILES = (
    "module.prop",
    "customize.sh",
    "service.sh",
)

REQUIRED_ARCH_FILES = (
    "bin/aicecho_injector",
    "system/vendor/lib64/libaicecho_pmm.so",
)


def validate_source(root: Path) -> list[str]:
    return [name for name in REQUIRED_SOURCE_FILES if not (root / name).is_file()]


def validate_staged(root: Path) -> list[str]:
    return [name for name in (*REQUIRED_SOURCE_FILES, *REQUIRED_ARCH_FILES)
            if not (root / name).is_file()]
