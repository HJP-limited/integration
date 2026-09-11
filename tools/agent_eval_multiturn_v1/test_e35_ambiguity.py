import json, unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
E34 = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e34.json"
E35 = ROOT / "tools/agent_eval_multiturn_v1/data/eval_set_v1_e35.json"

class E35AmbiguityContractTest(unittest.TestCase):
    def setUp(self):
        self.e34=json.loads(E34.read_text(encoding='utf-8'))
        self.e35=json.loads(E35.read_text(encoding='utf-8'))
        self.a={s['scenario_id']:s for s in self.e34['scenarios']}
        self.b={s['scenario_id']:s for s in self.e35['scenarios']}

    def test_inventory_and_control_invariants(self):
        self.assertEqual(self.e35['scenario_count'],400)
        self.assertEqual(sum(len(s['turns']) for s in self.e35['scenarios']),1918)
        # Existing ordinal control remains ordinal and is not ambiguity-normalized.
        self.assertEqual(self.b['DEV-0020']['turns'][2]['expected_calls'][0]['args']['card_id'], {'cmp':'ordinal_candidate','position':2})
        for sid in ('DEV-0023','DEV-0038'):
            self.assertIn('ordinal_candidate', str(self.b[sid]['turns']))

    def test_unresolved_replacement_is_clarification(self):
        for sid in ('DEV-0083','DEV-0087','DEV-0099','DEV-0115','REG-0015','REG-0047','REG-0053','HLD-0017','HLD-0025','HLD-0033','HLD-0099','HLD-0105','HLD-0123','HLD-0165','HLD-0167','HLD-0189','HLD-0195'):
            s=self.b[sid]
            self.assertNotIn('final_target_card_id', s['success'])
            refs=[t for t in s['turns'] if '그 사람' in t.get('user','')]
            self.assertTrue(refs, sid)
            self.assertTrue(any(t['decision_kind']=='NO_TOOL' and t['expected_calls']==[] for t in refs), sid)

    def test_non_ambiguity_unchanged(self):
        for sid, old in self.a.items():
            new=self.b[sid]
            if sid not in {'DEV-0083','DEV-0087','DEV-0099','DEV-0115','REG-0015','REG-0047','REG-0053','HLD-0017','HLD-0025','HLD-0033','HLD-0099','HLD-0105','HLD-0123','HLD-0165','HLD-0167','HLD-0189','HLD-0195'}:
                self.assertEqual(old['turns'],new['turns'],sid)

if __name__=='__main__': unittest.main()
