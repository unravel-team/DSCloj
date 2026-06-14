(ns dscloj.cot
  "Chain-of-Thought: predict, but reason step by step first.

  Mirrors DSPy's `ChainOfThought` (dspy/predict/chain_of_thought.py): a
  `reasoning` output field is prepended to the module's outputs, so the model
  emits its rationale before the declared outputs. Built on `predict`."
  (:require [dscloj.predict :as predict]))

(def reasoning-field
  "The rationale field prepended to a module's outputs by chain-of-thought."
  {:name :reasoning
   :spec :string
   :description "Think step by step to work out your answer."})

(defn with-reasoning
  "Return MODULE with the reasoning field prepended to its outputs."
  [module]
  (update module :outputs #(into [reasoning-field] %)))

(defn chain-of-thought
  "Like `dscloj.predict/predict`, but asks the model to reason step by step
  before producing the declared outputs. A `:reasoning` string field is
  prepended to MODULE's outputs (and so to the prompt), and the returned map
  includes `:reasoning` alongside the module's own outputs.

  Parameters and options are identical to `predict`."
  [provider-config module input-map & [options]]
  (predict/predict provider-config (with-reasoning module) input-map options))
