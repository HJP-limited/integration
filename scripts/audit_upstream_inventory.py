"""Offline production-source inventory, not a claim of behavioral parity or model execution."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess

REPO = Path(__file__).resolve().parents[1]
SOURCES = {"agent": "c514105", "search_multiturn": "734c63b", "ocr": "010d3b1"}


def git(*args, input_bytes=None):
    result = subprocess.run(
        ["git", "-c", f"safe.directory={REPO.as_posix()}", *args], cwd=REPO,
        input=input_bytes, capture_output=True,
    )
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip())
    return result.stdout


def production(path):
    return "/src/main/" in "/" + path and PurePosixPath(path).suffix in {".kt", ".java"}


def digest(data):
    return hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="build/upstream-source-inventory.json")
    args = parser.parse_args()
    output = (REPO / args.output).resolve()
    if not output.is_relative_to(REPO / "build"):
        parser.error("output must stay under this repository's build directory")
    local = sorted(set(p for p in git("ls-files", "--cached", "--others", "--exclude-standard").decode().splitlines() if production(p)))
    stems = defaultdict(list)
    for path in local:
        stems[PurePosixPath(path).stem].append(path)
    local_hashes = {path: digest((REPO / path).read_bytes()) for path in local if (REPO / path).is_file()}
    replacements = json.loads((REPO / "docs/upstream_replacements.json").read_text(encoding="utf-8"))
    for name, mapping in replacements.items():
        if not mapping.get("reason") or not mapping.get("targets"):
            raise RuntimeError(f"Incomplete replacement mapping: {name}")
        for target in mapping["targets"]:
            if target not in local_hashes:
                raise RuntimeError(f"Replacement target missing: {name} -> {target}")
    report = {"scope": "tracked upstream src/main Kotlin/Java only; excludes native code, assets, manifests, build wiring and scripts",
              "behavioral_parity_verified": False, "sources": []}
    for name, revision in SOURCES.items():
        # Missing source objects are a hard error. Never report an empty inventory as success.
        commit = git("rev-parse", "--verify", revision + "^{commit}").decode().strip()
        paths = [p for p in git("ls-tree", "-r", "--name-only", commit).decode().splitlines() if production(p)]
        if not paths:
            raise RuntimeError(f"No production sources found: {name}")
        blobs = git("cat-file", "--batch", input_bytes="".join(f"{commit}:{p}\n" for p in paths).encode())
        cursor = 0
        entries = []
        for path in paths:
            end = blobs.index(b"\n", cursor)
            header = blobs[cursor:end].split()
            if len(header) != 3 or header[1] != b"blob":
                raise RuntimeError(f"Missing source blob: {name}:{path}")
            size = int(header[2])
            data = blobs[end + 1:end + 1 + size]
            cursor = end + 1 + size + 1
            candidates = [path] if path in local_hashes else stems[PurePosixPath(path).stem]
            source_hash = digest(data)
            if not candidates:
                status = "no_same_stem_requires_mapping_review"
            elif len(candidates) > 1:
                status = "ambiguous_requires_mapping_review"
            elif local_hashes.get(candidates[0]) == source_hash:
                status = "same_content_lf_normalized"
            else:
                status = "changed_requires_behavior_review"
            entries.append({"upstream": path, "upstream_blob": header[0].decode(),
                            "status": status, "candidates": candidates,
                            "candidate_sha256": {p: local_hashes.get(p) for p in candidates},
                            "replacement_review": replacements.get(PurePosixPath(path).stem) if not candidates else None})
        counts = dict(Counter(e["status"] for e in entries))
        report["sources"].append({"name": name, "commit": commit, "counts": counts, "files": entries})
        print(f"{name}: {len(entries)} files; {json.dumps(counts, ensure_ascii=False)}")
        unresolved = [e["upstream"] for e in entries if not e["candidates"] and not e["replacement_review"]]
        if unresolved:
            raise RuntimeError(f"Unmapped source files: {unresolved}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Inventory written; differences and name matches are NOT proof of parity.")


if __name__ == "__main__":
    main()
