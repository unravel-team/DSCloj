(ns hooks.dscloj-import-vars
  "clj-kondo hook for dscloj.core/import-vars.

  The runtime macro re-exports public vars from other namespaces into the
  facade. clj-kondo cannot see through it, so this hook rewrites a call like

    (import-vars [dscloj.predict predict] [dscloj.cot chain-of-thought])

  into

    (do (def predict dscloj.predict/predict)
        (def chain-of-thought dscloj.cot/chain-of-thought))

  which makes the re-exported vars resolvable and marks the source
  namespaces as used."
  (:require [clj-kondo.hooks-api :as api]))

(defn import-vars
  [{:keys [node]}]
  (let [specs (rest (:children node))
        def-nodes (for [spec specs
                        :let [children (:children spec)
                              ns-sym (api/sexpr (first children))]
                        sym-node (rest children)
                        :let [sym (api/sexpr sym-node)]]
                    (api/list-node
                     [(api/token-node 'def)
                      (api/token-node sym)
                      (api/token-node (symbol (name ns-sym) (name sym)))]))]
    {:node (api/list-node (list* (api/token-node 'do) def-nodes))}))
