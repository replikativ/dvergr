"""Generator for a banking_knowledge differential corpus (provenance only).

The generated corpus is vendored in benchmarks/test/dvergr/benchmarks/tau2/banking_corpus.json;
regenerate + re-run benchmarks/dev/tau2/oracle.py only when porting a new upstream
revision. Paths below are those of the original run.
"""
import json, sys
seqs = []
def c(name, **a): return {"name": name, "arguments": a, "requestor": "assistant"}
def seq(id, calls, task=None):
    s = {"id": id, "calls": calls}
    if task: s["task"] = task
    seqs.append(s)

SUB="submit_credit_limit_increase_request_7392"; HIST="get_credit_limit_increase_history_4829"
PAY="get_payment_history_6183"; APP="approve_credit_limit_increase_5847"; DEN="deny_credit_limit_increase_5848"
OPEN="open_bank_account_4821"; CLOSE="close_bank_account_7392"; ALL="get_all_user_accounts_by_user_id_3847"
XFER="transfer_funds_between_bank_accounts_7291"; CHK="apply_checking_account_credit_5829"
SAV="apply_savings_account_credit_6831"; IDR="submit_interest_discrepancy_report_7294"
TX="get_bank_account_transactions_9173"

# --- submit CLI
cc, u = "cc_e9d195fe8e_silver", "e9d195fe8e"
amts = [2500, 2500.0, 2500.5, "2500", "2500.0", "abc", "", True, False, 0, 0.0, -100, -100.0, 1e20, 1234567, 1234567.0, [1], {"a":1}, None, " 750 ", "1e3", 1e-3, 999, "1,000", "$500"]
for i, a in enumerate(amts):
    seq(f"sub-amt-{i}", [c(SUB, credit_card_account_id=cc, user_id=u, requested_increase_amount=a), c(HIST, credit_card_account_id=cc)])
seq("sub-missing", [c(SUB, credit_card_account_id="", user_id=u, requested_increase_amount=100),
                    c(SUB, credit_card_account_id=cc, user_id="", requested_increase_amount=100),
                    c(SUB, credit_card_account_id=None, user_id=u, requested_increase_amount=100),
                    c(SUB, credit_card_account_id=cc, user_id=u),
                    c(SUB, credit_card_account_id=cc),
                    c(SUB, credit_card_account_id=cc, user_id=u, requested_increase_amount=100, extra=1),
                    c(SUB, credit_card_account_id="cc_nope", user_id=u, requested_increase_amount=100),
                    c(SUB, credit_card_account_id=5, user_id=u, requested_increase_amount=100),
                    c(SUB, credit_card_account_id=cc, user_id="someone", requested_increase_amount=100),
                    c(SUB, credit_card_account_id=cc, user_id=7, requested_increase_amount=100)])
seq("sub-dup", [c(SUB, credit_card_account_id=cc, user_id=u, requested_increase_amount=3000),
                c(SUB, credit_card_account_id=cc, user_id=u, requested_increase_amount=3000.0),
                c(SUB, credit_card_account_id=cc, user_id=u, requested_increase_amount="3000"),
                c(SUB, credit_card_account_id=cc, user_id=u, requested_increase_amount=4000),
                c(HIST, credit_card_account_id=cc),
                c(SUB, credit_card_account_id="cc_lj82d4f1a9_silver", user_id="lj82d4f1a9", requested_increase_amount=500),
                c(HIST, credit_card_account_id="cc_lj82d4f1a9_silver"),
                c(HIST, credit_card_account_id=cc)])
seq("sub-task052", [c(HIST, credit_card_account_id="cc_5e4c1a83b0_bronze"),
                    c(SUB, credit_card_account_id="cc_5e4c1a83b0_bronze", user_id="5e4c1a83b0", requested_increase_amount=1000),
                    c(HIST, credit_card_account_id="cc_5e4c1a83b0_bronze")], task="task_052")
seq("sub-task054", [c(HIST, credit_card_account_id="cc_584f9c5d00_gold"),
                    c(SUB, credit_card_account_id="cc_584f9c5d00_gold", user_id="584f9c5d00", requested_increase_amount=2000.0),
                    c(HIST, credit_card_account_id="cc_584f9c5d00_gold")], task="task_054")
