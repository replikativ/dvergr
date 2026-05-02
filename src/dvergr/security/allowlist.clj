(ns dvergr.security.allowlist
  "Telegram user allowlist for daemon access control.

   Prevents unauthorized users from consuming agent budget.
   Supports both numeric Telegram user IDs and @username strings.

   When the allowed set is empty, all users are permitted (backwards compat).

   The allowlist is stored in a global atom for runtime mutability:
     (add-user! \"@christian_w\")
     (remove-user! \"@spammer\")
     (list-users)")

;; ============================================================================
;; State
;; ============================================================================

(defonce ^:private allowed-users (atom #{}))

;; ============================================================================
;; Mutation
;; ============================================================================

(defn set-users!
  "Replace the entire allowed set. Pass empty collection for open access."
  [users]
  (reset! allowed-users (set users)))

(defn add-user!
  "Add a user ID (numeric) or \"@username\" to the allowlist."
  [user]
  (swap! allowed-users conj user))

(defn remove-user!
  "Remove a user from the allowlist."
  [user]
  (swap! allowed-users disj user))

(defn list-users
  "Return the current allowed set."
  []
  @allowed-users)

;; ============================================================================
;; Check
;; ============================================================================

(defn allowed?
  "Check if a Telegram user is permitted.

   Returns true if:
   - allowed set is empty (open access, backwards compatible)
   - user's numeric :id is in the set
   - user's \"@username\" is in the set

   Args:
     user-info - Telegram :from map with :id and :username keys"
  [user-info]
  (let [s @allowed-users]
    (or (empty? s)
        (contains? s (:id user-info))
        (contains? s (str "@" (:username user-info))))))
