#!/usr/bin/env python3
"""Create E-3.5 by normalizing evidence-backed unresolved ambiguity.
Uses persisted candidate multiplicity from the existing evaluation artifact only to
identify the predeclared 2+ candidate contract class; it never copies an observed
card_id into Gold.
"""
import argparse, copy,json,re,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
src=ROOT/'tools/agent_eval_multiturn_v1/data/eval_set_v1_e34.json'
out=ROOT/'tools/agent_eval_multiturn_v1/data/eval_set_v1_e35.json'
log=ROOT/'tools/agent_eval_multiturn_v1/data/eval_set_v1_e35_ambiguity_changes.json'
parser=argparse.ArgumentParser(description='Normalize evidence-backed unresolved ambiguity Gold')
parser.add_argument('--raw', required=True, help='Existing raw trace JSONL used only to identify persisted 2+ candidate cases')
args=parser.parse_args()
raw=args.raw
ref_re=re.compile(r'(그 사람|아까 그|그분)')
ord_re=re.compile(r'(첫|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\s*번째|첫째|둘째|셋째|넷째')
new_target_re=re.compile(r'(찾아줘|찾아|로 다시|말고|다른|새로)')

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()

g=json.loads(src.read_text(encoding='utf-8')); rawmap={d['scenario_id']:d for d in map(json.loads,open(raw,encoding='utf-8'))}
changes=[]
for s in g['scenarios']:
    if 'correction' not in s.get('traits',[]): continue
    rd=rawmap.get(s['scenario_id']);
    if not rd: continue
    # Candidate-producing replacement search: after correction and before immediate ref.
    cand_turns=[t for t in rd.get('turns',[]) if t.get('index',0)>=3 and len(t.get('candidate_card_ids') or [])>=2]
    if not cand_turns: continue
    gold_by={t['index']:t for t in s['turns']}
    # Only ambiguity references after replacement search, before a new explicit target/search.
    for ct in sorted(cand_turns,key=lambda x:x['index']):
        for t in s['turns']:
            if t['index']<=ct['index']: continue
            u=t.get('user','')
            if new_target_re.search(u) and not ref_re.search(u): break
            if not ref_re.search(u) or ord_re.search(u): continue
            calls=t.get('expected_calls',[])
            exact=[c for c in calls if c.get('tool')=='get_contact' and c.get('args',{}).get('card_id',{}).get('cmp')=='exact']
            if not exact: continue
            old_calls=copy.deepcopy(calls)
            t['expected_calls']=[]; t['primary_sequence']=[]; t['allowed_tool_sequences']=[[]]; t['decision_kind']='NO_TOOL'
            t['note']='2+ persisted candidates without explicit selection: clarification required'
            # Keep forbidden tools; remove only target-specific success constraints below.
            changes.append({'scenario_id':s['scenario_id'],'turn':t['index'],'candidate_count':len(ct['candidate_card_ids']),'old_expected_calls':old_calls,'new_expected_calls':[],'reason':'2+ candidates and no explicit ordinal/name/card selection'})
        break
    if any(c['scenario_id']==s['scenario_id'] for c in changes):
        s['success'].pop('final_target_card_id',None); s['success'].pop('must_not_final_target',None)
        # Preserve required search and side-effect policy; express unresolved state.
        s['success']['clarification_expected_turns']=sorted(set(s['success'].get('clarification_expected_turns',[])+[c['turn'] for c in changes if c['scenario_id']==s['scenario_id']]))
        s['contract_notes']='Policy A: replacement search with 2+ candidates and no explicit selection remains unresolved; clarification/NO_TOOL is required.'

g['contract_revision']='E-3.5'
g['source_contract']='E-3.4'
g['source_sha256']=sha(src)
g['normalization']='Unresolved 2+ candidate ambiguity: no static target/get_contact until explicit selection.'
g['normalization_change_count']=len(changes)
out.write_text(json.dumps(g,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
log.write_text(json.dumps({'source':'eval_set_v1_e34.json','source_sha256':sha(src),'output':'eval_set_v1_e35.json','output_sha256':sha(out),'change_count':len(changes),'changes':changes},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('source_sha256',sha(src)); print('output_sha256',sha(out)); print('scenarios',g['scenario_count'],'turns',sum(len(s['turns']) for s in g['scenarios']),'changes',len(changes),'scenario_count_changed',len({c['scenario_id'] for c in changes}))
for c in changes: print(c['scenario_id'],c['turn'],c['candidate_count'])
