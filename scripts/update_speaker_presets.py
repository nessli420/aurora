"""Refresh only the measured speaker index; EQ filters remain hosted upstream.

Usage: python scripts/update_speaker_presets.py <Spinorama commit SHA>
Then update SPEAKER_RAW_BASE in AutoEqRepository.kt to the same SHA.
"""
import json
import re
import sys
from pathlib import Path
from urllib.request import Request, urlopen


def main():
    revision = sys.argv[1] if len(sys.argv) == 2 else ""
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise SystemExit("Pass the full 40-character Spinorama commit SHA.")
    request = Request(
        f"https://api.github.com/repos/pierreaubert/spinorama/git/trees/{revision}?recursive=1",
        headers={"User-Agent": "Aurora-preset-index"},
    )
    with urlopen(request, timeout=45) as response:
        tree = json.load(response)
    if tree.get("truncated") or tree.get("sha") != revision:
        raise SystemExit("Upstream tree was incomplete or did not match the requested revision.")
    paths = sorted(
        item["path"] for item in tree["tree"]
        if re.fullmatch(r"datas/eq/[^/]+/iir-autoeq\.txt", item["path"])
        and item["type"] == "blob" and item["mode"] != "120000"
    )
    if not paths:
        raise SystemExit("No measured EQ files found; keeping the existing index.")
    lines = [
        f"# Spinorama measured speaker EQ; revision {revision}",
        "# Model<TAB>Source<TAB>Path; only actual iir-autoeq.txt files, no symlinks",
        *(f"{path.split('/')[2]}\tSpinorama\t{path}" for path in paths),
    ]
    output = Path(__file__).resolve().parents[1] / "app/src/main/assets/spinorama_index.tsv"
    staging = output.with_suffix(".tsv.tmp")
    staging.write_text("\n".join(lines) + "\n", encoding="utf-8")
    staging.replace(output)
    print(f"Indexed {len(paths)} speaker presets at {revision}.")
    print("Update SPEAKER_RAW_BASE in AutoEqRepository.kt to the same commit.")


if __name__ == "__main__":
    main()