seq("hist-misc", [c(HIST, credit_card_account_id=""), c(HIST, credit_card_account_id=None), c(HIST),
                  c(HIST, credit_card_account_id='x"y'), c(HIST, credit_card_account_id="nope"),
                  c(HIST, credit_card_account_id=12), c(HIST, credit_card_account_id="a\\b")])

# --- payment history
for t in ["task_051", "task_052"]:
    ccb = "cc_5e4c1a83b0_bronze"
    calls = [c(PAY, credit_card_account_id=ccb, months=m) for m in
             [3, 3.0, "3", "x", 0, -1, 2.7, True, False, 100, 6, 1, None, "3.5", " 4 ", 0.5, [2], "", 8, 12]]
    calls += [c(PAY, credit_card_account_id="nope", months=3), c(PAY, credit_card_account_id="", months=3),
              c(PAY, credit_card_account_id=ccb), c(PAY, months=3)]
    seq(f"pay-{t}", calls, task=t)
seq("pay-base", [c(PAY, credit_card_account_id="cc_5e4c1a83b0_bronze", months=6),
                 c(PAY, credit_card_account_id="cc_5e4c1a83b0_bronze", months="abc")])

# --- approve
seq("app-basic", [c(APP, credit_card_account_id=cc, user_id=u, new_credit_limit=20000),
                  c(HIST, credit_card_account_id=cc),
                  c(APP, credit_card_account_id=cc, user_id=u, new_credit_limit=25000.5),
                  c(APP, credit_card_account_id=cc, user_id=u, new_credit_limit="30000"),
                  c(ALL, user_id=u), c(HIST, credit_card_account_id=cc)])
for i, v in enumerate([7500, 7500.0, "7500", "abc", None, True, "", 0, -5, 1e6, "1,000", [1]]):
    seq(f"app-val-{i}", [c(APP, credit_card_account_id=cc, user_id=u, new_credit_limit=v), c(HIST, credit_card_account_id=cc)])
seq("app-errors", [c(APP, credit_card_account_id="", user_id=u, new_credit_limit=1),
                   c(APP, credit_card_account_id=cc, user_id="", new_credit_limit=1),
                   c(APP, credit_card_account_id="nope", user_id=u, new_credit_limit=1),
                   c(APP, credit_card_account_id=cc, user_id="x", new_credit_limit=1),
                   c(APP, credit_card_account_id=cc, user_id=u),
                   c(APP, credit_card_account_id="cc_6680a37184_silver", user_id="6680a37184", new_credit_limit=5000),
                   c(APP, credit_card_account_id="cc_e3f4a5b6c7_eco", user_id="e3f4a5b6c7", new_credit_limit=5000),
                   c(APP, credit_card_account_id="cc_e3f4a5b6c7_gold", user_id="e3f4a5b6c7", new_credit_limit=5000),
                   c(APP, credit_card_account_id="cc_h1i2j3k4l5_eco", user_id="h1i2j3k4l5", new_credit_limit=5000),
                   c(APP, credit_card_account_id="cc_584f9c5d00_gold", user_id="584f9c5d00", new_credit_limit=6000),
                   c(APP, credit_card_account_id="cc_584f9c5d00_gold", user_id="584f9c5d00", new_credit_limit=7000),
                   c(APP, credit_card_account_id="cc_584f9c5d00_gold", user_id="584f9c5d00", new_credit_limit=8000),
                   c(HIST, credit_card_account_id="cc_584f9c5d00_gold")])
