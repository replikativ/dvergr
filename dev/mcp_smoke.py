import json, subprocess, sys, os, time
# dvergr MCP smoke through the real relay (bin/dvergr-mcp). Usage:
#   python3 dev/mcp_smoke.py DVERGR_HOME PROFILE [steps.json]
# steps: [[tool, args], ...]; $JOB/$SC are filled from earlier results.
H = sys.argv[1]
env = dict(os.environ, DVERGR_HOME=H)
env.pop("TELEGRAM_BOT_TOKEN", None)
repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
port = os.environ.get("DVERGR_MCP_PORT", "17888")
p = subprocess.Popen(["bin/dvergr-mcp", "--port", port, "--no-start", "--profile", sys.argv[2] if len(sys.argv) > 2 else "admin"],
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, env=env, cwd=repo)
n = [0]
def rpc(method, params=None, notify=False):
    msg = {"jsonrpc": "2.0", "method": method}
    if params is not None: msg["params"] = params
    if not notify:
        n[0] += 1; msg["id"] = n[0]
    p.stdin.write(json.dumps(msg) + "\n"); p.stdin.flush()
    if notify: return None
    while True:
        line = p.stdout.readline()
        if not line: raise SystemExit("relay closed")
        r = json.loads(line)
        if r.get("id") == n[0]: return r
def call(name, args, show=600):
    t = time.time()
    r = rpc("tools/call", {"name": name, "arguments": args})
    res = r.get("result", {})
    text = "".join(c.get("text", "") for c in res.get("content", []))
    print(f"--- {name} {json.dumps(args)[:120]} ({time.time()-t:.1f}s) error={res.get('isError', 'error' in r)}")
    print((text or json.dumps(r.get('error')))[:show])
    try: return json.loads(text)
    except Exception: return text
init = rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "dogfood", "version": "0"}})
print("server:", init.get("result", {}).get("serverInfo"))
rpc("notifications/initialized", notify=True)
tools = rpc("tools/list", {})["result"]["tools"]
print("tools:", len(tools), sorted(t["name"] for t in tools))
steps = json.load(open(sys.argv[3])) if len(sys.argv) > 3 else []
state = {}
for name, args in steps:
    args = json.loads(json.dumps(args).replace("$JOB", str(state.get("job", ""))).replace("$ROOM", str(state.get("room", ""))).replace("$SC", str(state.get("sc", ""))))
    out = call(name, args)
    if isinstance(out, dict):
        for k, s in (("id", "job"), ("room", "room")):
            if name in ("catalog_benchmark",) and k in out: state[s] = out[k]
    if name == "scorecard_list" and isinstance(out, list) and out: state["sc"] = out[0]["id"]
p.stdin.close(); p.wait(timeout=10)
