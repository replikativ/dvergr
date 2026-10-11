"""One persistent IPython kernel; JSON lines on stdin ({"code": ...}) -> JSON lines on stdout
({"result": ...}).

Adapted from SpreadsheetBench (https://github.com/RUCKBReasoning/SpreadsheetBench),
code_exec_docker/jupyter.py (JupyterKernel.execute), commit
49b73a94775fb489063f60ca1865e3a650079a79, CC BY-SA 4.0; this adaptation is distributed
under the same license (https://creativecommons.org/licenses/by-sa/4.0/).

As upstream: '%colors nocolor' at start, an error's traceback joined by four newlines,
stream text, execute_result / display_data text/plain, '[Code executed successfully with
no output]' when there is no output, a 60 s timeout that interrupts the kernel, ANSI
colours stripped. Changed: the kernel is driven over jupyter_client in-process instead of
through a kernel gateway over a websocket; an execution ends on the kernel's idle status
(upstream: on execute_reply, or on the error message); image/png outputs are not appended
as markdown images (the spreadsheet tasks print text).
"""
import sys, json, re, queue
from jupyter_client import KernelManager

def strip_ansi(o):
    return re.compile(r'\x1B\[\d+(;\d+){0,2}m').sub('', o)

km = KernelManager(kernel_name='python3')
km.start_kernel()
kc = km.client(); kc.start_channels(); kc.wait_for_ready(timeout=60)

def execute(code, timeout=60):
    msg_id = kc.execute(code, silent=False, store_history=False, allow_stdin=False)
    outputs, done = [], False
    import time
    deadline = time.time() + timeout
    while not done:
        rem = deadline - time.time()
        if rem <= 0:
            km.interrupt_kernel()
            # drain until idle so the next request starts clean
            try:
                while True:
                    m = kc.get_iopub_msg(timeout=10)
                    if m['parent_header'].get('msg_id') == msg_id and m['msg_type'] == 'status' and m['content']['execution_state'] == 'idle':
                        break
            except queue.Empty:
                pass
            return f"[Execution timed out ({timeout} seconds).]"
        try:
            msg = kc.get_iopub_msg(timeout=min(rem, 1))
        except queue.Empty:
            continue
        if msg['parent_header'].get('msg_id') != msg_id:
            continue
        t = msg['msg_type']
        if t == 'error':
            outputs.append("\n\n\n\n".join(msg['content']['traceback']))
            # upstream stops reading here; we also wait for idle so a quickly following request
            # is not aborted by ipykernel's stop-on-error window (no output difference)
        elif t == 'stream':
            outputs.append(msg['content']['text'])
        elif t in ('execute_result', 'display_data'):
            outputs.append(msg['content']['data'].get('text/plain', ''))
        elif t == 'status' and msg['content']['execution_state'] == 'idle':
            done = True   # upstream ends on execute_reply (shell); idle on iopub follows all outputs
    ret = "[Code executed successfully with no output]" if not outputs else ''.join(outputs)
    return strip_ansi(ret)

execute(r"%colors nocolor")
sys.stdout.write(json.dumps({"ready": True}) + "\n"); sys.stdout.flush()
for line in sys.stdin:
    req = json.loads(line)
    try:
        res = execute(req['code'])
    except Exception as e:
        res = f"[executor error: {e!r}]"
    sys.stdout.write(json.dumps({"result": res}) + "\n"); sys.stdout.flush()
km.shutdown_kernel(now=True)
