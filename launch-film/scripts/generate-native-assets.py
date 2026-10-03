#!/usr/bin/env python3
"""Prepare and verify Kotlin/Compose screen exports; rendering is done by Gradle in CI."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess


def git_revision(root: Path) -> str:
    return subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()


def prepare(tv: Path, mobile: Path, output: Path, github_env: Path | None) -> None:
    source = tv / "launch-film" / "native"
    fixture_dir = tv / ".render-cache" / "native-fixtures"
    fixture_dir.mkdir(parents=True, exist_ok=True)
    catalogue_file = tv / "advertising-video" / "catalogue.json"
    catalogue = json.loads(catalogue_file.read_text(encoding="utf-8"))
    shutil.copy2(catalogue_file, fixture_dir / "catalogue.json")
    for item in catalogue:
        for key in ("backdrop_file", "poster_file"):
            name = item[key]
            shutil.copy2(tv / "advertising-video" / "assets" / name, fixture_dir / name)
    for name in ("squid-game.jpg", "squid-game-logo.png"):
        shutil.copy2(mobile / "app" / "src" / "test" / "resources" / "artwork" / name, fixture_dir / name)
    for root, name in ((tv, "LaunchFilmTvCaptureTest.kt"), (mobile, "LaunchFilmMobileCaptureTest.kt")):
        target = root / "app" / "src" / "test" / "java" / "com" / "example" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / name, target)
    output.mkdir(parents=True, exist_ok=True)
    values = {"NPRO_NATIVE_CAPTURE": "1", "NPRO_NATIVE_FIXTURES": str(fixture_dir.resolve()), "NPRO_NATIVE_OUTPUT": str(output.resolve())}
    if github_env:
        with github_env.open("a", encoding="utf-8") as handle:
            for key, value in values.items():
                handle.write(f"{key}={value}\n")
    (output / "source-revisions.json").write_text(json.dumps({"tv": git_revision(tv), "mobile": git_revision(mobile)}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(values, indent=2))


def png_size(path: Path) -> tuple[int, int]:
    with path.open("rb") as handle:
        header = handle.read(24)
    if len(header) != 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"Invalid PNG export: {path}")
    return struct.unpack(">II", header[16:24])


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(output: Path) -> None:
    sequences = {}
    for name, expected_ratio, minimum_width in (("tv-home", 16 / 9, 2500), ("mobile-home", 412 / 895, 1200), ("mobile-details", 412 / 895, 1200)):
        frames = sorted((output / name).glob("*.png"))
        if [path.name for path in frames] != [f"{index:05d}.png" for index in range(60)]:
            raise ValueError(f"{name}: expected exactly 60 consecutive frames")
        size = png_size(frames[0])
        if size[0] < minimum_width or abs(size[0] / size[1] - expected_ratio) > .015:
            raise ValueError(f"{name}: unexpected native size {size}")
        if any(png_size(path) != size for path in frames):
            raise ValueError(f"{name}: changing dimensions")
        hashes = [digest(path) for path in frames]
        if len(set(hashes)) < 5:
            raise ValueError(f"{name}: capture did not export moving UI")
        sequences[name] = {"width": size[0], "height": size[1], "fps": 15, "durationSeconds": 4, "frameCount": 60,
                           "uniqueFrames": len(set(hashes)), "pattern": f"screens/{name}/%05d.png", "firstFrameSha256": hashes[0], "lastFrameSha256": hashes[-1]}
    stills = {}
    for name in ("tv-home", "mobile-home", "mobile-details"):
        file = output / f"{name}.png"
        size = png_size(file)
        stills[name] = {"width": size[0], "height": size[1], "sha256": digest(file)}
    revisions_file = output / "source-revisions.json"
    manifest = {"renderer": "Robolectric 34 native Skia / Roborazzi / production Kotlin Jetpack Compose",
                "profile": "Synthetic Home profile; offline fixture catalogue; no real account data",
                "sourceRevisions": json.loads(revisions_file.read_text()) if revisions_file.exists() else {},
                "sequences": sequences, "stills": stills}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tv-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--mobile-root", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--github-env", type=Path)
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    output = args.output or args.tv_root / "launch-film" / "public" / "screens"
    if args.prepare:
        if not args.mobile_root:
            parser.error("--prepare requires --mobile-root")
        prepare(args.tv_root, args.mobile_root, output, args.github_env)
    if args.verify:
        verify(output)
    if not (args.prepare or args.verify):
        parser.error("Choose --prepare or --verify")


if __name__ == "__main__":
    main()
