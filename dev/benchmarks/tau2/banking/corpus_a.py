"""Generator for a banking_knowledge differential corpus (provenance only).

The generated corpus is vendored in test/dvergr/benchmarks/tau2/banking_corpus.json;
regenerate + re-run dev/benchmarks/tau2/oracle.py only when porting a new upstream
revision. Paths below are those of the original run.
"""
import json, copy
seqs = []
def S(id, calls, task=None, inject=None):
    s = {"id": id, "calls": [{"name": n, "arguments": a, "requestor": "assistant"} for n, a in calls]}
    if task: s["task"] = task
    if inject: s["inject"] = inject
    seqs.append(s)

# --- update_transaction_rewards_3847
U = "update_transaction_rewards_3847"
S("utr-ok", [(U, {"transaction_id": "txn_8d1aa1219382", "new_rewards_earned": "6300 points"}),
             ("get_credit_card_transactions_by_user", {"user_id": "01f21c9970"})])
S("utr-float", [(U, {"transaction_id": "txn_8d1aa1219382", "new_rewards_earned": 6300.0})])
S("utr-int", [(U, {"transaction_id": "txn_8d1aa1219382", "new_rewards_earned": 6300})])
S("utr-list", [(U, {"transaction_id": "txn_8d1aa1219382", "new_rewards_earned": ["a", 1, {"x": None, "y": True}]})])
S("utr-unicode", [(U, {"transaction_id": "txn_8d1aa1219382", "new_rewards_earned": "café \"q\" 10 pts"})])
S("utr-missing", [(U, {"transaction_id": "", "new_rewards_earned": "1"}), (U, {"transaction_id": "x", "new_rewards_earned": ""}),
                  (U, {"transaction_id": "x", "new_rewards_earned": 0}), (U, {"transaction_id": None, "new_rewards_earned": "1"})])
S("utr-notfound", [(U, {"transaction_id": "txn_nope", "new_rewards_earned": "1 points"}),
                   (U, {"transaction_id": 5.0, "new_rewards_earned": "1 points"})])
S("utr-argerr", [(U, {"transaction_id": "txn_8d1aa1219382"}), (U, {}), (U, {"transaction_id": "a", "new_rewards_earned": "b", "extra": 1})])
S("utr-twice", [(U, {"transaction_id": "txn_8d1aa1219382", "new_rewards_earned": "1 points"}),
                (U, {"transaction_id": "txn_8d1aa1219382", "new_rewards_earned": "2 points"})])

# --- constant tools
for n in ["initial_transfer_to_human_agent_0218", "initial_transfer_to_human_agent_1822", "emergency_credit_bureau_incident_transfer_1114"]:
    S("const-" + n, [(n, {}), (n, {"x": 1})])

# --- file_credit_card_transaction_dispute_4829
F = "file_credit_card_transaction_dispute_4829"
base = {"transaction_id": "txn_8d1aa1219382", "card_action": "keep_active", "card_last_4_digits": "4821",
        "full_name": "Kenji Tanaka", "user_id": "01f21c9970", "phone": "206-555-0293", "email": "k@x.com",
        "address": "1 Main St", "contacted_merchant": True, "purchase_date": "10/02/2025",
        "issue_noticed_date": "11/01/2025", "dispute_reason": "duplicate_charge",
        "resolution_requested": "full_refund", "eligible_for_provisional_credit": True}
def v(b, **kw):
    d = copy.deepcopy(b); d.update(kw); return d
S("fcd-ok", [(F, base), (F, base), ("get_user_dispute_history_7291", {"user_id": "01f21c9970"})])
S("fcd-partial-float", [(F, v(base, resolution_requested="partial_refund", partial_refund_amount=50.0, eligible_for_provisional_credit=False))])
S("fcd-partial-int", [(F, v(base, resolution_requested="partial_refund", partial_refund_amount=33))])
S("fcd-partial-odd", [(F, v(base, resolution_requested="partial_refund", partial_refund_amount=12.345))])
S("fcd-partial-none", [(F, v(base, resolution_requested="partial_refund"))])
S("fcd-partial-null", [(F, v(base, resolution_requested="partial_refund", partial_refund_amount=None))])
S("fcd-partial-zero", [(F, v(base, resolution_requested="partial_refund", partial_refund_amount=0))])
S("fcd-partial-str", [(F, v(base, resolution_requested="partial_refund", partial_refund_amount="50"))])
S("fcd-full-with-amount", [(F, v(base, partial_refund_amount=20.5, eligible_for_provisional_credit=0))])
S("fcd-bad-action", [(F, v(base, card_action="cancel"))])
S("fcd-cancel", [(F, v(base, card_action="cancel_and_reissue", dispute_reason="unauthorized_fraudulent_charge",
                         eligible_for_provisional_credit="no"))])
