(ns dsclj.integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [dsclj.core :as dsclj]))

(def api-key-available?
  "Check if OPENAI_API_KEY is set in environment"
  (not (nil? (System/getenv "OPENAI_API_KEY"))))

(defn skip-if-no-api-key
  "Fixture to skip tests if API key is not available"
  [f]
  (if api-key-available?
    (f)
    (println "Skipping integration tests: OPENAI_API_KEY not set")))

(use-fixtures :once skip-if-no-api-key)

(deftest ^:integration basic-qa-integration-test
  (testing "Basic Q&A with OpenAI API"
    (when api-key-available?
      (let [qa-module {:inputs [{:name :question
                                 :type "str"
                                 :description "A question to answer"}]
                       :outputs [{:name :answer
                                  :type "str"
                                  :description "The answer to the question"}]
                       :instructions "Answer the question accurately and concisely."}
            result (dsclj/predict qa-module 
                                  {:question "What is 2+2? Reply with just the number."}
                                  {:model "gpt-3.5-turbo"
                                   :api-key (System/getenv "OPENAI_API_KEY")
                                   :temperature 0})]
        (is (map? result))
        (is (contains? result :answer))
        (is (string? (:answer result)))
        (is (re-find #"4" (:answer result)))))))

(deftest ^:integration boolean-output-integration-test
  (testing "Boolean output parsing with OpenAI API"
    (when api-key-available?
      (let [validator-module {:inputs [{:name :statement
                                        :type "str"
                                        :description "A statement to verify"}]
                              :outputs [{:name :is_true
                                         :type "bool"
                                         :description "Whether the statement is true or false"}]
                              :instructions "Determine if the statement is true or false."}
            result (dsclj/predict validator-module
                                  {:statement "The Earth orbits around the Sun."}
                                  {:model "gpt-3.5-turbo"
                                   :api-key (System/getenv "OPENAI_API_KEY")
                                   :temperature 0})]
        (is (map? result))
        (is (contains? result :is_true))
        (is (boolean? (:is_true result)))
        (is (true? (:is_true result)))))))

(deftest ^:integration multiple-outputs-integration-test
  (testing "Multiple outputs with different types"
    (when api-key-available?
      (let [analyzer-module {:inputs [{:name :text
                                       :type "str"
                                       :description "Text to analyze"}]
                             :outputs [{:name :word_count
                                        :type "int"
                                        :description "Number of words"}
                                       {:name :has_punctuation
                                        :type "bool"
                                        :description "Whether text has punctuation"}
                                       {:name :summary
                                        :type "str"
                                        :description "Brief summary"}]
                             :instructions "Analyze the text and provide word count, check for punctuation, and give a brief summary."}
            result (dsclj/predict analyzer-module
                                  {:text "Hello, world! This is a test."}
                                  {:model "gpt-3.5-turbo"
                                   :api-key (System/getenv "OPENAI_API_KEY")                                                   :temperature 0})]
        (is (map? result))
        (is (contains? result :word_count))
        (is (contains? result :has_punctuation))
        (is (contains? result :summary))
        (is (number? (:word_count result)))
        (is (boolean? (:has_punctuation result)))
        (is (string? (:summary result)))
        (is (true? (:has_punctuation result)))))))