for t, acct, usr in [("task_046", "cc_224959b99e_plat", "224959b99e"), ("task_040", "cc_01f21c9970_gold", "01f21c9970"),
                     ("task_054", "cc_584f9c5d00_gold", "584f9c5d00"), ("task_080", "cc_tm92c4d7e8_gold", "tm92c4d7e8"),
                     ("task_081", "cc_tm92c4d7e8_eco", "tm92c4d7e8"), ("task_094", "cc_wl94k7m3p8_platinum", "wl94k7m3p8"),
                     ("task_052", "cc_5e4c1a83b0_bronze", "5e4c1a83b0"), ("task_051", "cc_5e4c1a83b0_bronze", "5e4c1a83b0")]:
    seq(f"app-{t}", [c(APP, credit_card_account_id=acct, user_id=usr, new_credit_limit=12345),
                     c(SUB, credit_card_account_id=acct, user_id=usr, requested_increase_amount=500),
                     c(DEN, credit_card_account_id=acct, user_id=usr, denial_reason="high_utilization"),
                     c(HIST, credit_card_account_id=acct), c(ALL, user_id=usr)], task=t)
# same increase -> same id twice (approve from 5000 to 6000 then from 6000 to 7000 = same increase 1000)
seq("app-collide", [c(APP, credit_card_account_id="cc_584f9c5d00_gold", user_id="584f9c5d00", new_credit_limit=6000),
                    c(APP, credit_card_account_id="cc_584f9c5d00_gold", user_id="584f9c5d00", new_credit_limit=7000.0),
                    c(HIST, credit_card_account_id="cc_584f9c5d00_gold")])

# --- deny
reasons = ["insufficient_account_age", "cooldown_period_active", "pending_disputes", "pending_replacement_card",
           "past_due_balance", "high_utilization", "insufficient_payment_history", "requested_amount_exceeds_limit",
           "other", "bogus", "", None, 5, "OTHER"]
seq("deny", [c(DEN, credit_card_account_id=cc, user_id=u, denial_reason=r) for r in reasons] +
    [c(DEN, credit_card_account_id="nope", user_id=u, denial_reason="other"),
     c(DEN, credit_card_account_id=cc, user_id="someone_else", denial_reason="other"),
     c(DEN, credit_card_account_id="", user_id=u, denial_reason="other"),
     c(DEN, credit_card_account_id=cc, denial_reason="other"),
     c(HIST, credit_card_account_id=cc)])
seq("deny-sub", [c(DEN, credit_card_account_id=cc, user_id=u, denial_reason="other"),
                 c(SUB, credit_card_account_id=cc, user_id=u, requested_increase_amount=0.0001),
                 c(APP, credit_card_account_id=cc, user_id=u, new_credit_limit=15000),
                 c(HIST, credit_card_account_id=cc)])

# --- open accounts
users = ["123", "125", "224959b99e", "e9d195fe8e", "b5e8f2a1c9", "yt71c9e4f2", "jm60a8b9c2", "jc61f7a8d2",
         "mm61b4c8d3", "lj82d4f1a9", "kj93a7b2e1", "126", "nobody", "6680a37184"]
for us in users:
    seq(f"open-{us}", [c(OPEN, user_id=us, account_type=t, account_class=f"{t} class") for t in
                       ["savings", "business_checking", "business_savings", "checking"]] +
        [c(OPEN, user_id=us, account_type="savings", account_class="Bronze Account"),
         c(ALL, user_id=us)])
for t, us in [("task_098", "pj94k7x3m2"), ("task_099", "ky83m9p2t6"), ("task_100", "ti87k4x9m2"),
              ("task_101", "nk80d5r2j7"), ("task_093", "sp93k4m7n2"), ("task_090", "er38a7c9d2")]:
    seq(f"open-{t}", [c(OPEN, user_id=us, account_type=ty, account_class="Some Class") for ty in
                      ["savings", "business_checking", "business_savings", "checking"]] + [c(ALL, user_id=us)], task=t)
