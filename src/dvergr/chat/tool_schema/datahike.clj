(ns dvergr.chat.tool-schema.datahike
  "Atomic database installation for the otherwise pure tool-schema vocabulary."
  (:require [datahike.api :as d]))

(def ^:private durable-schema-keys
  [:db/valueType :db/cardinality :db/isComponent :db/unique])

(defn- durable-attr-shape [attr]
  (select-keys attr durable-schema-keys))

(defn- install-compatible-attrs
  "Datahike transaction function for an atomic schema claim.

   Writer serialization makes the current DB schema authoritative even when
   different processes race the first use of one late-bound tool. Existing
   attributes must have the same durable shape; only absent attributes are
   returned for installation. In particular, do not rely on Datahike rejecting
   a redeclaration: under `:schema-flexibility :write` it may legally rewrite
   an unused attribute before an older serialized message reaches the writer.
   An attribute proposed twice in one claim (two tools installed together)
   must have one shape too; it is installed once."
  [db attrs]
  (let [installed (d/schema db)]
    (:missing
     (reduce
      (fn [{:keys [claimed] :as acc} attr]
        (let [ident (:db/ident attr)
              proposed-shape (durable-attr-shape attr)]
          (if-let [current (or (get installed ident) (get claimed ident))]
            (let [current-shape (durable-attr-shape current)]
              (when-not (= current-shape proposed-shape)
                (throw
                 (ex-info
                  "Incompatible durable tool schema is already installed"
                  {:type :dvergr.chat.tool-schema/incompatible-installed-tool-schema
                   :attribute ident
                   :installed current-shape
                   :proposed proposed-shape})))
              acc)
            (-> acc
                (update :missing conj attr)
                (assoc-in [:claimed ident] attr)))))
      {:missing [] :claimed {}}
      attrs))))

(defn install-compatible-schema! [conn schema]
  (d/transact conn [[:db.fn/call install-compatible-attrs schema]]))
