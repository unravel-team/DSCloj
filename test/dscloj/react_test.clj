(ns dscloj.react-test
  "Tests for the ReAct agent loop.

  ReAct is built on `predict`: each turn the LLM emits next_thought,
  next_tool_name, and next_tool_args; the chosen tool runs and its
  observation is appended to the trajectory; the loop ends when the model
  calls `finish` (or the iteration budget is hit), after which a final
  `predict` extracts the module's declared outputs. All LLM calls are
  stubbed via `router/completion` so these tests make no network requests."
  (:require [clojure.test :refer [deftest is testing]]
            [dscloj.core :as dscloj]
            [litellm.router :as router]))

(def qa-module
  {:inputs [{:name :question :spec :string :description "The question"}]
   :outputs [{:name :answer :spec :string :description "The answer"}]
   :instructions "Answer the question using the tools."})

(defn- step-content
  "A litellm-style assistant message that selects a tool for one ReAct turn."
  [thought tool args-json]
  (str "[[ ## next_thought ## ]]\n" thought "\n\n"
       "[[ ## next_tool_name ## ]]\n" tool "\n\n"
       "[[ ## next_tool_args ## ]]\n" args-json))

(defn- response-with [content]
  {:choices [{:message {:role "assistant" :content content}}]})

(defn- scripted
  "A router/completion stub returning each content in CONTENTS in order."
  [contents]
  (let [remaining (atom contents)]
    (fn [_provider-config _request]
      (let [c (first @remaining)]
        (swap! remaining rest)
        (response-with c)))))

(defn- echo-tool [calls]
  {:name "echo"
   :description "Echo the given text back."
   :args [{:name :text :spec :string :description "Text to echo"}]
   :handler (fn [{:keys [text]}]
              (swap! calls conj text)
              (str "echoed: " text))})

(deftest react-tool-then-finish-test
  (testing "a tool turn followed by finish runs the handler and extracts output"
    (let [calls (atom [])]
      (with-redefs [router/completion
                    (scripted [(step-content "I will echo" "echo" "{\"text\": \"hi\"}")
                               (step-content "I have what I need" "finish" "{}")
                               "[[ ## answer ## ]]\nThe answer is hi"])]
        (let [result (dscloj/react :stub qa-module {:question "say hi"}
                                   [(echo-tool calls)])]
          (testing "handler received keyword-keyed args parsed from JSON"
            (is (= ["hi"] @calls)))
          (testing "final extraction produced the module output"
            (is (= "The answer is hi" (:answer result))))
          (testing "metadata reports a clean finish"
            (is (= :finished (:stopped result)))
            (is (= 2 (:iterations result))))
          (testing "the observation is recorded in the trajectory"
            (is (re-find #"echoed: hi" (:trajectory result)))))))))

(deftest react-max-iters-test
  (testing "without a finish, the loop stops at :max-iters and still extracts"
    (let [calls (atom [])]
      (with-redefs [router/completion
                    (fn [_pc _req]
                      (response-with (step-content "again" "echo" "{\"text\": \"x\"}")))]
        (let [steps (atom [])
              result (dscloj/react :stub qa-module {:question "q"}
                                   [(echo-tool calls)]
                                   {:max-iters 2
                                    :on-step #(swap! steps conj %)
                                    :validate? false})]
          (is (= :max-iters (:stopped result)))
          (is (= 2 (:iterations result)))
          (testing "the tool ran once per turn"
            (is (= ["x" "x"] @calls)))
          (testing ":on-step fired once per turn"
            (is (= 2 (count @steps)))
            (is (= ["echo" "echo"] (map :tool-name @steps)))))))))

(deftest react-unknown-tool-recovers-test
  (testing "an unknown tool name yields a corrective observation, not a crash"
    (with-redefs [router/completion
                  (scripted [(step-content "guessing" "nonexistent" "{}")
                             (step-content "ok, finishing" "finish" "{}")
                             "[[ ## answer ## ]]\nrecovered"])]
      (let [result (dscloj/react :stub qa-module {:question "q"} [(echo-tool (atom []))])]
        (is (= "recovered" (:answer result)))
        (is (= :finished (:stopped result)))
        (is (re-find #"Unknown tool: nonexistent" (:trajectory result)))))))

(deftest react-handler-error-recovers-test
  (testing "a throwing handler becomes an observation the agent can see"
    (let [boom {:name "boom"
                :description "Always throws."
                :args []
                :handler (fn [_] (throw (ex-info "kaboom" {})))}]
      (with-redefs [router/completion
                    (scripted [(step-content "try boom" "boom" "{}")
                               (step-content "give up" "finish" "{}")
                               "[[ ## answer ## ]]\nhandled"])]
        (let [result (dscloj/react :stub qa-module {:question "q"} [boom])]
          (is (= "handled" (:answer result)))
          (is (re-find #"Execution error in boom: kaboom" (:trajectory result))))))))

(deftest react-instructions-test
  (testing "step-module instructions list the tools, finish, and the protocol"
    (let [smodule (#'dscloj.core/step-module qa-module [(echo-tool (atom []))])
          instr (:instructions smodule)]
      (is (re-find #"echo: Echo the given text back" instr))
      (is (re-find #"next_thought" instr))
      (is (re-find #"Answer the question using the tools" instr)))
    (testing "step-module declares the three control output fields"
      (let [smodule (#'dscloj.core/step-module qa-module [(echo-tool (atom []))])
            out-names (set (map :name (:outputs smodule)))]
        (is (= #{:next_thought :next_tool_name :next_tool_args} out-names))))))