seq("open-errors", [c(OPEN, user_id="", account_type="checking", account_class="x"),
                    c(OPEN, user_id="123", account_type="", account_class="x"),
                    c(OPEN, user_id="123", account_type="checking", account_class=""),
                    c(OPEN, user_id="123", account_type="investment", account_class="x"),
                    c(OPEN, user_id="123", account_type="Checking", account_class="x"),
                    c(OPEN, user_id="123", account_type=["checking"], account_class="x"),
                    c(OPEN, user_id="123", account_type="checking"),
                    c(OPEN, user_id="123", account_type="checking", account_class="x"),
                    c(OPEN, user_id="123", account_type="checking", account_class="x"),
                    c(OPEN, user_id=123, account_type="checking", account_class="x"),
                    c(OPEN, user_id="newbie", account_type="checking", account_class="Blue Account"),
                    c(OPEN, user_id="newbie", account_type="savings", account_class="Bronze Account"),
                    c(OPEN, user_id="newbie", account_type="business_checking", account_class="Navy"),
                    c(OPEN, user_id="newbie", account_type="business_savings", account_class="Navy Sav"),
                    c(ALL, user_id="newbie")])

# --- close
seq("close-base", [c(CLOSE, account_id=a) for a in
                   ["01", "02", "03", "05", "08", "61d4e5f6a7b8c901", "58d57780cc15e32d", "chk_rp65a7b3c4", "61c9d8e7f6a5b432",
                    "biz_chk_b5e8f2a1c9", "nope", "", None]] +
    [c(CLOSE, account_id="58d57780cc15e32d"), c(CLOSE, account_id="chk_rp65a7b3c4", reason="moving", waive_early_closure_fee=True),
     c(CLOSE), c(CLOSE, account_id="01", extra=True), c(ALL, user_id="jm60a8b9c2"), c(ALL, user_id="rp65a7b3c4")])
seq("close-waive", [c(CLOSE, account_id="01", waive_early_closure_fee=True),
                    c(CLOSE, account_id="61d4e5f6a7b8c901", waive_early_closure_fee="yes", reason=None),
                    c(ALL, user_id="123"), c(ALL, user_id="mm61b4c8d3")])
seq("close-waive2", [c(CLOSE, account_id="01", waive_early_closure_fee=0, reason=42),
                     c(CLOSE, account_id="61d4e5f6a7b8c901", waive_early_closure_fee=1)])
# fee exactly equal to balance: 38a2c5f77504be6c Blue 11/04/2025 1500 -> transfer 1475 away leaving 25 == fee
seq("close-fee-equal", [c(XFER, source_account_id="38a2c5f77504be6c", destination_account_id="58d57780cc15e32d", amount=1475),
                        c(CLOSE, account_id="38a2c5f77504be6c", reason="fee equal"),
                        c(ALL, user_id="jm60a8b9c2"),
                        c(XFER, source_account_id="38a2c5f77504be6c", destination_account_id="58d57780cc15e32d", amount=1),
                        c(XFER, source_account_id="58d57780cc15e32d", destination_account_id="38a2c5f77504be6c", amount=1)])
seq("close-fee-less", [c(XFER, source_account_id="38a2c5f77504be6c", destination_account_id="58d57780cc15e32d", amount=1490.5),
                       c(CLOSE, account_id="38a2c5f77504be6c"),
                       c(CLOSE, account_id="38a2c5f77504be6c", waive_early_closure_fee=True),
                       c(XFER, source_account_id="38a2c5f77504be6c", destination_account_id="58d57780cc15e32d", amount=9.5),
                       c(CLOSE, account_id="38a2c5f77504be6c", waive_early_closure_fee=True)])
seq("close-opened", [c(OPEN, user_id="zz", account_type="checking", account_class="Blue Account"),
                     c(ALL, user_id="zz")])
seq("close-new", [c(OPEN, user_id="zz", account_type="checking", account_class="Blue Account"),
                  c(CLOSE, account_id="51e17b0d44b3daa0"), c(ALL, user_id="zz")])
for t, accs in [("task_098", ["chk_pj94k7x3m2_greenfeefree", "chk_pj94k7x3m2_lightgreen"]),
                ("task_090", ["chk_er38a7c9d2_blue", "chk_er38a7c9d2_green"]),
                ("task_097", ["sav_mc80w7k3x9_silverplus", "chk_mc80w7k3x9_evergreen"]),
                ("task_096", ["chk_ni73k9m4p2_goldyears", "sav_ni73k9m4p2_bronze"])]:
    seq(f"close-{t}", [c(CLOSE, account_id=a) for a in accs] + [c(CLOSE, account_id=a, waive_early_closure_fee=True) for a in accs], task=t)
