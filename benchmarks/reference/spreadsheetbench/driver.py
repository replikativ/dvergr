"""SpreadsheetBench's reference harness (inference/inference_single.py and
inference/inference_multiple.py of RUCKBReasoning/SpreadsheetBench) on the verified_400
tasks, with the model called through Dvergr's model layer (bridge.clj).

Adapted from SpreadsheetBench (https://github.com/RUCKBReasoning/SpreadsheetBench),
commit 49b73a94775fb489063f60ca1865e3a650079a79, CC BY-SA 4.0; this adaptation is
distributed under the same license (https://creativecommons.org/licenses/by-sa/4.0/).

Taken from the upstream checkout (fetch.sh) at run time, unmodified: the prompts
(inference/prompt_format.py), the spreadsheet preview gen_file_content
(inference/inference_multiple.py, 5 rows), and code extraction and execution feedback
(extract_code / exec_code, inference/code_exec.py). The functions are read with `ast`, so
their code runs as written without importing upstream's modules (which need openai and
the Jupyter kernel gateway client). Also as upstream: the message list (alternating
user/assistant strings, no system prompt, llm_api.py), max_turn_num 5, the stop rule
(stop once the output file exists), 'Error occur when running code.' on an executor
failure, and the output path given in the prompt (/mnt/data/outputs/<setting>_<model>/...).

Changed, and why:
- The LLM call goes through bridge.clj (Dvergr's model layer) instead of llm_api.py's
  OpenAI client: no temperature or other parameters are set.
- Code runs in a per-task Docker container (image sbref-exec:1, exec/): no network,
  read-only root, non-root, and only the task's input file mounted at /mnt/data. Upstream
  runs one Jupyter kernel gateway (conv_id EVAL) shared by all tasks, with the whole
  dataset directory mounted, answers included. Here each task gets a fresh IPython kernel.
- verified_400 names its files 1_<id>_init.xlsx (or initial.xlsx); upstream's scripts
  still expect the earlier 1_<id>_input.xlsx. The output is 1_<id>_output.xlsx as upstream.
- The multi-round output folder is created (upstream creates only single_<model>).
- The single-round run executes the reply's code once, as inference_single.py does; it does
  not re-run the solution on test cases 2 and 3 (verified_400 has one test case per task).

usage: python3 driver.py SETTING TAG [IDS_FILE]
  SETTING: single | row_react_exec | react_exec | row_exec
  TAG: a name for this run; results go to $SBREF_OUT/TAG (default
       ~/.cache/dvergr-bench/sbref/TAG): results/<id>.json, outputs/<id>.xlsx
  IDS_FILE: task ids, default ids.txt beside this file
Environment:
  MODEL                dvergr.model.registry id (default codex-subscription-luna)
  MODEL_LABEL          the <model> part of the output folder named in the prompt (default luna)
  SPREADSHEETBENCH_ROOT  verified_400 (default ~/.cache/dvergr-bench/spreadsheetbench/data/spreadsheetbench_verified_400)
  SPREADSHEETBENCH_REPO  upstream checkout (default ~/.cache/dvergr-bench/spreadsheetbench/repo)
  SBREF_STAGE          where each task's container mount is staged (default ~/sbref-stage;
                       Docker installed as a snap can mount only non-hidden dirs under $HOME)
  SBREF_IMAGE          executor image (default sbref-exec:1)
  SBREF_PARALLEL       tasks in flight (default 4)
"""
import ast, concurrent.futures as cf, json, os, shutil, subprocess, sys, threading, time

import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..', '..'))   # the dvergr checkout
env = lambda k, d: os.environ.get(k) or d
REPO = os.path.expanduser(env('SPREADSHEETBENCH_REPO', '~/.cache/dvergr-bench/spreadsheetbench/repo'))
DATA = os.path.expanduser(env('SPREADSHEETBENCH_ROOT',
                              '~/.cache/dvergr-bench/spreadsheetbench/data/spreadsheetbench_verified_400'))
OUT = os.path.expanduser(env('SBREF_OUT', '~/.cache/dvergr-bench/sbref'))
STAGE = os.path.expanduser(env('SBREF_STAGE', '~/sbref-stage'))
IMAGE = env('SBREF_IMAGE', 'sbref-exec:1')
MODEL_LABEL = env('MODEL_LABEL', 'luna')
PAR = int(env('SBREF_PARALLEL', '4'))
MAX_TURN = 5
ROWS = 5


