#!/usr/bin/env python3
"""Certify an explicitly supplied installed renderer JAR; never infer a version from a pin."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


def certify(path: Path, mod_id: str) -> dict:
    if mod_id not in ("sodium", "embeddium"):
        raise ValueError("Expected Sodium or Embeddium")
    path = path.resolve(strict=True)
    if not path.is_file() or path.suffix.lower() != ".jar":
        raise ValueError("Expected an installed renderer JAR")
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        version = None
        if "fabric.mod.json" in names:
            metadata = json.loads(archive.read("fabric.mod.json"))
            if metadata.get("id") == mod_id:
                version = metadata.get("version")
        for manifest in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
            if version or manifest not in names:
                continue
            text = archive.read(manifest).decode("utf-8")
            # Bind version to the same [[mods]] block as the requested mod ID.
            for block in re.split(r"(?=\\[\\[mods\\]\\])", text):
                ident = re.search(r'^\\s*modId\\s*=\\s*["\\\']([^"\\\']+)', block, re.M)
                if ident and ident.group(1) == mod_id:
                    match = re.search(r'^\\s*version\\s*=\\s*["\\\']([^"\\\']+)', block, re.M)
                    version = match.group(1) if match else None
                    break
        if not isinstance(version, str) or not version.strip() or "${" in version:
            raise ValueError(f"Cannot certify concrete {mod_id} version from installed JAR metadata: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"modId": mod_id, "version": version, "sha256": digest.hexdigest(),
            "installedJar": str(path), "bytes": path.stat().st_size}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True, help="Path to JAR from the launched client's resolved classpath")
    parser.add_argument("--mod-id", choices=("sodium", "embeddium"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    receipt = certify(args.jar, args.mod_id)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(receipt, indent=2) + "\\n", encoding="utf-8")
    print(json.dumps(receipt))


if __name__ == "__main__":
    main()
