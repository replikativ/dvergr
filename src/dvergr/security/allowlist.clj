(ns dvergr.security.allowlist
  "Telegram user allowlist for daemon access control.

   Prevents unauthorized users from consuming agent budget.
   Supports both numeric Telegram user IDs and @username strings.

   When the allowed set is empty, behavior depends on `strict?`:
   - default (strict? false): all users are permitted (backwards compat), but a
     warning is logged so the operator knows access is open.
   - strict? true: an empty allowlist DENIES everyone (fail-closed). Opt in via
     the daemon's `:strict-allowlist? true` config (or `(set-strict! true)`).

   The daemon calls `configure!` on every start, so both the user set and the
   strict flag come from the current config — an empty list or an absent flag
   resets what a previous start installed.

   The policy is stored in one global atom for runtime mutability:
     (add-user! \"@christian_w\")
     (remove-user! \"@spammer\")
     (list-users)"
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]))

;; ============================================================================
;; State
;; ============================================================================

;; The whole policy in ONE atom, so a reconfiguration is a single atomic write and
;; `allowed?` reads one consistent snapshot (two atoms let a concurrent check see
;; the new empty user set before strict mode was on, i.e. open access).
;; :strict? — empty-allowlist policy: false = open (backwards compat), true = fail-closed.
(defonce ^:private policy (atom {:users #{} :strict? false}))

(defn set-strict!
  "Set the empty-allowlist policy: true = deny-all when the allowlist is empty
   (fail-closed), false = allow-all (open, backwards compatible)."
  [v]
  (swap! policy assoc :strict? (boolean v)))

;; ============================================================================
;; Mutation
;; ============================================================================

(defn- valid-user?
  "A valid allowlist entry is a numeric Telegram id or an \"@username\" string."
  [user]
  (or (integer? user)
      (and (string? user) (re-matches #"@\S+" user))))

(defn- validate! [user]
  (when-not (valid-user? user)
    (throw (ex-info "Invalid allowlist entry — expected a numeric id or \"@username\""
                    {:user user})))
  user)

(defn set-users!
  "Replace the entire allowed set. Pass empty collection for open access.
   Validates every entry (numeric id or \"@username\")."
  [users]
  (let [s (set (map validate! users))]
    (swap! policy assoc :users s)))

(defn add-user!
  "Add a user ID (numeric) or \"@username\" to the allowlist."
  [user]
  (validate! user)
  (swap! policy update :users conj user))

(defn remove-user!
  "Remove a user from the allowlist."
  [user]
  (swap! policy update :users disj user))

(defn list-users
  "Return the current allowed set."
  []
  (:users @policy))

;; ============================================================================
;; Configuration
;; ============================================================================

(defn- config-entries
  "Allowlist entries for one config `:allowed-users` item: a user map
   `{:id 123 :username \"alice\"}` yields its id and \"@alice\"; a number or an
   \"@username\" string stands for itself."
  [u]
  (cond
    (map? u) (remove nil? [(:id u)
                           (when-let [n (:username u)]
                             (if (str/starts-with? (str n) "@") (str n) (str "@" n)))])
    :else    [u]))

(defn- open-policy? [{:keys [users strict?]}]
  (and (empty? users) (not strict?)))

(defn open?
  "True when the current policy admits every sender: no users and not strict."
  []
  (open-policy? @policy))

(defn configure!
  "Install the whole access policy from config, replacing any previous state:
   `:users` (config `:allowed-users`, user maps, ids or \"@username\" strings;
   nil means none) and `:strict?` (config `:strict-allowlist?`; nil means false).
   When `:telegram?` is true and the result admits everyone, logs a warning.
   Returns `{:users #{…} :strict? bool :open? bool}`."
  [{:keys [users telegram?] strict :strict?}]
  (let [new-policy {:users   (set (map validate! (mapcat config-entries users)))
                    :strict? (boolean strict)}]
    (reset! policy new-policy)
    (when (and telegram? (open-policy? new-policy))
      (tel/log! {:level :warn :id ::open-telegram}
                (str "SECURITY: Telegram is enabled with an empty :allowed-users and "
                     ":strict-allowlist? off — ANY Telegram user who finds the bot can use "
                     "it and spend agent budget. Add :allowed-users or set "
                     ":strict-allowlist? true.")))
    (assoc new-policy :open? (open-policy? new-policy))))

;; ============================================================================
;; Check
;; ============================================================================

(defn allowed?
  "Check if a Telegram user is permitted.

   Returns true if:
   - user's numeric :id is in the set, or
   - user's \"@username\" is in the set, or
   - the allowed set is empty AND strict mode is off (open access, backwards
     compatible — logs a warning so the operator knows access is open).

   When the set is empty and strict mode is on, returns false (fail-closed).

   Args:
     user-info - Telegram :from map with :id and :username keys"
  [user-info]
  (let [{s :users strict :strict?} @policy]
    (cond
      (contains? s (:id user-info)) true
      (contains? s (str "@" (:username user-info))) true
      (seq s) false
      strict false
      :else (do (tel/log! {:level :warn :id ::open-allowlist}
                          "Allowlist is empty — permitting all users (open access). Set :strict-allowlist? or add users to lock down.")
                true))))