S("fcd-bad-reason", [(F, v(base, dispute_reason="fraud"))])
S("fcd-bad-res", [(F, v(base, resolution_requested="refund"))])
S("fcd-missing", [(F, v(base, transaction_id="")), (F, v(base, user_id=None)), (F, v(base, user_id=0.0))])
S("fcd-argerr", [(F, {k: base[k] for k in list(base)[:5]}), (F, v(base, foo=1))])
S("fcd-num-ids", [(F, v(base, transaction_id=12.0, user_id=7)), (F, v(base, transaction_id=12, user_id=7.0))])
S("fcd-existing", [(F, v(base, user_id="6680a37184", transaction_id="txn_913d14a20dc5"))], task=None)
for r in ["unauthorized_fraudulent_charge", "incorrect_amount", "goods_services_not_received", "goods_services_not_as_described",
          "canceled_subscription_still_charging", "refund_never_processed"]:
    S("fcd-r-" + r, [(F, v(base, dispute_reason=r, transaction_id="t_" + r))])
S("fcd-task", [(F, v(base, user_id="6680a37184")), ("get_user_dispute_history_7291", {"user_id": "6680a37184"})], task="task_040")

# --- file_debit_card_transaction_dispute_6281
D = "file_debit_card_transaction_dispute_6281"
dbase = {"transaction_id": "btxn_0788d2513c8c", "account_id": "chk_cr47f8d2a1_evergreen", "card_id": "dbc_cr47f8d2a1_evergreen",
         "user_id": "cr47f8d2a1", "dispute_category": "atm_deposit_not_credited", "transaction_date": "11/06/2025",
         "discovery_date": "11/07/2025", "disputed_amount": 400.0, "transaction_type": "atm_deposit",
         "card_in_possession": True, "pin_compromised": "no", "contacted_merchant": False, "police_report_filed": False,
         "written_statement_provided": True, "provisional_credit_eligible": True, "customer_max_liability_amount": 0.0,
         "card_action": "keep_active"}
S("dd-ok", [(D, dbase), (D, dbase), ("get_debit_dispute_status_7483", {"user_id": "cr47f8d2a1"})], task="task_086")
S("dd-ok-base", [(D, dbase), ("get_debit_dispute_status_7483", {"user_id": "cr47f8d2a1"})])
S("dd-int", [(D, v(dbase, disputed_amount=400, customer_max_liability_amount=50, provisional_credit_eligible=False))])
S("dd-fraud-big", [(D, v(dbase, dispute_category="unauthorized_transaction", disputed_amount=750.5, transaction_type="online_purchase",
                          pin_compromised="yes_shared", customer_max_liability_amount=-1, card_action="freeze_pending_investigation"))])
S("dd-fraud-500", [(D, v(dbase, dispute_category="card_present_fraud", disputed_amount=500, transaction_type="pin_purchase"))])
S("dd-fraud-500f", [(D, v(dbase, dispute_category="card_not_present_fraud", disputed_amount=500.01, police_report_filed=True,
                           card_action="close_and_reissue", pin_compromised="yes_observed"))])
S("dd-fraud-nopolice-str", [(D, v(dbase, dispute_category="card_not_present_fraud", disputed_amount=900, police_report_filed="",
                                   pin_compromised="unknown"))])
S("dd-neg", [(D, v(dbase, disputed_amount=-5)), (D, v(dbase, disputed_amount=-0.5))])
S("dd-zero", [(D, v(dbase, disputed_amount=0)), (D, v(dbase, disputed_amount=0.0))])
S("dd-str-amount", [(D, v(dbase, disputed_amount="400"))])
S("dd-bool-amount", [(D, v(dbase, disputed_amount=True))])
S("dd-maxliab-none", [(D, v(dbase, customer_max_liability_amount=None))])
S("dd-bool-none", [(D, v(dbase, card_in_possession=None)), (D, v(dbase, written_statement_provided=None)),
                   (D, v(dbase, police_report_filed=None)), (D, v(dbase, contacted_merchant=None))])
