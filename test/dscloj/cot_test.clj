(ns dscloj.cot-test
  "Tests for chain-of-thought. The LLM is stubbed via router/completion, so
  these tests make no network requests."
  (:require [clojure.test :refer [deftest is testing]]
            [dscloj.core :as dscloj]
            [litellm.router :as router]))

(def qa-module
  {:inputs [{:name :question :spec :string :description "The question"}]
   :outputs [{:name :answer :spec :string :description "The answer"}]
   :instructions "Answer the question."})

(defn- response-with [content]
  {:choices [{:message {:role "assistant" :content content}}]})

(deftest with-reasoning-test
  (testing "reasoning is prepended as the first output field"
    (let [cm (dscloj/with-reasoning qa-module)]
      (is (= [:reasoning :answer] (map :name (:outputs cm))))
      (testing "inputs are untouched"
        (is (= (:inputs qa-module) (:inputs cm)))))))

(deftest chain-of-thought-returns-reasoning-and-output-test
  (with-redefs [router/completion
                (fn [_pc _req]
                  (response-with "[[ ## reasoning ## ]]\n2 plus 2 is 4\n\n[[ ## answer ## ]]\n4"))]
    (let [result (dscloj/chain-of-thought :stub qa-module {:question "What is 2+2?"})]
      (is (= "4" (:answer result)))
      (is (= "2 plus 2 is 4" (:reasoning result))))))

(deftest chain-of-thought-prompt-requests-reasoning-test
  (testing "the rendered prompt asks for the reasoning field"
    (let [captured (atom nil)]
      (with-redefs [router/completion
                    (fn [_pc req]
                      (reset! captured req)
                      (response-with "[[ ## reasoning ## ]]\nr\n\n[[ ## answer ## ]]\n4"))]
        (dscloj/chain-of-thought :stub qa-module {:question "What is 2+2?"})
        (is (re-find #"reasoning"
                     (-> @captured :messages first :content)))))))
