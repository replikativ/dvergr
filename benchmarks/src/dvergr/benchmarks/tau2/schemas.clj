(ns dvergr.benchmarks.tau2.schemas
  "Curated malli types for tau2 tool results.

   Drafted with `malli.provider` from every result of the gold trajectories
   and the seeded fuzz corpus, then curated: id-keyed maps are `:map-of`
   (the draft lists every id as an optional key), payment methods a union
   by source, enums and nullable fields taken from the observed data.
   `dvergr.benchmarks.tau2-schemas-test` validates every corpus result
   against these, so a curation mistake fails the build.

   Keys are strings because tool results are JSON parsed by `tau2/parse`.")

(def ^:private address
  [:map ["address1" :string] ["address2" :string] ["city" :string]
   ["country" :string] ["state" :string] ["zip" :string]])

(def retail-registry
  "Named types of the retail domain."
  {:tau2.retail/address address
   :tau2.retail/options [:map-of {:description "option name -> value"} :string :string]
   :tau2.retail/variant [:map ["item_id" :string] ["options" :tau2.retail/options]
                         ["available" :boolean] ["price" number?]]
   :tau2.retail/product [:map ["name" :string] ["product_id" :string]
                         ["variants" [:map-of {:description "item_id -> variant"} :string :tau2.retail/variant]]]
   :tau2.retail/product-types [:map-of {:description "product name -> product_id"} :string :string]
   :tau2.retail/payment-method
   [:or
    [:map ["source" [:= "gift_card"]] ["id" :string] ["balance" number?]]
    [:map ["source" [:= "credit_card"]] ["id" :string] ["brand" :string] ["last_four" :string]]
    [:map ["source" [:= "paypal"]] ["id" :string]]]
   :tau2.retail/user [:map ["user_id" :string]
                      ["name" [:map ["first_name" :string] ["last_name" :string]]]
                      ["address" :tau2.retail/address] ["email" :string]
                      ["payment_methods" [:map-of {:description "payment method id -> method"} :string :tau2.retail/payment-method]]
                      ["orders" [:vector {:description "order ids"} :string]]]
   :tau2.retail/order-item [:map ["name" :string] ["product_id" :string] ["item_id" :string]
                            ["price" number?] ["options" :tau2.retail/options]]
   :tau2.retail/order
   [:map ["order_id" :string] ["user_id" :string] ["address" :tau2.retail/address]
    ["items" [:vector :tau2.retail/order-item]]
    ["status" [:enum "pending" "pending (item modified)" "processed" "delivered"
               "cancelled" "return requested" "exchange requested"]]
    ["fulfillments" [:vector [:map ["tracking_id" [:vector :string]] ["item_ids" [:vector :string]]]]]
    ["payment_history" [:vector [:map ["transaction_type" [:enum "payment" "refund"]]
                                 ["amount" number?] ["payment_method_id" :string]]]]
    ["cancel_reason" [:maybe [:enum "no longer needed" "ordered by mistake"]]]
    ["exchange_items" [:maybe [:vector :string]]]
    ["exchange_new_items" [:maybe [:vector :string]]]
    ["exchange_payment_method_id" [:maybe :string]]
    ["exchange_price_difference" [:maybe number?]]
    ["return_items" [:maybe [:vector :string]]]
    ["return_payment_method_id" [:maybe :string]]]})

(def ^:private retail-returns
  (merge (zipmap ["get_order_details" "cancel_pending_order" "return_delivered_order_items"
                  "exchange_delivered_order_items" "modify_pending_order_items"
                  "modify_pending_order_address" "modify_pending_order_payment"]
                 (repeat :tau2.retail/order))
         {"get_user_details" :tau2.retail/user
          "modify_user_address" :tau2.retail/user
          "get_product_details" :tau2.retail/product
          "get_item_details" :tau2.retail/variant
          "list_all_product_types" :tau2.retail/product-types}))

(def ^:private domains
  {"retail" {:registry retail-registry :returns retail-returns}})

(defn registry
  "Named types of `domain-name`, or nil when the domain has none yet."
  [domain-name]
  (get-in domains [domain-name :registry]))

(defn returns
  "The type a tool's JSON result has once parsed, or nil (plain text, or no
   schema for the domain yet)."
  [domain-name tool-name]
  (get-in domains [domain-name :returns tool-name]))