S("dd-missing", [(D, v(dbase, account_id="")), (D, v(dbase, card_action=None)), (D, v(dbase, discovery_date=""))])
S("dd-bad-cat", [(D, v(dbase, dispute_category="fraud"))])
S("dd-bad-type", [(D, v(dbase, transaction_type="wire"))])
S("dd-bad-pin", [(D, v(dbase, pin_compromised="maybe"))])
S("dd-bad-action", [(D, v(dbase, card_action="cancel_and_reissue"))])
S("dd-argerr", [(D, {k: dbase[k] for k in list(dbase)[:10]}), (D, v(dbase, bogus=True))])
for c in ["atm_cash_discrepancy", "duplicate_charge", "incorrect_amount", "goods_services_not_received", "recurring_charge_after_cancellation"]:
    for t in ["signature_purchase", "atm_withdrawal", "recurring_payment", "person_to_person"][:2]:
        S(f"dd-c-{c}-{t}", [(D, v(dbase, dispute_category=c, transaction_type=t, transaction_id=c + t, disputed_amount=123.456))])
S("dd-existing", [(D, v(dbase, transaction_id="btxn_e139a180b899")), ("get_debit_dispute_status_7483", {"user_id": "cr47f8d2a1"})], task="task_086")

# --- set_debit_card_recurring_block_7382
R = "set_debit_card_recurring_block_7382"
S("rb-block", [(R, {"card_id": "dbc_lj82d4f1a9_bluest", "block_recurring": True}),
               (R, {"card_id": "dbc_lj82d4f1a9_bluest", "block_recurring": False})])
S("rb-unblock", [(R, {"card_id": "dbc_538bfb9cba", "block_recurring": False})])
S("rb-truthy", [(R, {"card_id": "dbc_538bfb9cba", "block_recurring": "false"}), (R, {"card_id": "dbc_kj93a7b2e1_blue", "block_recurring": 0}),
                (R, {"card_id": "dbc_kj93a7b2e1_green", "block_recurring": 1.0}), (R, {"card_id": "dbc_kj93a7b2e1_lightgreen", "block_recurring": None})])
S("rb-pending", [(R, {"card_id": "dbc_7a3f9c2b1e", "block_recurring": True})])
S("rb-missing", [(R, {"card_id": "", "block_recurring": True}), (R, {"card_id": "nope", "block_recurring": True}), (R, {"card_id": "x"})])
S("rb-inject", [(R, {"card_id": "dbc_lower", "block_recurring": True}), (R, {"card_id": "dbc_nostatus", "block_recurring": True}),
                (R, {"card_id": "dbc_nonestatus", "block_recurring": True}), (R, {"card_id": "dbc_frozen", "block_recurring": False})],
  inject=[["debit_cards", "dbc_lower", {"card_id": "dbc_lower", "status": "active", "a1": 1, "a2": 2, "a3": 3, "a4": 4, "a5": 5, "a6": 6, "a7": 7, "a8": 8}],
          ["debit_cards", "dbc_nostatus", {"card_id": "dbc_nostatus"}],
          ["debit_cards", "dbc_nonestatus", {"card_id": "dbc_nonestatus", "status": None}],
          ["debit_cards", "dbc_frozen", {"card_id": "dbc_frozen", "status": "FROZEN", "recurring_blocked": True}]])
S("rb-task", [(R, {"card_id": "dbc_cr47f8d2a1_gff", "block_recurring": True})], task="task_086")

# --- read history tools
for name, param, vals in [("get_debit_dispute_status_7483", "user_id", ["cr47f8d2a1", "nobody", "", 5.0, 'a"b']),
                          ("get_user_dispute_history_7291", "user_id", ["6680a37184", "nobody", "", 7, 'q"']),
                          ("get_pending_replacement_orders_5765", "credit_card_account_id", ["cc_e3f4a5b6c7_eco", "cc_none", None, 'x"y']),
                          ("get_closure_reason_history_8293", "credit_card_account_id", ["cc_e3f4a5b6c7_green", "cc_none", "", '"'])]:
    S("hist-" + name, [(name, {param: x}) for x in vals] + [(name, {}), (name, {param: "a", "z": 1})])
    S("hist-task-" + name, [(name, {param: x}) for x in vals[:2]], task="task_086")