# savings class "savings" (only reachable via deep update?) -- skip; lightgreen 350 in task_098: transfer 335 leaving 15 == fee
seq("close-t098-fee", [c(XFER, source_account_id="chk_pj94k7x3m2_lightgreen", destination_account_id="chk_pj94k7x3m2_greenfeefree", amount="335"),
                       c(CLOSE, account_id="chk_pj94k7x3m2_lightgreen"),
                       c(TX, account_id="chk_pj94k7x3m2_lightgreen"),
                       c(ALL, user_id="pj94k7x3m2")], task="task_098")

# --- get all accounts
seq("all", [c(ALL, user_id=x) for x in ["123", "e9d195fe8e", "lj82d4f1a9", "nobody", "", None, 'a"b', "76ad9cc60e", 123, "5e4c1a83b0"]] + [c(ALL)])
seq("all-t052", [c(ALL, user_id="5e4c1a83b0")], task="task_052")

# --- transfer
S, D = "chk_lj82d4f1a9", "chk_538bfb9cba"
xamts = [100, 100.0, "100", "abc", None, True, 0, -5, 0.1, 1e-9, "1e2", 127450, 127450.01, 1e300, "nan", "inf", [1], "", 33.333, 0.005]
for i, a in enumerate(xamts):
    seq(f"xfer-{i}", [c(XFER, source_account_id=S, destination_account_id=D, amount=a), c(ALL, user_id="lj82d4f1a9")])
seq("xfer-errs", [c(XFER, source_account_id=S, destination_account_id=S, amount=1),
                  c(XFER, source_account_id="nope", destination_account_id=D, amount=1),
                  c(XFER, source_account_id=S, destination_account_id="nope", amount=1),
                  c(XFER, source_account_id="", destination_account_id=D, amount=1),
                  c(XFER, source_account_id=S, destination_account_id=D),
                  c(XFER, source_account_id=S, destination_account_id=None, amount=1),
                  c(XFER, source_account_id="01", destination_account_id="02", amount=1),
                  c(XFER, source_account_id="02", destination_account_id="01", amount=129.02),
                  c(XFER, source_account_id="02", destination_account_id="01", amount=0.01),
                  c(CLOSE, account_id="02"), c(CLOSE, account_id="01"),
                  c(XFER, source_account_id="01", destination_account_id="02", amount=1),
                  c(XFER, source_account_id="03", destination_account_id="01", amount=1),
                  c(XFER, source_account_id="01", destination_account_id="03", amount=1),
                  c(ALL, user_id="123")])
seq("xfer-chain", [c(XFER, source_account_id="03", destination_account_id="04", amount=a) for a in [1000, 400.5, 1999.5, 0.01, 1]] +
    [c(XFER, source_account_id="04", destination_account_id="03", amount=4300), c(ALL, user_id="125")])

# --- credits
for fn, types, accs in [(CHK, ["rebate_credit", "fee_refund", "goodwill_credit", "", None, "REBATE_CREDIT"], ["05", "02", "biz_chk_b5e8f2a1c9", "nope"]),
                        (SAV, ["interest_correction", "fee_refund", "goodwill_credit", "rebate_credit", "", None], ["02", "04", "05", "61c9d8e7f6a5b432", "nope"])]:
    calls = []
    for a in accs:
        for ty in types:
            calls.append(c(fn, account_id=a, amount=12.34, credit_type=ty))
    for amt in [10, 10.0, "10", "x", None, 0, -1, True, 0.005, 1e-7, "", [1], 2.675]:
        calls.append(c(fn, account_id=accs[0], amount=amt, credit_type=types[1]))
    calls += [c(fn, account_id=accs[0], amount=10, credit_type=types[1]),
              c(fn, account_id="", amount=10, credit_type=types[0]),
              c(fn, account_id=accs[0], credit_type=types[0]),
              c(TX, account_id=accs[0]), c(ALL, user_id="123"), c(ALL, user_id="224959b99e")]
    seq(f"credit-{fn}", calls)