def upstream_functions(path, names, ns):
    """The named top-level functions of an upstream source file, defined in `ns`."""
    tree = ast.parse(open(path).read(), path)
    mod = ast.Module(body=[n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names],
                     type_ignores=[])
    exec(compile(mod, path, 'exec'), ns)
    missing = set(names) - set(ns)
    assert not missing, missing
    return ns


def upstream_constants(path):
    """The top-level string constants of an upstream module (prompt_format.py)."""
    ns = {}
    tree = ast.parse(open(path).read(), path)
    exec(compile(ast.Module(body=[n for n in tree.body if isinstance(n, ast.Assign)], type_ignores=[]), path, 'exec'), ns)
    return ns


class Opt:
    row = ROWS


pf = upstream_constants(f'{REPO}/inference/prompt_format.py')
ce = upstream_functions(f'{REPO}/inference/code_exec.py', ['extract_code', 'exec_code'], {})
gen_file_content = upstream_functions(f'{REPO}/inference/inference_multiple.py', ['gen_file_content'],
                                      {'pd': pd, 'opt': Opt()})['gen_file_content']


class Bridge:
    """bridge.clj as a subprocess: one JSON request per line, '@@RESP {json}' replies."""

    def __init__(self, log_dir):
        self.p = subprocess.Popen(['env', '-u', 'TELEGRAM_BOT_TOKEN', 'clojure', '-J-Xmx2g', '-M:benchmarks',
                                   os.path.join(HERE, 'bridge.clj')], cwd=ROOT, stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=open(f'{log_dir}/bridge.stderr.log', 'a'),
                                  text=True, bufsize=1)
        self.log = open(f'{log_dir}/bridge.stdout.log', 'a')
        self.waiters, self.lock, self.n = {}, threading.Lock(), 0
        self.ready = threading.Event()
        threading.Thread(target=self._read, daemon=True).start()
        if not self.ready.wait(600):
            raise RuntimeError('bridge did not start')

    def _read(self):
        for line in self.p.stdout:
            if not line.startswith('@@RESP '):
                self.log.write(line); self.log.flush(); continue
            r = json.loads(line[7:])
            if r['id'] == 'ready':
                self.ready.set(); continue
            with self.lock:
                fut = self.waiters.pop(r['id'])
            fut.set_result(r)

    def ask(self, msgs):
        with self.lock:
            self.n += 1; rid = str(self.n); fut = cf.Future(); self.waiters[rid] = fut
            self.p.stdin.write(json.dumps({'id': rid, 'messages': msgs}) + '\n'); self.p.stdin.flush()
        return fut.result(timeout=1800)

    def close(self):
        self.p.stdin.close(); self.p.wait(600)


class Sandbox:
    """Same interface as upstream's ClientJupyterKernel: execute(code) -> str."""

    def __init__(self, mount, name):
        self.name = name
        self.p = subprocess.Popen(['docker', 'run', '-i', '--rm', '--name', name, '--network', 'none', '--read-only',
                                   '--tmpfs', '/tmp:size=512m,uid=1000', '--memory', '4g', '--cpus', '2',
                                   '--pids-limit', '256', '--cap-drop', 'ALL', '--user', '1000:1000',
                                   '-v', f'{mount}:/mnt/data', IMAGE],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  text=True, bufsize=1)
        assert json.loads(self.p.stdout.readline())['ready']

    def execute(self, code):
        self.p.stdin.write(json.dumps({'code': code}) + '\n'); self.p.stdin.flush()
        line = self.p.stdout.readline()
        if not line:
            raise RuntimeError('executor died')
        return json.loads(line)['result']

    def close(self):
        try:
            self.p.stdin.close(); self.p.wait(30)
        except Exception:
            subprocess.run(['docker', 'kill', self.name], capture_output=True)


def input_name(tid):
    return f'1_{tid}_init.xlsx' if os.path.exists(f'{DATA}/spreadsheet/{tid}/1_{tid}_init.xlsx') else 'initial.xlsx'