S("hist-task054", [("get_user_dispute_history_7291", {"user_id": "6680a37184"}),
                   ("get_pending_replacement_orders_5765", {"credit_card_account_id": "cc_e3f4a5b6c7_eco"})], task="task_054")

# --- get_atm_deposit_images_8473
A = "get_atm_deposit_images_8473"
S("atm-task", [(A, {"transaction_id": "btxn_0788d2513c8c"}), (A, {"transaction_id": "btxn_e139a180b899"}), (A, {"transaction_id": "nope"}),
               (A, {"transaction_id": ""}), (A, {})], task="task_086")
S("atm-base", [(A, {"transaction_id": "btxn_bd30d42f67a2"}), (A, {"transaction_id": None})])
atm_inj = [["bank_account_transaction_history", "btxn_834027370c20", {"transaction_id": "btxn_834027370c20", "date": "11/05/2025", "description": "RHO-BANK ATM #3921 DEPOSIT", "amount": 385.0, "type": "atm_deposit"}],
           ["bank_account_transaction_history", "btxn_test_deposit_001", {"transaction_id": "btxn_test_deposit_001", "description": "rhobank atm", "amount": 385, "type": "atm_deposit"}],
           ["bank_account_transaction_history", "btxn_test_atm_dep_partial", {"transaction_id": "btxn_test_atm_dep_partial", "date": "11/09/2025", "description": "RhoBank ATM #5847", "amount": 500.0, "type": "atm_deposit"}],
           ["bank_account_transaction_history", "btxn_third", {"transaction_id": "btxn_third", "date": "11/09/2025", "description": "CHASE ATM DEPOSIT", "amount": 20.0, "type": "atm_deposit"}],
           ["bank_account_transaction_history", "btxn_neg", {"transaction_id": "btxn_neg", "date": "11/09/2025", "description": "Rho-Bank atm", "amount": -20.125, "type": "atm_deposit"}],
           ["bank_account_transaction_history", "btxn_noamt", {"transaction_id": "btxn_noamt", "description": "RHO-BANK", "type": "atm_deposit"}],
           ["bank_account_transaction_history", "btxn_intamt", {"transaction_id": "btxn_intamt", "description": "RHO-BANK", "type": "atm_deposit", "amount": -7, "date": None}],
           ["bank_account_transaction_history", "btxn_stramt", {"transaction_id": "btxn_stramt", "description": "RHO-BANK", "type": "atm_deposit", "amount": "$5"}],
           ["bank_account_transaction_history", "btxn_nodesc", {"transaction_id": "btxn_nodesc", "type": "atm_deposit"}],
           ["bank_account_transaction_history", "btxn_nonedesc", {"transaction_id": "btxn_nonedesc", "type": "atm_deposit", "description": None}],
           ["bank_account_transaction_history", "btxn_notype", {"transaction_id": "btxn_notype"}]]
S("atm-inject", [(A, {"transaction_id": r[1]}) for r in atm_inj], inject=atm_inj)

# --- order_replacement_credit_card_7291
O = "order_replacement_credit_card_7291"
obase = {"credit_card_account_id": "cc_lj82d4f1a9_silver", "user_id": "lj82d4f1a9", "shipping_address": "1 Main St, Austin, TX", "reason": "lost"}
S("orc-ok", [(O, obase), (O, obase), ("get_pending_replacement_orders_5765", {"credit_card_account_id": "cc_lj82d4f1a9_silver"}),
             ("get_credit_card_accounts_by_user", {"user_id": "lj82d4f1a9"})])
S("orc-exp", [(O, v(obase, credit_card_account_id="cc_224959b99e_plat", user_id="224959b99e", reason="fraud_suspected", expedited_shipping=True)),
              (O, v(obase, credit_card_account_id="cc_224959b99e_plat", user_id="224959b99e", reason="stolen", expedited_shipping="yes"))])
S("orc-exp-false", [(O, v(obase, reason="damaged", expedited_shipping=0)), (O, v(obase, reason="expired", expedited_shipping=None)),
                    (O, v(obase, reason="other", expedited_shipping=1.0))])