seq("credit-closed", [c(CLOSE, account_id="58d57780cc15e32d"),
                      c(CHK, account_id="58d57780cc15e32d", amount=5, credit_type="fee_refund"),
                      c(OPEN, user_id="zz", account_type="savings", account_class="x"),
                      c(OPEN, user_id="jc61f7a8d2", account_type="savings", account_class="Bronze Account"),
                      c(SAV, account_id="7993b7526f18c7f0", amount=5, credit_type="fee_refund")])
seq("credit-task", [c(CHK, account_id="chk_tw34a7d9e2_lightblue", amount=25, credit_type="fee_refund"),
                    c(CHK, account_id="chk_tw34a7d9e2_lightblue", amount=25.0, credit_type="fee_refund"),
                    c(TX, account_id="chk_tw34a7d9e2_lightblue")], task="task_091")
seq("credit-sav-task", [c(SAV, account_id="sav_sp93k4m7n2_silver", amount=123.456, credit_type="interest_correction"),
                        c(TX, account_id="sav_sp93k4m7n2_silver"), c(ALL, user_id="sp93k4m7n2")], task="task_093")

# --- interest report
calls = []
for e, a, d in [(2.775, 2.5, 12.34), (2.775, 2.5, 12), (3, 2, 5), ("3.1", "2.9", "1.5"), ("x", 2, 1), (3, None, 1),
                (0.1, 0.2, 0.3), (True, False, 0), (1e-5, 3e-5, -2.5), (2.8, 2.5, 0.005), ([1], 2, 3)]:
    calls.append(c(IDR, account_id="04", user_id="125", expected_apy=e, actual_apy=a, amount_difference=d))
calls += [c(IDR, account_id="nope", user_id="125", expected_apy=1, actual_apy=1, amount_difference=1),
          c(IDR, account_id="04", user_id="nope", expected_apy=1, actual_apy=1, amount_difference=1),
          c(IDR, account_id="04", user_id="126", expected_apy=1, actual_apy=1, amount_difference=1),
          c(IDR, account_id="", user_id="125", expected_apy=1, actual_apy=1, amount_difference=1),
          c(IDR, account_id="04", user_id="125", expected_apy=1, actual_apy=1),
          c(IDR, account_id=4, user_id="125", expected_apy=1, actual_apy=1, amount_difference=1)]
seq("idr", calls)
seq("idr-task", [c(IDR, account_id="sav_wl94k7m3p8_gold", user_id="wl94k7m3p8", expected_apy=2.775, actual_apy=2.5, amount_difference=264.0)], task="task_094")

# --- transactions
seq("tx-base", [c(TX, account_id=a) for a in ["chk_ar72c5d8e3_1", "chk_lj82d4f1a9", "chk_538bfb9cba", "01", "nope", "", None, 5]] + [c(TX)])
for t, accs in [("task_090", ["chk_er38a7c9d2_evergreen", "chk_er38a7c9d2_green", "chk_er38a7c9d2_blue"]),
                ("task_091", ["chk_tw34a7d9e2_green", "chk_tw34a7d9e2_evergreen", "chk_tw34a7d9e2_blue", "chk_tw34a7d9e2_lightblue"]),
                ("task_092", ["chk_rw42b8d3e1_blue", "chk_rw42b8d3e1_green", "chk_rw42b8d3e1_evergreen", "chk_rw42b8d3e1_lightblue"]),
                ("task_078", ["chk_mc78a5b9d2_1", "chk_mc78a5b9d2_2"]), ("task_082", ["chk_mc47a2b9e1_blue"]),
                ("task_088", ["chk_cec8333d41_green", "chk_cec8333d41_evergreen"])]:
    seq(f"tx-{t}", [c(TX, account_id=a) for a in accs] +
        [c(CHK, account_id=accs[0], amount=1, credit_type="fee_refund"), c(TX, account_id=accs[0])], task=t)

json.dump(seqs, open(sys.argv[1], "w"), indent=1)
print(len(seqs), sum(len(s["calls"]) for s in seqs))