def make_prompt(setting, d):
    """The upstream prompt for task `d`, and the input file name and output path it names."""
    tid = str(d['id'])
    out_dir = f'single_{MODEL_LABEL}' if setting == 'single' else f'multi_{setting}_{MODEL_LABEL}'
    fname = input_name(tid)
    input_path = f'/mnt/data/spreadsheet/{tid}/{fname}'
    output_path = f'/mnt/data/outputs/{out_dir}/1_{tid}_output.xlsx'
    fields = {'instruction': d['instruction'], 'spreadsheet_path': input_path, 'instruction_type': d['instruction_type'],
              'answer_position': d['answer_position'], 'max_turn_num': MAX_TURN, 'output_path': output_path}
    if setting in ('single', 'row_exec', 'row_react_exec'):
        fields['spreadsheet_content'] = gen_file_content(f'{DATA}/spreadsheet/{tid}/{fname}')
    tmpl = {'single': pf['PROMPT_FORMAT_SINGLE'], 'row_exec': pf['PROMPT_FORMAT_SINGLE'],
            'react_exec': pf['PROMPT_NO_DF_RCT_FORMAT'], 'row_react_exec': pf['PROMPT_DF_RCT_FORMAT']}[setting]
    return tmpl.format_map(fields), fname, out_dir, output_path


def run_task(bridge, setting, tag, run_dir, d):
    tid = str(d['id'])
    prompt, fname, out_dir, output_path = make_prompt(setting, d)
    mount = f'{STAGE}/{tag}/{tid}'
    shutil.rmtree(mount, ignore_errors=True)
    os.makedirs(f'{mount}/spreadsheet/{tid}'); os.makedirs(f'{mount}/outputs/{out_dir}')
    shutil.copy(f'{DATA}/spreadsheet/{tid}/{fname}', f'{mount}/spreadsheet/{tid}/{fname}')
    host_out = output_path.replace('/mnt/data', mount)
    t0 = time.time(); turns = []; err = None
    messages = [prompt]
    sb = Sandbox(mount, f'sbref-{tag}-{tid}'.replace('_', '-'))
    try:
        for _ in range(1 if setting == 'single' else MAX_TURN):
            r = bridge.ask([{'role': 'user' if i % 2 == 0 else 'assistant', 'content': m} for i, m in enumerate(messages)])
            if r.get('error'):
                raise RuntimeError(r['error'])
            response = r['content'] or ''
            messages.append(response)
            te = time.time()
            try:
                exec_result = ce['exec_code'](sb, ce['extract_code'](response))
            except Exception:
                exec_result = 'Error occur when running code.'
            messages.append(exec_result)
            turns.append({'usage': r.get('usage'), 'llm_ms': r.get('ms'), 'exec_s': round(time.time() - te, 2)})
            if os.path.exists(host_out):
                break
    except Exception as e:
        err = repr(e)
    finally:
        sb.close()
    res = {'id': tid, 'setting': setting, 'model': os.environ.get('MODEL') or 'codex-subscription-luna',
           'instruction_type': d['instruction_type'], 'conversation': messages,
           'solution': ce['extract_code'](messages[-2]) if len(messages) >= 3 else '', 'turns': turns,
           'wall_s': round(time.time() - t0, 2), 'output_exists': os.path.exists(host_out), 'error': err}
    if res['output_exists']:
        shutil.copy(host_out, f'{run_dir}/outputs/{tid}.xlsx')
    return res


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    setting, tag = sys.argv[1:3]
    ids_file = sys.argv[3] if len(sys.argv) > 3 else os.path.join(HERE, 'ids.txt')
    ids = open(ids_file).read().split()
    ds = {str(d['id']): d for d in json.load(open(f'{DATA}/dataset.json'))}
    run_dir = f'{OUT}/{tag}'
    rdir = f'{run_dir}/results'
    os.makedirs(rdir, exist_ok=True); os.makedirs(f'{run_dir}/outputs', exist_ok=True)
    # a rerun keeps the tasks that finished without an error
    todo = [t for t in ids if not (os.path.exists(f'{rdir}/{t}.json') and not json.load(open(f'{rdir}/{t}.json'))['error'])]
    print(f'{len(todo)} of {len(ids)} to run', flush=True)
    bridge = Bridge(run_dir)

    def one(t):
        r = run_task(bridge, setting, tag, run_dir, ds[t])
        json.dump(r, open(f'{rdir}/{t}.json', 'w'), indent=1, ensure_ascii=False)
        print(t, 'turns', len(r['turns']), 'out', r['output_exists'], 'wall', r['wall_s'], 'err', r['error'], flush=True)

    with cf.ThreadPoolExecutor(PAR) as ex:
        for f in [ex.submit(one, t) for t in todo]:
            try:
                f.result()
            except Exception as e:
                print('TASK FAIL', repr(e), flush=True)
    bridge.close()


if __name__ == '__main__':
    main()