S("orc-bad", [(O, v(obase, reason="broken")), (O, v(obase, shipping_address="")), (O, v(obase, credit_card_account_id="cc_none")),
              (O, v(obase, credit_card_account_id='cc"x')), (O, v(obase, credit_card_account_id=3.0)), (O, {"credit_card_account_id": "a"}),
              (O, v(obase, extra=1))])
S("orc-other-user", [(O, v(obase, user_id="someone_else"))])
S("orc-task", [(O, v(obase, credit_card_account_id="cc_e3f4a5b6c7_eco", user_id="e3f4a5b6c7"))], task="task_054")

# --- log_credit_card_closure_reason_4521
L = "log_credit_card_closure_reason_4521"
S("lcr-ok", [(L, {"credit_card_account_id": "cc_lj82d4f1a9_silver", "user_id": "lj82d4f1a9", "closure_reason": "annual_fee"}),
             (L, {"credit_card_account_id": "cc_lj82d4f1a9_silver", "user_id": "lj82d4f1a9", "closure_reason": "other"}),
             ("get_closure_reason_history_8293", {"credit_card_account_id": "cc_lj82d4f1a9_silver"})])
S("lcr-all", [(L, {"credit_card_account_id": "cc_" + r, "user_id": "u", "closure_reason": r}) for r in
              ["not_using_card", "found_better_card", "unhappy_with_rewards", "simplifying_finances", "negative_experience"]])
S("lcr-bad", [(L, {"credit_card_account_id": "a", "user_id": "u", "closure_reason": "bored"}), (L, {"credit_card_account_id": "", "user_id": "u", "closure_reason": "other"}),
              (L, {"credit_card_account_id": "a", "user_id": "u"}), (L, {"credit_card_account_id": 1.0, "user_id": 2, "closure_reason": "other"}),
              (L, {"credit_card_account_id": "aé\"", "user_id": "u", "closure_reason": "other"})])

# --- apply_statement_credit_8472
C = "apply_statement_credit_8472"
cbase = {"user_id": "lj82d4f1a9", "credit_card_account_id": "cc_lj82d4f1a9_silver", "amount": 25.0, "reason": "goodwill_adjustment"}
S("asc-ok", [(C, cbase), (C, cbase), (C, v(cbase, amount=25)), ("get_credit_card_transactions_by_user", {"user_id": "lj82d4f1a9"})])
S("asc-int", [(C, v(cbase, amount=25, reason="annual_fee_reversal"))])
S("asc-odd", [(C, v(cbase, amount=0.125, reason="late_fee_reversal")), (C, v(cbase, amount=1e-9, reason="price_match")),
              (C, v(cbase, amount=1234567.891, reason="retention_offer")), (C, v(cbase, amount=True, reason="error_correction"))])
S("asc-bad", [(C, v(cbase, amount=0)), (C, v(cbase, amount=-1.5)), (C, v(cbase, amount=None)), (C, v(cbase, amount="25")),
              (C, v(cbase, reason="nice")), (C, v(cbase, reason="")), (C, v(cbase, credit_card_account_id="cc_none")),
              (C, v(cbase, credit_card_account_id='c"')), (C, {"user_id": "x"}), (C, v(cbase, amount=[1]))])
for r in ["promotional_credit", "interest_charge_reversal", "dispute_resolution", "other"]:
    S("asc-r-" + r, [(C, v(cbase, reason=r, amount=10.5))])

# --- apply_credit_card_account_flag_6147
G = "apply_credit_card_account_flag_6147"
gbase = {"credit_card_account_id": "cc_lj82d4f1a9_silver", "user_id": "lj82d4f1a9", "flag_type": "annual_fee_waived", "expiration_date": "11/14/2026", "reason": "retention_offer"}
S("fl-ok", [(G, gbase), (G, gbase), (G, v(gbase, expiration_date="12/31/2026"))])
S("fl-types", [(G, v(gbase, flag_type=t, reason=r)) for t, r in [("promotional_apr", "loyalty_benefit"), ("rewards_bonus", "promotional"), ("other", "error_correction"), ("other", "other")]])
S("fl-bad", [(G, v(gbase, flag_type="x")), (G, v(gbase, reason="x")), (G, v(gbase, expiration_date="")), (G, v(gbase, credit_card_account_id="cc_none")),
             (G, v(gbase, credit_card_account_id='"')), (G, v(gbase, expiration_date=20261114)), (G, v(gbase, expiration_date=1.5)), (G, {})])

