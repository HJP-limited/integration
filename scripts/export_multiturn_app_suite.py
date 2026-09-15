"""Export the current upstream generator, not the historical 130/139-scenario JSONs."""
import hashlib
import json
import os
from pathlib import Path
import random
import subprocess
import sys


def main():
    # The original generator also uses sets. Freeze their iteration order as well as RNG.
    if os.environ.get("PYTHONHASHSEED") != "0":
        return subprocess.call([sys.executable, __file__, *sys.argv[1:]],
                               env={**os.environ, "PYTHONHASHSEED": "0"})
    import eval_multiturn as source
    cards_bytes = source.CARDS_PATH.read_bytes()
    scenarios = source.build_scenarios(json.loads(cards_bytes), random.Random(42), "v1")
    turns = sum(len(s["turns"]) for s in scenarios)
    if (len(scenarios), turns) != (165, 435):
        raise RuntimeError(f"Unexpected current suite: {len(scenarios)}/{turns}")
    payload = {
        "suite_id": "ryeong-dynamic-v1-165", "seed": 42, "python_hash_seed": 0,
        "scenario_count": len(scenarios), "turn_count": turns,
        "generator_sha256": hashlib.sha256(Path(source.__file__).read_bytes()).hexdigest(),
        "cards_sha256": hashlib.sha256(cards_bytes).hexdigest(),
        "scenarios": scenarios,
    }
    output = source.REPO / "app/src/androidTest/assets/multiturn/current_165.json"
    text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
    if "--check" in sys.argv:
        if not output.exists() or output.read_text(encoding="utf-8") != text:
            raise RuntimeError("Current app suite is stale; run scripts/export_multiturn_app_suite.py")
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(text, encoding="utf-8")
    print(f"current suite: {len(scenarios)} scenarios / {turns} turns; {'checked' if '--check' in sys.argv else 'exported'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
