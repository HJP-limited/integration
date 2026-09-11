#!/usr/bin/env python3
"""Create E-3.4 by normalizing ordinal downstream pronoun references.

The selected ordinal candidate remains the reference target until a later
explicit search/correction/new-target turn. E-3.3 is never overwritten.
"""
import copy, json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e33.json"
DST = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e34.json"
CHANGES = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e34_reference_changes.json"
ORD = {"첫 번째":1,"두 번째":2,"세 번째":3,"네 번째":4,"다섯 번째":5,"여섯 번째":6,"일곱 번째":7,"여덟 번째":8,"아홉 번째":9,"열 번째":10,"첫째":1,"둘째":2,"셋째":3,"넷째":4}
REF = ("그 사람", "아까 그 사람", "그분", "그 사람에게", "그 사람한테")

def main():
    data = json.loads(SRC.read_text(encoding="utf-8")); out = copy.deepcopy(data); changes=[]
    for s in out["scenarios"]:
        ordinal = next(((t, p) for t in s["turns"] for phrase,p in sorted(ORD.items(), key=lambda x:-len(x[0])) if phrase in t["user"]), None)
        if not ordinal: continue
        ordinal_turn, position = ordinal; active=True
        for t in s["turns"]:
            if t["index"] <= ordinal_turn["index"]: continue
            explicit = any(c.get("tool") == "search_contacts" for c in t.get("expected_calls", [])) or (
                "찾아" in t["user"] and not any(x in t["user"] for x in REF)
            ) or "말고" in t["user"] or "다시 찾아" in t["user"]
            if explicit: active=False
            if not active or not any(x in t["user"] for x in REF): continue
            for call in t.get("expected_calls", []):
                card=call.get("args",{}).get("card_id")
                if card and card.get("cmp") == "exact":
                    old=copy.deepcopy(card); card.clear(); card.update({"cmp":"ordinal_candidate","position":position})
                    changes.append({"scenario_id":s["scenario_id"],"turn":t["index"],"tool":call["tool"],"position":position,"old":old})
    out["contract"] = dict(out.get("contract",{}), ordinal_downstream_reference="selected_ordinal_candidate_until_explicit_transition")
    DST.write_text(json.dumps(out,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    CHANGES.write_text(json.dumps({"source":str(SRC.relative_to(ROOT)),"changes":changes},ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({"changes":len(changes),"output":str(DST),"change_log":str(CHANGES)}))
if __name__ == "__main__": main()