# --- close_credit_card_account_7834
X = "close_credit_card_account_7834"
S("cl-ok", [(X, {"credit_card_account_id": "cc_lj82d4f1a9_silver", "user_id": "lj82d4f1a9"}),
            (X, {"credit_card_account_id": "cc_lj82d4f1a9_silver", "user_id": "other"}),
            ("get_credit_card_accounts_by_user", {"user_id": "lj82d4f1a9"})])
S("cl-nostatus", [(X, {"credit_card_account_id": "cc_76ad9cc60e_gold", "user_id": 5.0}), ("get_credit_card_accounts_by_user", {"user_id": "76ad9cc60e"})])
S("cl-bad", [(X, {"credit_card_account_id": "cc_none", "user_id": "u"}), (X, {"credit_card_account_id": 'a"b', "user_id": "u"}),
             (X, {"credit_card_account_id": "", "user_id": "u"}), (X, {"credit_card_account_id": "a", "user_id": ""}), (X, {"user_id": "u"})])

# --- pay_credit_card_from_checking_9182
P = "pay_credit_card_from_checking_9182"
pbase = {"user_id": "lj82d4f1a9", "checking_account_id": "chk_538bfb9cba", "credit_card_account_id": "cc_lj82d4f1a9_silver", "amount": 100.0}
S("pay-ok", [(P, pbase), (P, v(pbase, amount=100)), (P, v(pbase, amount="42.15")), ("get_credit_card_accounts_by_user", {"user_id": "lj82d4f1a9"})])
S("pay-full", [(P, v(pbase, amount=842.15)), (P, v(pbase, amount=0.01))])
S("pay-over-cc", [(P, v(pbase, amount=900))])
S("pay-over-chk", [(P, v(pbase, amount=2000.0)), (P, v(pbase, checking_account_id="chk_lj82d4f1a9", amount=842.16))])
S("pay-comma", [(P, {"user_id": "e9d195fe8e", "checking_account_id": "chk_e9d195fe8e", "credit_card_account_id": "cc_e9d195fe8e_silver", "amount": 4500}),
                (P, {"user_id": "e9d195fe8e", "checking_account_id": "chk_e9d195fe8e", "credit_card_account_id": "cc_e9d195fe8e_silver", "amount": 1})])
S("pay-zero-cc", [(P, {"user_id": "c7d8e9f0a1", "checking_account_id": "06", "credit_card_account_id": "cc_c7d8e9f0a1_plat", "amount": 1})])
S("pay-small-chk", [(P, {"user_id": "h1i2j3k4l5", "checking_account_id": "08", "credit_card_account_id": "cc_h1i2j3k4l5_gold", "amount": 50}),
                    (P, {"user_id": "h1i2j3k4l5", "checking_account_id": "08", "credit_card_account_id": "cc_h1i2j3k4l5_gold", "amount": 0.3})])
S("pay-bad", [(P, v(pbase, amount=0)), (P, v(pbase, amount=-3)), (P, v(pbase, amount="abc")), (P, v(pbase, amount=None)),
              (P, v(pbase, amount=[1])), (P, v(pbase, amount="  1e1 ")), (P, v(pbase, amount="nan")), (P, v(pbase, amount="inf")),
              (P, v(pbase, amount=True)), (P, v(pbase, amount=False)), (P, v(pbase, amount="$5")),
              (P, v(pbase, checking_account_id="nope")), (P, v(pbase, user_id="224959b99e")), (P, v(pbase, checking_account_id="02", user_id="123")),
              (P, v(pbase, credit_card_account_id="cc_none")), (P, v(pbase, credit_card_account_id="cc_224959b99e_plat")),
              (P, v(pbase, user_id="")), (P, {"user_id": "x", "amount": 1})])
