(ns dscloj.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dscloj.core :as dscloj]))

(def openrouter-api-key
  "OpenRouter API key for live integration tests."
  (System/getenv "OPENROUTER_API_KEY"))

(def openrouter-model
  "Cheap, deterministic default model for live integration tests.
   CI may override this with OPENROUTER_MODEL."
  (or (System/getenv "OPENROUTER_MODEL")
      "openai/gpt-4o-mini"))

(defn- api-key-available? []
  (boolean (seq openrouter-api-key)))

(defn- ci? []
  (= "true" (System/getenv "CI")))

(defn setup-test-provider
  "Register OpenRouter provider when OPENROUTER_API_KEY is available.

  These tests intentionally keep one live provider smoke. Unit tests already cover
  DSCloj parsing/validation in detail; this verifies the generated prompt still
  works against a real chat-completions model without making DSCloj responsible
  for testing every litellm provider."
  [f]
  (cond
    (api-key-available?)
    (do
      (dscloj/register-provider! :test-openrouter
                                 {:provider :openrouter
                                  :model openrouter-model
                                  :config {:api-key openrouter-api-key}})
      (f))

    (ci?)
    (throw (ex-info "OPENROUTER_API_KEY is required in CI for integration tests" {}))

    :else
    (println "Skipping integration tests: OPENROUTER_API_KEY not set")))

(use-fixtures :once setup-test-provider)

(deftest ^:integration live-structured-output-integration-test
  (testing "Live OpenRouter model can follow DSCloj structured output format"
    (when (api-key-available?)
      (let [module {:inputs [{:name :question
                              :spec :string
                              :description "A deterministic arithmetic question"}]
                    :outputs [{:name :answer
                               :spec :string
                               :description "The answer as text"}
                              {:name :numeric_answer
                               :spec :int
                               :description "The answer as an integer"}
                              {:name :is_correct
                               :spec :boolean
                               :description "Whether the numeric answer is correct"}]
                    :instructions (str "Answer the arithmetic question. "
                                       "Use 4 for numeric_answer. "
                                       "Use True for is_correct.")}
            result (dscloj/predict :test-openrouter
                                   module
                                   {:question "What is 2+2?"}
                                   {:temperature 0.0
                                    :max-tokens 80})]
        (is (map? result))
        (is (contains? result :answer))
        (is (string? (:answer result)))
        (is (= 4 (:numeric_answer result)))
        (is (true? (:is_correct result)))))))
