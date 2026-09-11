#!/usr/bin/env python3
import json, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
E32 = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e32.json"
E33 = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e33.json"
E34 = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e34.json"
E35 = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e35.json"

class E33OrdinalContractTest(unittest.TestCase):
    def setUp(self):
        self.e32 = json.loads(E32.read_text(encoding="utf-8"))
        self.e33 = json.loads(E33.read_text(encoding="utf-8"))
        self.s32 = {s["scenario_id"]: s for s in self.e32["scenarios"]}
        self.s33 = {s["scenario_id"]: s for s in self.e33["scenarios"]}

    def test_inventory_and_non_ordinal_turns_preserved(self):
        self.assertEqual(self.e33["scenario_count"], 400)
        self.assertEqual(sum(len(s["turns"]) for s in self.e33["scenarios"]), 1918)
        for sid, old in self.s32.items():
            new = self.s33[sid]
            for ot, nt in zip(old["turns"], new["turns"]):
                if not any(w in ot["user"] for w in ("첫 번째", "두 번째", "세 번째", "네 번째", "다섯 번째", "여섯 번째", "일곱 번째", "여덟 번째", "아홉 번째", "열 번째", "첫째", "둘째", "셋째", "넷째")):
                    self.assertEqual(ot.get("expected_calls"), nt.get("expected_calls"), (sid, ot["index"]))

    def test_static_ordinal_cards_are_normalized(self):
        for scenario in self.e33["scenarios"]:
            for turn in scenario["turns"]:
                if not any(w in turn["user"] for w in ("첫 번째", "두 번째", "세 번째", "네 번째", "다섯 번째", "여섯 번째", "일곱 번째", "여덟 번째", "아홉 번째", "열 번째", "첫째", "둘째", "셋째", "넷째")):
                    continue
                for call in turn.get("expected_calls", []):
                    card = call.get("args", {}).get("card_id")
                    if card and card.get("cmp") == "exact":
                        self.fail((scenario["scenario_id"], turn["index"], card))

    def test_known_controls_and_e33_ambiguity_unchanged(self):
        t20 = next(t for t in self.s33["DEV-0020"]["turns"] if t["index"] == 3)
        self.assertEqual(t20["expected_calls"][0]["args"]["card_id"], {"cmp": "ordinal_candidate", "position": 2})
        self.assertEqual(self.s33["DEV-0083"]["success"], self.s32["DEV-0083"]["success"])

if __name__ == "__main__":
    unittest.main()