S("pay-saving", [(P, {"user_id": "123", "checking_account_id": "02", "credit_card_account_id": "cc_x", "amount": 1})])
S("pay-inject", [(P, {"user_id": "u9", "checking_account_id": "chk_u9", "credit_card_account_id": "cc_u9", "amount": 10}),
                 (P, {"user_id": "u9", "checking_account_id": "chk_u9b", "credit_card_account_id": "cc_u9b", "amount": 10}),
                 (P, {"user_id": "u9", "checking_account_id": "chk_u9b", "credit_card_account_id": "cc_u9c", "amount": 10}),
                 (P, {"user_id": "u9", "checking_account_id": "chk_u9c", "credit_card_account_id": "cc_u9", "amount": 5})],
  inject=[["accounts", "chk_u9", {"account_id": "chk_u9", "user_id": "u9", "class": "checking", "balance": "$1,000.50", "a": 1, "b": 2, "c": 3, "d": 4, "e": 5, "f": 6, "g": 7}],
          ["accounts", "chk_u9b", {"account_id": "chk_u9b", "user_id": "u9", "class": "checking", "current_holdings": 500}],
          ["accounts", "chk_u9c", {"account_id": "chk_u9c", "user_id": "u9", "class": "checking"}],
          ["credit_card_accounts", "cc_u9", {"account_id": "cc_u9", "user_id": "u9", "current_balance": 250.5, "a": 1, "b": 2, "c": 3, "d": 4, "e": 5, "f": 6, "g": 7}],
          ["credit_card_accounts", "cc_u9b", {"account_id": "cc_u9b", "user_id": "u9"}],
          ["credit_card_accounts", "cc_u9c", {"account_id": "cc_u9c", "user_id": "u9", "current_balance": "garbage"}]])
S("pay-then-close", [(P, v(pbase, amount=842.15)), (X, {"credit_card_account_id": "cc_lj82d4f1a9_silver", "user_id": "lj82d4f1a9"}),
                     (O, v(obase, reason="stolen")), (P, v(pbase, amount=1))])


# --- extra: task-driven sweeps
tasks = {t["id"]: t for t in json.load(open("/home/christian-weilbach/Development/tau2-bench/data/tau2/domains/banking_knowledge/tasks.json"))}
for tid in ["task_078", "task_080", "task_082", "task_083", "task_084", "task_085", "task_087", "task_090", "task_092"]:
    ad = tasks[tid]["initial_state"]["initialization_data"]["agent_data"]
    calls = []
    for cid, card in ad.get("debit_cards", {}).get("data", {}).items():
        calls.append((R, {"card_id": cid, "block_recurring": True}))
        calls.append(("get_debit_dispute_status_7483", {"user_id": card.get("user_id")}))
    for bid, bt in list(ad.get("bank_account_transaction_history", {}).get("data", {}).items())[:3]:
        calls.append((A, {"transaction_id": bid}))
        calls.append((D, v(dbase, transaction_id=bid, account_id=bt.get("account_id"), disputed_amount=abs(bt.get("amount", 1)) or 1)))
    for ccid, cc in ad.get("credit_card_accounts", {}).get("data", {}).items():
        calls.append((C, v(cbase, user_id=cc.get("user_id"), credit_card_account_id=ccid, amount=12.34)))
        calls.append((O, v(obase, user_id=cc.get("user_id"), credit_card_account_id=ccid)))
    for aid, acc in ad.get("accounts", {}).get("data", {}).items():
        for ccid, cc in ad.get("credit_card_accounts", {}).get("data", {}).items():
            calls.append((P, {"user_id": acc.get("user_id"), "checking_account_id": aid, "credit_card_account_id": ccid, "amount": 10.5}))
    S("sweep-" + tid, calls, task=tid)
S("fcd-neg", [(F, v(base, resolution_requested="partial_refund", partial_refund_amount=-5)),
              (F, v(base, transaction_id="t2", resolution_requested="partial_refund", partial_refund_amount=1e16)),
              (F, v(base, transaction_id="t3", resolution_requested="partial_refund", partial_refund_amount=2.675))])
S("pay-steps", [(P, v(pbase, amount=a)) for a in [0.1, 0.2, 0.3, 0.005, 0.015, 1.005, 99.995]])
S("dd-neg-liab", [(D, v(dbase, customer_max_liability_amount=-1.0, disputed_amount=0.125, provisional_credit_eligible=1))])
json.dump(seqs, open("corpus.json", "w"), indent=1)
print(len(seqs), sum(len(s["calls"]) for s in seqs))
