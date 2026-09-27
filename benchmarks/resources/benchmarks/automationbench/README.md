# AutomationBench service

`sidecar.py` serves Zapier's AutomationBench (MIT, https://github.com/zapier/AutomationBench)
to `dvergr.benchmarks.automationbench.*`: upstream's task set, tool implementations, tool
schemas and rubric, called as upstream's runner calls them, behind a stateless JSON-lines
protocol. It is dvergr's code and ships none of upstream's; it runs in an upstream checkout's
environment:

    git clone https://github.com/zapier/AutomationBench ~/.cache/dvergr-bench/automationbench
    cd ~/.cache/dvergr-bench/automationbench
    git checkout 4a8e1061254004d9dac807054eed33fad7d1ff14
    uv sync
    uv pip install --python .venv/bin/python time-machine

(`AUTOMATIONBENCH_ROOT` points elsewhere.) `time-machine` freezes the clock during a call:
upstream's worlds take wall-clock defaults and random ids, which the service pins to a
recorded instant and a seed so that an episode can be replayed exactly.
