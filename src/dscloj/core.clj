(ns dscloj.core
  "Aggregating facade over DSCloj's modules, analogous to DSPy's top-level
  `dspy` package. Requiring `dscloj.core` gives you the whole public API:

  - prediction:  `dscloj.predict` (predict, predict-stream, provider mgmt,
                 module/spec helpers)
  - reasoning:   `dscloj.cot` (chain-of-thought)
  - agents:      `dscloj.react` (react)

  You may also require the individual namespaces directly."
  (:require [dscloj.predict :as predict]
            [dscloj.cot :as cot]
            [dscloj.react :as react]))

(defmacro ^:private import-vars
  "Define vars in this namespace that alias public vars from other
  namespaces, copying their :doc and :arglists so docs and tooling work."
  [& specs]
  (cons 'do
        (for [[ns-sym & syms] specs
              sym syms
              :let [target (symbol (name ns-sym) (name sym))]]
          `(do
             (def ~sym ~target)
             (alter-meta! (var ~sym) merge
                          (select-keys (meta (var ~target)) [:doc :arglists]))))))

(import-vars
 [dscloj.predict
  register-provider! quick-setup! list-providers
  composite-spec? spec->type-str validate-field validate-inputs validate-outputs
  module->prompt parse-output
  predict
  parse-streaming-json-array parse-streaming-output predict-stream]
 [dscloj.cot
  chain-of-thought with-reasoning]
 [dscloj.react
  react])
