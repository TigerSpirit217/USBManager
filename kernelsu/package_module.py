#!/usr/bin/env python3
"""Build the Android APK and package a flashable KernelSU module ZIP."""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
KERNELSU_DIR = ROOT / "kernelsu"
MODULE_DIR = KERNELSU_DIR / "module"
APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"


def module_version() -> str:
    for line in (MODULE_DIR / "module.prop").read_text(encoding="utf-8").splitlines():
        if line.startswith("version="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("module.prop does not contain a version field")


def build_apk() -> None:
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run(
        [str(wrapper), ":app:assembleDebug", "--console=plain"],
        cwd=ROOT,
        check=True,
        shell=os.name == "nt",
    )
    if not APK.is_file():
        raise FileNotFoundError(f"Built APK not found: {APK}")


def package() -> Path:
    version = module_version()
    output = KERNELSU_DIR / f"USBManager-KernelSU-{version}.zip"
    with tempfile.TemporaryDirectory(prefix="usbmanager-") as temp_dir:
        stage = Path(temp_dir)
        for source in MODULE_DIR.iterdir():
            if source.is_file():
                shutil.copy2(source, stage / source.name)
        shutil.copy2(APK, stage / "usbmanager.apk")

        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for source in sorted(stage.iterdir()):
                info = zipfile.ZipInfo(source.name)
                info.create_system = 3
                mode = 0o755 if source.suffix == ".sh" else 0o644
                info.external_attr = (mode & 0xFFFF) << 16
                archive.writestr(info, source.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
    return output


if __name__ == "__main__":
    build_apk()
    print(package())
