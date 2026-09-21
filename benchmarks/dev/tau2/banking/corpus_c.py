"""Generator for a banking_knowledge differential corpus (provenance only).

The generated corpus is vendored in benchmarks/test/dvergr/benchmarks/tau2/banking_corpus.json;
regenerate + re-run benchmarks/dev/tau2/oracle.py only when porting a new upstream
revision. Paths below are those of the original run.
"""
import json, hashlib, sys
S = sys.argv[1]
def did(seed, n): return hashlib.sha256(seed.encode()).digest()[: n // 2].hex()
TODAY = "11/14/2025"
def new_card(acct, user):
    cid = "dbc_" + did(f"debit_card:{acct}:{user}:{TODAY}", 12)
    l4 = "".join(c for c in did(f"card_details:{cid}:last4", 8) if c.isdigit())[:4].zfill(4)
    cvv = "".join(c for c in did(f"card_details:{cid}:cvv", 6) if c.isdigit())[:3].zfill(3)
    return cid, l4, cvv

seqs = []
def seq(id, calls, task=None):
    s = {"id": id, "calls": [{"name": n, "arguments": a, "requestor": "assistant"} for n, a in calls]}
    if task: s["task"] = task
    seqs.append(s)

ORD = "order_debit_card_5739"
A1, A2, A3 = "activate_debit_card_8291", "activate_debit_card_8292", "activate_debit_card_8293"
CL, FR, UF = "close_debit_card_4721", "freeze_debit_card_3892", "unfreeze_debit_card_3893"
CFA, RP, CP = "clear_debit_card_fraud_alert_4892", "reset_debit_card_pin_6284", "change_debit_card_pin_6285"
GET, LIM = "get_debit_cards_by_account_id_7823", "request_temporary_debit_card_limit_increase_8374"

def order(acct, user, opt="STANDARD", dfee=0, design="CLASSIC", gfee=0, addr="1 Main St, Austin TX", **kw):
    a = {"account_id": acct, "user_id": user, "delivery_option": opt, "delivery_fee": dfee,
         "card_design": design, "design_fee": gfee, "shipping_address": addr}
    a.update(kw)
    return (ORD, a)

def act(tool, cid, l4, cvv="123", pin="1357", exp="11/29", **kw):
    a = {"card_id": cid, "last_4_digits": l4, "expiration_date": exp, "cvv": cvv, "pin": pin}
    a.update(kw)
    return (tool, a)

BL = "dbc_lj82d4f1a9_bluest"
# --- freeze/unfreeze
seq("fz-basic", [(FR, {"card_id": BL}), (FR, {"card_id": BL}), (UF, {"card_id": BL}), (UF, {"card_id": BL}),
                 (FR, {"card_id": "dbc_7a3f9c2b1e"}), (UF, {"card_id": "dbc_7a3f9c2b1e"}),
                 (FR, {"card_id": "nope"}), (UF, {"card_id": "nope"}), (FR, {"card_id": ""}), (UF, {"card_id": ""}),
                 (FR, {"card_id": None}), (FR, {"card_id": 123}), (UF, {"card_id": 12.0}), (FR, {}),
                 (UF, {}), (FR, {"card_id": BL, "reason": "x"}), (UF, {"card_id": False})])
seq("fz-task087", [(UF, {"card_id": "dbc_mt35a7c9d2_blue"}), (FR, {"card_id": "dbc_mt35a7c9d2_gff"}),
                   (UF, {"card_id": "dbc_mt35a7c9d2_gff"}), (FR, {"card_id": "dbc_mt35a7c9d2_evergreen"})], "task_087")
seq("fz-task088", [(FR, {"card_id": "dbc_c56fb772c2e0"}), (UF, {"card_id": "dbc_c56fb772c2e0"}),
                   (CL, {"card_id": "dbc_c56fb772c2e0", "reason": "lost"})], "task_088")

# --- PIN reset/change
seq("pin-reset", [
    (RP, {"card_id": BL, "last_4_digits": "4821"}),
    (RP, {"card_id": BL, "last_4_digits": "4821", "new_pin": ""}),
    (RP, {"card_id": "", "last_4_digits": "4821", "new_pin": "1357"}),
    (RP, {"card_id": BL, "last_4_digits": "482", "new_pin": "1357"}),
    (RP, {"card_id": BL, "last_4_digits": "48a1", "new_pin": "1357"}),
    (RP, {"card_id": BL, "last_4_digits": 4821, "new_pin": "1357"}),
    (RP, {"card_id": BL, "last_4_digits": 4821.0, "new_pin": "1357"}),
    (RP, {"card_id": BL, "last_4_digits": "4821", "new_pin": 1357}),
    (RP, {"card_id": BL, "last_4_digits": "4821", "new_pin": 1357.0}),
    (RP, {"card_id": BL, "last_4_digits": "4821", "new_pin": "1234"}),
    (RP, {"card_id": BL, "last_4_digits": "4821", "new_pin": "3210"}),
    (RP, {"card_id": BL, "last_4_digits": "4821", "new_pin": "1111"}),
    (RP, {"card_id": BL, "last_4_digits": "4821", "new_pin": "12a4"}),
    (RP, {"card_id": BL, "last_4_digits": "4821", "new_pin": "135"}),
    (RP, {"card_id": BL, "last_4_digits": "4821", "new_pin": "13579"}),
    (RP, {"card_id": "nope", "last_4_digits": "4821", "new_pin": "1357"}),
    (RP, {"card_id": BL, "last_4_digits": "4822", "new_pin": "1357"}),
    (RP, {"card_id": "dbc_7a3f9c2b1e", "last_4_digits": "2847", "new_pin": "1357"}),
    (RP, {"card_id": BL, "last_4_digits": "4821", "new_pin": "1357"}),
    (RP, {"card_id": BL, "last_4_digits": "4821", "new_pin": "1357", "cvv": "1"}),
    (RP, {"card_id": BL, "last_4_digits": 0, "new_pin": 0}),
    (RP, {"card_id": BL, "last_4_digits": True, "new_pin": "1357"}),
])
seq("pin-change", [
    (CP, {"card_id": BL, "current_pin": "1357", "new_pin": "2468"}),
    (CP, {"card_id": BL, "current_pin": "2468", "new_pin": "2468"}),
    (CP, {"card_id": BL, "current_pin": "12", "new_pin": "2468"}),
    (CP, {"card_id": BL, "current_pin": 1357, "new_pin": "2468"}),
    (CP, {"card_id": BL, "current_pin": 1357.0, "new_pin": "2468"}),
    (CP, {"card_id": BL, "current_pin": "1357", "new_pin": 2468}),
    (CP, {"card_id": BL, "current_pin": "1357", "new_pin": "4567"}),
    (CP, {"card_id": BL, "current_pin": "1357", "new_pin": "9999"}),
    (CP, {"card_id": BL, "current_pin": "", "new_pin": "2468"}),
    (CP, {"card_id": BL, "current_pin": "abcd", "new_pin": "2468"}),
    (CP, {"card_id": "nope", "current_pin": "1357", "new_pin": "2468"}),
    (CP, {"card_id": "dbc_4e8d2a6f9c", "current_pin": "1357", "new_pin": "2468"}),
    (FR, {"card_id": BL}),
    (CP, {"card_id": BL, "current_pin": "1357", "new_pin": "2468"}),
    (RP, {"card_id": BL, "last_4_digits": "4821", "new_pin": "2468"}),
    (UF, {"card_id": BL}),
    (CP, {"card_id": BL, "current_pin": "1357", "new_pin": "8024"}),
    (CP, {"card_id": BL}),
    (CP, {"card_id": BL, "current_pin": "1357", "new_pin": "2468", "pin": "x"}),
])
seq("pin-task090", [
    (RP, {"card_id": "dbc_er38a7c9d2_green", "last_4_digits": "3156", "new_pin": "8024"}),
    (RP, {"card_id": "dbc_er38a7c9d2_blue", "last_4_digits": "6183", "new_pin": "8024"}),
    (CP, {"card_id": "dbc_er38a7c9d2_evergreen", "current_pin": "1111", "new_pin": "8024"}),
], "task_090")

# --- close
for i, r in enumerate(["lost", "stolen", "fraud_suspected", "damaged", "no_longer_needed", "account_closing",
                       "LOST", "Fraud_Suspected", "other", "", 5, None, "no longer needed"]):
    seq(f"close-{i}", [(CL, {"card_id": BL, "reason": r}), (CL, {"card_id": BL, "reason": "lost"}),
                       (GET, {"account_id": "chk_lj82d4f1a9"})])
seq("close-misc", [(CL, {"card_id": "nope", "reason": "lost"}), (CL, {"card_id": "", "reason": "lost"}),
                   (CL, {"card_id": "dbc_7a3f9c2b1e", "reason": "no_longer_needed"}),
                   (FR, {"card_id": "dbc_kj93a7b2e1_blue"}), (CL, {"card_id": "dbc_kj93a7b2e1_blue", "reason": "lost"}),
                   (CL, {"card_id": "dbc_kj93a7b2e1_green"}), (CL, {"card_id": 7, "reason": "lost"})])

# --- fraud alert / velocity
seq("cfa-087", [
    (CFA, {"card_id": "dbc_mt35a7c9d2_blue", "reason": "velocity_clear"}),
    (CFA, {"card_id": "dbc_mt35a7c9d2_blue", "reason": "velocity_clear"}),
    (CFA, {"card_id": "dbc_mt35a7c9d2_gff", "reason": "velocity_clear"}),
    (CFA, {"card_id": "dbc_mt35a7c9d2_blue", "reason": "customer_verified"}),
    (CFA, {"card_id": "dbc_mt35a7c9d2_evergreen", "reason": "customer_verified"}),
    (CFA, {"card_id": "dbc_mt35a7c9d2_evergreen", "reason": "customer_verified"}),
    (CFA, {"card_id": "dbc_mt35a7c9d2_evergreen", "reason": "Customer_Verified"}),
    (CFA, {"card_id": "dbc_mt35a7c9d2_evergreen", "reason": 5}),
    (CFA, {"card_id": "dbc_mt35a7c9d2_evergreen", "reason": ""}),
    (CFA, {"card_id": "", "reason": "velocity_clear"}),
    (CFA, {"card_id": "nope", "reason": "velocity_clear"}),
    (CFA, {"card_id": "nope"}),
    (UF, {"card_id": "dbc_mt35a7c9d2_blue"}),
], "task_087")
seq("cfa-088", [(CFA, {"card_id": "dbc_c56fb772c2e0", "reason": "customer_verified"}),
                (CFA, {"card_id": "dbc_31839d9c1891", "reason": "customer_verified"}),
                (GET, {"account_id": "chk_cec8333d41_blue"})], "task_088")
seq("cfa-092", [(CFA, {"card_id": "dbc_rw42b8d3e1_blue", "reason": "customer_verified"}),
                (CFA, {"card_id": "dbc_rw42b8d3e1_blue", "reason": "velocity_clear"})], "task_092")
seq("cfa-base", [(CFA, {"card_id": BL, "reason": "customer_verified"}),
                 (CFA, {"card_id": BL, "reason": "velocity_clear"}),
                 (CFA, {"card_id": BL, "reason": None})])

# --- get debit cards
seq("get-base", [(GET, {"account_id": a}) for a in
                 ["chk_lj82d4f1a9", "03", "05", "06", "02", "biz_chk_b5e8f2a1c9", "chk_e9d195fe8e", "", "nope", None, 3,
                  "chk_kj93a7b2e1_3"]] + [(GET, {}), (GET, {"account_id": "03", "x": 1})])
for t, accts in [("task_078", ["chk_mc78a5b9d2_1", "chk_mc78a5b9d2_2"]), ("task_088", ["chk_cec8333d41_green"]),
                 ("task_090", ["chk_er38a7c9d2_blue"]), ("task_087", ["chk_mt35a7c9d2_blue"])]:
    seq(f"get-{t}", [(GET, {"account_id": a}) for a in accts], t)

# --- activation validation
P = "dbc_7a3f9c2b1e"
seq("act-validate", [
    act(A1, P, "2847", pin=None), act(A1, P, "2847", cvv=""), act(A1, "", "2847"), act(A1, P, "2847", exp=""),
    act(A1, P, "2847", pin=1357), act(A1, P, "2847", pin=1357.0), act(A1, P, "2847", pin="1234"),
    act(A1, P, "2847", pin="7777"), act(A1, P, "2847", pin="12345"), act(A1, P, "2847", pin="abcd"),
    act(A1, P, "2847", cvv=516), act(A1, P, "2847", cvv=516.0), act(A1, P, "2847", cvv="51"), act(A1, P, "2847", cvv="5a6"),
    act(A1, P, 2847), act(A1, P, 2847.0), act(A1, P, "284"), act(A1, P, "28a7"),
    act(A1, P, 2847, pin="1234"), act(A1, P, 2847, cvv="51"), act(A1, P, 2847, cvv=516), act(A1, 42, "2847"),
    act(A1, "nope", "2847"), act(A2, P, "2847"), act(A3, P, "2847"), act(A1, P, "2848"),
    act(A1, BL, "4821"), act(A2, BL, "4821"), act(A3, "dbc_538bfb9cba", "7293"),
    act(A2, "dbc_kj93a7b2e1_green", "9152"), act(A1, "dbc_kj93a7b2e1_lightgreen", "6473"),
    (A1, {"card_id": P}), act(A1, P, "2847", extra=1),
    act(A1, P, "2847", cvv=True), act(A1, P, "2847", pin=True),
    act(A1, P, "2847"), act(A1, P, "2847"), act(A2, P, "2847"),
])
seq("act-8292", [act(A2, "dbc_4e8d2a6f9c", "8765"), act(A3, "dbc_4e8d2a6f9c", "8765"), act(A2, "dbc_4e8d2a6f9c", "8765")])
seq("act-8293", [act(A3, "dbc_9b1c5d7e3a", "5291"), act(A1, "dbc_9b1c5d7e3a", "5291")])
seq("act-8293-frozen", [act(A3, "dbc_9b1c5d7e3a", "5291"), (FR, {"card_id": "dbc_9b1c5d7e3a"}),
                        act(A3, "dbc_9b1c5d7e3a", "5291")])

# --- order: validation
seq("order-validate", [
    order("03", "125", dfee="abc"), order("03", "125", dfee=[]), order("03", "125", dfee={}),
    order("03", "125", gfee="x"), order("03", "125", gfee=[1]), order("", "125", dfee="abc"),
    order("03", "125", dfee=None), order("03", "125", gfee=None), order("", "125"), order("03", ""),
    order("03", "125", opt=""), order("03", "125", design=""), order("03", "125", addr=""),
    order("03", "125", opt="overnight"), order("03", "125", design="gold"), order("03", "125", opt=5),
    order("03", "125", opt=True), order("03", "125", design=3.0), order("03", "125", opt="standard", design=7),
    order("nope", "125"), order(5, "125"), order("02", "123"), order("biz_chk_b5e8f2a1c9", "b5e8f2a1c9"),
    order("03", "126"), order("03", 125), order("01", "123"), order("08", "h1i2j3k4l5", dfee=30, gfee=25),
    order("08", "h1i2j3k4l5", dfee=20, gfee=5, excess_replacement_fee=0.5),
    order("chk_lj82d4f1a9", "lj82d4f1a9"), order("61a8b7c6d5e4f321", "jc61f7a8d2", dfee=10000),
    (ORD, {"account_id": "03"}), (ORD, dict(order("03", "125")[1], foo=1)),
])
# order: success variants
succ = [
    ("ord-07-free", order("07", "e3f4a5b6c7")),
    ("ord-03-fees", order("03", "125", opt="expedited", dfee=10, design="premium", gfee=5.5, excess_replacement_fee=25)),
    ("ord-03-feesf", order("03", "125", opt="Rush", dfee=10.0, design="Custom", gfee=5.0, excess_replacement_fee=25.0)),
    ("ord-03-strfee", order("03", "125", dfee="12.5", gfee="0", excess_replacement_fee="7")),
    ("ord-03-excbad", order("03", "125", dfee=3, excess_replacement_fee="abc")),
    ("ord-03-excbad0", order("03", "125", excess_replacement_fee="abc")),
    ("ord-03-exclist", order("03", "125", dfee=1, excess_replacement_fee=[])),
    ("ord-03-exclist2", order("03", "125", dfee=1, excess_replacement_fee=[5])),
    ("ord-03-exc45", order("03", "125", excess_replacement_fee=4.5)),
    ("ord-03-exc55", order("03", "125", excess_replacement_fee=5.5)),
    ("ord-03-exc0", order("03", "125", excess_replacement_fee=0)),
    ("ord-03-exctrue", order("03", "125", excess_replacement_fee=True)),
    ("ord-03-booldfee", order("03", "125", dfee=True, gfee=False)),
    ("ord-03-neg", order("03", "125", dfee=-5, gfee=2)),
    ("ord-03-negtotal", order("03", "125", dfee=-5, gfee=-2)),
    ("ord-03-tiny", order("03", "125", dfee=0.1, gfee=0.2)),
    ("ord-03-exact", order("03", "125", dfee=3375, gfee=0)),
    ("ord-03-over", order("03", "125", dfee=3375, gfee=0.01)),
    ("ord-05", order("05", "224959b99e", dfee=15)),
    ("ord-06", order("06", "c7d8e9f0a1")),
    ("ord-purple", order("chk_ar72c5d8e3_1", "ar72c5d8e3", opt="RUSH", dfee=25)),
    ("ord-08-ok", order("08", "h1i2j3k4l5", dfee=20, gfee=5)),
    ("ord-lightgreen-young", order("chk_jl72b4e9d1", "jl72b4e9d1")),
    ("ord-addr-num", order("07", "e3f4a5b6c7", addr=12345)),
]
for sid, call in succ:
    acct, user = call[1]["account_id"], call[1]["user_id"]
    cid, l4, cvv = new_card(acct, user)
    seq(sid, [call, call, (GET, {"account_id": acct}), act(A1, cid, l4, cvv=cvv), act(A2, cid, l4),
              (LIM, {"card_id": cid, "limit_type": "atm", "new_limit": 100}),
              (LIM, {"card_id": cid, "limit_type": "purchase", "new_limit": 100})])

# order after freezing the active card -> first_card; deactivation on activate
cid, l4, _ = new_card("chk_lj82d4f1a9", "lj82d4f1a9")
seq("flow-8291-deact", [(FR, {"card_id": BL}), order("chk_lj82d4f1a9", "lj82d4f1a9", dfee=5),
                        (UF, {"card_id": BL}), act(A1, cid, l4), (GET, {"account_id": "chk_lj82d4f1a9"}),
                        (CP, {"card_id": cid, "current_pin": "1357", "new_pin": "2468"}),
                        (FR, {"card_id": BL}), (LIM, {"card_id": cid, "limit_type": "atm", "new_limit": 2250}),
                        (LIM, {"card_id": cid, "limit_type": "atm", "new_limit": 3375}),
                        (LIM, {"card_id": cid, "limit_type": "atm", "new_limit": 3376})])
# lost closure -> issue lost -> 8292
for reason, exp in [("lost", "lost"), ("stolen", "stolen"), ("fraud_suspected", "fraud"), ("damaged", "first_card")]:
    cid, l4, cvv = new_card("chk_kj93a7b2e1_2", "kj93a7b2e1")
    seq(f"flow-close-{reason}", [(CL, {"card_id": "dbc_kj93a7b2e1_green", "reason": reason}),
                                 order("chk_kj93a7b2e1_2", "kj93a7b2e1", opt="EXPEDITED", dfee=15),
                                 act(A1, cid, l4), act(A2, cid, l4), act(A3, cid, l4),
                                 (GET, {"account_id": "chk_kj93a7b2e1_2"})])
# 8292 with deactivation (acct 05 has pending stolen card)
cid, l4, _ = new_card("05", "224959b99e")
seq("flow-8292-deact", [order("05", "224959b99e"), act(A1, cid, l4), act(A2, "dbc_4e8d2a6f9c", "8765"),
                        (GET, {"account_id": "05"}), act(A1, cid, l4)])
cid, l4, _ = new_card("06", "c7d8e9f0a1")
seq("flow-8293-grace", [order("06", "c7d8e9f0a1"), act(A1, cid, l4), act(A3, "dbc_9b1c5d7e3a", "5291"),
                        (GET, {"account_id": "06"}), (FR, {"card_id": cid}), (CL, {"card_id": cid, "reason": "lost"})])
# fraud: 8292 fraud line with deactivation
cid, l4, _ = new_card("chk_kj93a7b2e1_2", "kj93a7b2e1")
seq("flow-fraud-deact", [(CL, {"card_id": "dbc_kj93a7b2e1_green", "reason": "fraud_suspected"}),
                         order("chk_kj93a7b2e1_2", "kj93a7b2e1"), act(A2, cid, l4)])
# task_078: closed card without closure_reason
cid, l4, _ = new_card("chk_mc78a5b9d2_1", "mc78a5b9d2")
seq("flow-078", [(FR, {"card_id": "dbc_mc78a5b9d2_lb"}), order("chk_mc78a5b9d2_1", "mc78a5b9d2", dfee=9.99),
                 (UF, {"card_id": "dbc_mc78a5b9d2_lb"}), act(A2, cid, l4), act(A1, cid, l4),
                 (GET, {"account_id": "chk_mc78a5b9d2_1"})], "task_078")
# age: young account
cid, l4, _ = new_card("38a2c5f77504be6c", "jm60a8b9c2")
seq("flow-young", [order("38a2c5f77504be6c", "jm60a8b9c2"), act(A1, cid, l4),
                   (LIM, {"card_id": cid, "limit_type": "atm", "new_limit": 600})])
cid, l4, _ = new_card("61e5f6a7b8c9d012", "mm61b4c8d3")
seq("flow-silver", [order("61e5f6a7b8c9d012", "mm61b4c8d3"), act(A1, cid, l4),
                    (LIM, {"card_id": cid, "limit_type": "atm", "new_limit": 600})])
# default atm limits for several levels
for acct, user in [("chk_ar72c5d8e3_1", "ar72c5d8e3"), ("chk_ar72c5d8e3_3", "ar72c5d8e3"),
                   ("chk_ar72c5d8e3_2", "ar72c5d8e3"), ("chk_ar72c5d8e3_4", "ar72c5d8e3"), ("07", "e3f4a5b6c7"),
                   ("chk_e9d195fe8e", "e9d195fe8e"), ("chk_kj93a7b2e1_3", "kj93a7b2e1")]:
    cid, l4, _ = new_card(acct, user)
    calls = [order(acct, user), act(A1, cid, l4)]
    if acct == "chk_kj93a7b2e1_3":
        calls = [(FR, {"card_id": "dbc_kj93a7b2e1_lightgreen"})] + calls
    calls += [(LIM, {"card_id": cid, "limit_type": "atm", "new_limit": n}) for n in [100, 450, 450.0, 700, 1100, 2000]]
    seq(f"flow-atm-{acct}", calls)

# --- limit increases on task data
L = lambda c, t, n: (LIM, {"card_id": c, "limit_type": t, "new_limit": n})
seq("lim-090", [L("dbc_er38a7c9d2_evergreen", "atm", 1501), L("dbc_er38a7c9d2_evergreen", "atm", 1000),
                L("dbc_er38a7c9d2_evergreen", "atm", 999), L("dbc_er38a7c9d2_evergreen", "atm", 1200.5),
                L("dbc_er38a7c9d2_evergreen", "atm", "abc"), L("dbc_er38a7c9d2_evergreen", "atm", None),
                L("dbc_er38a7c9d2_evergreen", "atm", 0), L("dbc_er38a7c9d2_evergreen", "atm", -5),
                L("dbc_er38a7c9d2_evergreen", "atm", -5.5), L("dbc_er38a7c9d2_evergreen", "atm", [1]),
                L("dbc_er38a7c9d2_evergreen", "atm", "1e3"), L("dbc_er38a7c9d2_evergreen", "atm", " 1200 "),
                L("dbc_er38a7c9d2_evergreen", "atm", "1200.5"), L("dbc_er38a7c9d2_evergreen", "atm", True),
                L("dbc_er38a7c9d2_evergreen", "ATM", 1200), L("dbc_er38a7c9d2_evergreen", "", 1200),
                L("dbc_er38a7c9d2_evergreen", 5, 1200), L("", "atm", 1200), L("nope", "atm", 1200),
                L("dbc_er38a7c9d2_evergreen", "atm", 1e400 if False else 1e20),
                L("dbc_er38a7c9d2_evergreen", "atm", 1200.0), L("dbc_er38a7c9d2_evergreen", "atm", 1300),
                L("dbc_er38a7c9d2_evergreen", "atm", 1800), L("dbc_er38a7c9d2_evergreen", "atm", 1799),
                L("dbc_er38a7c9d2_evergreen", "purchase", 7500), L("dbc_er38a7c9d2_evergreen", "purchase", 7501),
                L("dbc_er38a7c9d2_green", "atm", 900), L("dbc_er38a7c9d2_blue", "purchase", 3750),
                L("dbc_er38a7c9d2_blue", "atm", 750), (LIM, {"card_id": "dbc_er38a7c9d2_green", "limit_type": "atm"}),
                (FR, {"card_id": "dbc_er38a7c9d2_green"}), L("dbc_er38a7c9d2_green", "atm", 800),
                (GET, {"account_id": "chk_er38a7c9d2_evergreen"})], "task_090")
seq("lim-088", [L("dbc_31839d9c1891", "purchase", 3750), L("dbc_31839d9c1891", "atm", 700),
                L("dbc_c56fb772c2e0", "purchase", 3000), L("dbc_c8feebe975cf", "purchase", 4500.0)], "task_088")
seq("lim-089", [L("dbc_9c3e7f1b5d2a", "atm", 200), L("dbc_9c3e7f1b5d2a", "purchase", 200),
                L("dbc_2f8a7c3d1e9b", "purchase", 6000), L("dbc_5e1b9d4f2a8c", "atm", 700)], "task_089")
seq("lim-091", [L("dbc_tw34a7d9e2_lightblue", "atm", 1050), L("dbc_tw34a7d9e2_blue", "atm", 900),
                L("dbc_tw34a7d9e2_green", "purchase", 5001)], "task_091")
seq("lim-base", [L(BL, "atm", 2250), L(BL, "purchase", 100), L("dbc_538bfb9cba", "atm", 200),
                 L("dbc_kj93a7b2e1_lightgreen", "atm", 200), L("dbc_kj93a7b2e1_blue", "atm", 600),
                 L("dbc_kj93a7b2e1_green", "atm", 900), L("dbc_7a3f9c2b1e", "atm", 700), L(BL, "atm", 2250),
                 L(BL, "atm", 3375), L(BL, "atm", 3376)])

json.dump(seqs, open(f"{S}/corpus.json", "w"), indent=1)
print(len(seqs), sum(len(s["calls"]) for s in seqs))
seqs.append({"id": "lim-strings", "task": "task_090", "calls": [
    {"name": LIM, "arguments": {"card_id": "dbc_er38a7c9d2_evergreen", "limit_type": "atm", "new_limit": v}, "requestor": "assistant"}
    for v in ["inf", "nan", "-inf", "Infinity", "1_000", "0x10", "-0", -0.0, "1200", "  1300.0\n", "", {}, False, 1400.0]]})
seqs.append({"id": "order-strings", "calls": [
    {"name": ORD, "arguments": order("03", "125", dfee=d, gfee=g, excess_replacement_fee=e)[1], "requestor": "assistant"}
    for d, g, e in [("$5", 0, None), ("5", "1,000", None), ("1e1", "2.50", "3.5"), (" 4 ", 0, "x")]]})
json.dump(seqs, open(f"{S}/corpus.json", "w"), indent=1)
print(len(seqs), sum(len(s["calls"]) for s in seqs))
