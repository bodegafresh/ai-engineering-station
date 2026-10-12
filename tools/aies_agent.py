#!/usr/bin/env python3
"""v0.5: local model proposes a single tool; human approves; gateway executes.
No autonomous execution, no shell access, no external URLs.
"""
import argparse
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(os.environ.get('AIES_WORKSPACE', os.getcwd())).resolve()
OLLAMA = os.environ.get('AIES_MODEL_BASE_URL', 'http://127.0.0.1:11434').rstrip('/')
MODEL = os.environ.get('AIES_MODEL_NAME', 'qwen2.5-coder:7b')
GATEWAY = os.environ.get('AIES_GATEWAY_URL', 'http://127.0.0.1:8080').rstrip('/')
ALLOWED = {'browser.inspect', 'diagram.render'}

def validate(action):
    if not isinstance(action, dict) or action.get('tool') not in ALLOWED:
        raise ValueError('Tool not allowed')
    if action['tool'] == 'browser.inspect':
        from urllib.parse import urlparse
        u = urlparse(action.get('url', ''))
        if u.scheme not in ('http', 'https') or u.hostname not in ('localhost', '127.0.0.1', '::1') or u.username or u.password:
            raise ValueError('Only explicit loopback URLs allowed')
    else:
        src = (ROOT / action.get('source', '')).resolve()
        if ROOT not in src.parents or not src.is_file() or src.suffix not in ('.puml', '.plantuml'):
            raise ValueError('Source must be an existing PlantUML file in workspace')
    out = (ROOT / action.get('output', '')).resolve()
    artifact_root = (ROOT / '.aies/artifacts').resolve()
    if artifact_root not in out.parents:
        raise ValueError('Output must be under .aies/artifacts')
    if action['tool'] == 'browser.inspect' and out.suffix != '.png':
        raise ValueError('Browser output must be PNG')
    if action['tool'] == 'diagram.render' and out.suffix not in ('.png', '.svg'):
        raise ValueError('Diagram output must be PNG or SVG')
    return {k: action[k] for k in ('tool','url','source','output') if k in action}

def propose(goal):
    prompt = ('Return ONLY one JSON object. No markdown. Choose exactly one tool from browser.inspect and diagram.render. '
              'For browser.inspect use keys tool,url,output and only http://127.0.0.1:8765/ with output .aies/artifacts/v05-browser.png. '
              'For diagram.render use keys tool,source,output with source examples/architecture.puml '
              'and output .aies/artifacts/v05-diagram.svg. Do not invent tools or paths. User goal: ' + goal)
    payload = {'model':MODEL,'stream':False,'messages':[{'role':'user','content':prompt}], 'options':{'num_predict':200,'temperature':0}}
    req = Request(OLLAMA + '/v1/chat/completions', data=json.dumps(payload).encode(), headers={'Content-Type':'application/json'})
    with urlopen(req, timeout=120) as resp:
        text = json.load(resp)['choices'][0]['message']['content']
    text = text.strip()
    if text.startswith('```'):
        text = text.split('\n',1)[1].rsplit('```',1)[0].strip()
    return validate(json.loads(text))

def execute(action):
    token = os.environ.get('AIES_TOOLS_TOKEN', '')
    if len(token) < 24: raise ValueError('AIES_TOOLS_TOKEN missing or too short')
    data = dict(validate(action), approved=True)
    req = Request(GATEWAY + '/api/v1/tools/execute', data=json.dumps(data).encode(),
                  headers={'Content-Type':'application/json','X-AIES-Tool-Token':token})
    with urlopen(req, timeout=75) as resp: return json.load(resp)

def main():
    p=argparse.ArgumentParser()
    sub=p.add_subparsers(dest='cmd',required=True)
    s=sub.add_parser('propose'); s.add_argument('goal')
    s=sub.add_parser('execute'); s.add_argument('--proposal',default='.aies/proposal-v05.json'); s.add_argument('--approve',action='store_true')
    a=p.parse_args()
    if a.cmd == 'propose':
        action=propose(a.goal)
        dest=ROOT/'.aies/proposal-v05.json'; dest.parent.mkdir(parents=True,exist_ok=True)
        dest.write_text(json.dumps(action,indent=2,ensure_ascii=False)+'\n')
        print(json.dumps({'proposed':action,'file':str(dest),'executed':False},ensure_ascii=False,indent=2))
    else:
        if not a.approve: p.error('Human approval required: --approve')
        action=json.loads((ROOT/a.proposal).read_text())
        print(json.dumps(execute(action),ensure_ascii=False,indent=2))
if __name__ == '__main__': main()
