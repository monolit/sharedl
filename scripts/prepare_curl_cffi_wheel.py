#!/usr/bin/env python3
"""Prepare curl-cffi's Android CPython 3.13 wheel for Chaquopy.

Upstream curl-cffi 0.17.0 requires cffi>=2.1.1 because it also supports
CPython 3.15. CFFI 2.1.1 is needed for the CPython 3.15 ABI change; this app
embeds CPython 3.13, for which Chaquopy supplies CFFI 1.17.1. CFFI 1.17.1
and 2.1.1 accept the same generated-module ABI version range. This script
changes only the wheel metadata requirement, preserves the upstream binary,
uses a local version identifier, and regenerates RECORD.
"""

from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import io
import re
import urllib.request
import zipfile
from pathlib import Path

UPSTREAM_VERSION = "0.17.0"
LOCAL_VERSION = "0.17.0+sharedl1"
WHEEL_TAG = "cp313-cp313-android_24_arm64_v8a"
UPSTREAM_FILENAME = f"curl_cffi-{UPSTREAM_VERSION}-{WHEEL_TAG}.whl"
OUTPUT_FILENAME = f"curl_cffi-{LOCAL_VERSION}-{WHEEL_TAG}.whl"
UPSTREAM_URL = (
    "https://github.com/lexiforest/curl_cffi/releases/download/v0.17.0/"
    + UPSTREAM_FILENAME
)
# SHA-256 published by the immutable upstream GitHub release v0.17.0.
UPSTREAM_SHA256 = "ae0442e45d6e0c987f84cb88bd6a99d7b91c8e2b017c18048a6c4fed63847c80"


def download_upstream_wheel() -> bytes:
    request = urllib.request.Request(
        UPSTREAM_URL,
        headers={"User-Agent": "ShareDL-build/1.0", "Accept": "application/octet-stream"},
    )
    with urllib.request.urlopen(request, timeout=180) as response:
        data = response.read()

    actual = hashlib.sha256(data).hexdigest()
    if actual != UPSTREAM_SHA256:
        raise RuntimeError(
            f"Upstream wheel SHA-256 mismatch: expected {UPSTREAM_SHA256}, got {actual}"
        )
    return data


def patch_wheel(data: bytes) -> bytes:
    with zipfile.ZipFile(io.BytesIO(data), "r") as source:
        metadata_paths = [
            name for name in source.namelist()
            if name.endswith(".dist-info/METADATA")
        ]
        if len(metadata_paths) != 1:
            raise RuntimeError(f"Expected one METADATA file, got {metadata_paths}")

        old_dist_info = metadata_paths[0].rsplit("/", 1)[0]
        new_dist_info = f"curl_cffi-{LOCAL_VERSION}.dist-info"
        entries: dict[str, bytes] = {}

        for name in source.namelist():
            if name.endswith("/") or name.endswith(".dist-info/RECORD"):
                continue

            new_name = name.replace(old_dist_info, new_dist_info, 1)
            content = source.read(name)

            if name == metadata_paths[0]:
                metadata = content.decode("utf-8")
                metadata, count = re.subn(
                    rf"(?m)^Version: {re.escape(UPSTREAM_VERSION)}$",
                    f"Version: {LOCAL_VERSION}",
                    metadata,
                    count=1,
                )
                if count != 1:
                    raise RuntimeError("Could not update wheel Version metadata")

                metadata, count = re.subn(
                    r"(?m)^Requires-Dist: cffi\s*>=\s*2\.1\.1\s*$",
                    "Requires-Dist: cffi>=1.17.1",
                    metadata,
                    count=1,
                )
                if count != 1:
                    raise RuntimeError("Expected upstream Requires-Dist: cffi>=2.1.1")
                content = metadata.encode("utf-8")

            entries[new_name] = content

    record_path = f"{new_dist_info}/RECORD"
    record = io.StringIO(newline="")
    writer = csv.writer(record, lineterminator="\n")
    for name, content in sorted(entries.items()):
        digest = base64.urlsafe_b64encode(
            hashlib.sha256(content).digest()
        ).rstrip(b"=").decode("ascii")
        writer.writerow((name, f"sha256={digest}", str(len(content))))
    writer.writerow((record_path, "", ""))
    entries[record_path] = record.getvalue().encode("utf-8")

    output = io.BytesIO()
    with zipfile.ZipFile(
        output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
    ) as target:
        for name, content in sorted(entries.items()):
            target.writestr(name, content)

    patched = output.getvalue()
    with zipfile.ZipFile(io.BytesIO(patched), "r") as check:
        bad_member = check.testzip()
        if bad_member:
            raise RuntimeError(f"Invalid member in patched wheel: {bad_member}")
        metadata_name = next(
            name for name in check.namelist()
            if name.endswith(".dist-info/METADATA")
        )
        metadata = check.read(metadata_name).decode("utf-8")
        if f"Version: {LOCAL_VERSION}" not in metadata:
            raise RuntimeError("Patched wheel version verification failed")
        if "Requires-Dist: cffi>=1.17.1" not in metadata:
            raise RuntimeError("Patched CFFI requirement verification failed")
    return patched


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output_path = args.output_dir / OUTPUT_FILENAME

    if output_path.is_file() and output_path.stat().st_size:
        print(f"Using cached wheel: {output_path}")
        return

    print(f"Downloading upstream {UPSTREAM_FILENAME}")
    patched = patch_wheel(download_upstream_wheel())
    output_path.write_bytes(patched)
    print(f"Prepared Chaquopy wheel: {output_path}")


if __name__ == "__main__":
    main()
