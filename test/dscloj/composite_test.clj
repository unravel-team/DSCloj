(ns dscloj.composite-test
  "Tests for composite (array/nested map) output support.

  Composite Malli specs (:vector, :map, :map-of, :sequential, :set, :tuple,
  and :maybe wrapping any of those) are rendered as `json` typed fields in
  prompts, parsed from JSON inside the [[ ## field ## ]] block, and validated
  with the same Malli spec. Scalar fields keep the existing behavior."
  (:require [clojure.test :refer [deftest is testing]]
            [dscloj.core :as dscloj]
            [litellm.router :as router]))

(def action-items-spec
  [:vector
   [:map
    [:description :string]
    [:owner [:maybe :string]]
    [:priority [:int {:min 0 :max 3}]]]])

(def extraction-module
  {:inputs [{:name :transcript
             :spec :string
             :description "Meeting transcript window"}]
   :outputs [{:name :action_items
              :spec action-items-spec
              :description "Action items mentioned in the transcript"}]
   :instructions "Extract action items from the transcript."})

(defn- response-with
  "Build a litellm-style completion response with the given content."
  [content]
  {:choices [{:message {:role "assistant" :content content}}]})

(def valid-items-json
  "[{\"description\": \"Send proposal\", \"owner\": \"asha\", \"priority\": 1}]")

(def invalid-items-json
  ;; priority is a string -> fails [:int {:min 0 :max 3}]
  "[{\"description\": \"Send proposal\", \"owner\": \"asha\", \"priority\": \"high\"}]")

;; =============================================================================
;; Spec classification
;; =============================================================================

(deftest composite-spec?-test
  (testing "Composite specs are detected"
    (is (dscloj/composite-spec? [:vector :string]))
    (is (dscloj/composite-spec? [:vector [:map [:a :string]]]))
    (is (dscloj/composite-spec? [:map [:a :string]]))
    (is (dscloj/composite-spec? [:map-of :string :int]))
    (is (dscloj/composite-spec? [:sequential :int]))
    (is (dscloj/composite-spec? [:set :string]))
    (is (dscloj/composite-spec? [:tuple :string :int]))
    (is (dscloj/composite-spec? [:maybe [:vector :string]])))
  (testing "Scalar specs are not composite"
    (is (not (dscloj/composite-spec? :string)))
    (is (not (dscloj/composite-spec? :int)))
    (is (not (dscloj/composite-spec? :boolean)))
    (is (not (dscloj/composite-spec? [:string {:min 1}])))
    (is (not (dscloj/composite-spec? [:int {:min 0 :max 3}])))
    (is (not (dscloj/composite-spec? [:maybe :string])))
    (is (not (dscloj/composite-spec? 'string?)))))

(deftest spec->type-str-json-test
  (testing "Composite specs map to json type"
    (is (= "json" (dscloj/spec->type-str [:vector :string])))
    (is (= "json" (dscloj/spec->type-str action-items-spec)))
    (is (= "json" (dscloj/spec->type-str [:map [:a :string]])))
    (is (= "json" (dscloj/spec->type-str [:maybe [:vector :string]]))))
  (testing "Scalar specs keep existing mapping"
    (is (= "str" (dscloj/spec->type-str :string)))
    (is (= "str" (dscloj/spec->type-str [:string {:min 1}])))
    (is (= "float" (dscloj/spec->type-str [:double {:min 0.0 :max 1.0}])))))

;; =============================================================================
;; Prompt generation
;; =============================================================================

(deftest module->prompt-json-test
  (let [prompt (dscloj/module->prompt extraction-module)]
    (testing "json fields are labeled with the json type"
      (is (re-find #"`action_items` \(json\)" prompt)))
    (testing "json fields instruct the LLM to produce valid JSON"
      (is (re-find #"(?i)valid JSON" prompt)))
    (testing "json fields embed a JSON Schema derived from the Malli spec"
      ;; [:vector ...] renders as a JSON Schema array with object items
      (is (re-find #"array" prompt))
      (is (re-find #"description" prompt))
      (is (re-find #"priority" prompt))))
  (testing "scalar-only modules are unchanged (no JSON instructions)"
    (let [module {:inputs [{:name :q :spec :string :description "Q"}]
                  :outputs [{:name :a :spec :string :description "A"}]}
          prompt (dscloj/module->prompt module)]
      (is (not (re-find #"(?i)valid JSON" prompt))))))

;; =============================================================================
;; Parsing
;; =============================================================================

(deftest parse-output-json-test
  (testing "JSON array of objects parses to vector of keyword-keyed maps"
    (let [response (str "[[ ## action_items ## ]]\n" valid-items-json)
          result (dscloj/parse-output response extraction-module)]
      (is (= [{:description "Send proposal" :owner "asha" :priority 1}]
             (:action_items result)))))

  (testing "JSON wrapped in markdown code fences parses"
    (let [response (str "[[ ## action_items ## ]]\n"
                        "```json\n" valid-items-json "\n```")
          result (dscloj/parse-output response extraction-module)]
      (is (= [{:description "Send proposal" :owner "asha" :priority 1}]
             (:action_items result)))))

  (testing "single JSON object parses to a keyword-keyed map"
    (let [module {:outputs [{:name :metadata
                             :spec [:map [:title :string] [:kind :string]]}]}
          response "[[ ## metadata ## ]]\n{\"title\": \"Sync\", \"kind\": \"sales\"}"
          result (dscloj/parse-output response module)]
      (is (= {:title "Sync" :kind "sales"} (:metadata result)))))

  (testing "empty JSON array parses to empty vector"
    (let [response "[[ ## action_items ## ]]\n[]"
          result (dscloj/parse-output response extraction-module)]
      (is (= [] (:action_items result)))))

  (testing "invalid JSON falls back to the raw string (like int/float fallback)"
    (let [response "[[ ## action_items ## ]]\nnot json at all"
          result (dscloj/parse-output response extraction-module)]
      (is (= "not json at all" (:action_items result)))))

  (testing "scalar fields alongside json fields keep scalar parsing"
    (let [module {:outputs [{:name :items :spec [:vector :string]}
                            {:name :confidence :spec :double}]}
          response (str "[[ ## items ## ]]\n[\"a\", \"b\"]\n"
                        "[[ ## confidence ## ]]\n0.9")
          result (dscloj/parse-output response module)]
      (is (= ["a" "b"] (:items result)))
      (is (= 0.9 (:confidence result))))))

;; =============================================================================
;; predict end-to-end (LLM stubbed at the router boundary)
;; =============================================================================

(deftest predict-composite-test
  (testing "predict returns validated composite output"
    (with-redefs [router/completion
                  (fn [_provider-config _request]
                    (response-with (str "[[ ## action_items ## ]]\n" valid-items-json)))]
      (let [result (dscloj/predict :stub extraction-module
                                   {:transcript "Asha will send the proposal."})]
        (is (= [{:description "Send proposal" :owner "asha" :priority 1}]
               (:action_items result))))))

  (testing "predict throws on composite output failing Malli validation"
    (with-redefs [router/completion
                  (fn [_provider-config _request]
                    (response-with (str "[[ ## action_items ## ]]\n" invalid-items-json)))]
      (is (thrown? Exception
                   (dscloj/predict :stub extraction-module
                                   {:transcript "Asha will send the proposal."}))))))

;; =============================================================================
;; Retries on validation/parse failure
;; =============================================================================

(deftest predict-nil-content-test
  (testing "predict tolerates a response message with nil content"
    (with-redefs [router/completion
                  (fn [_provider-config _request]
                    {:choices [{:message {:role "assistant", :content nil}}]})]
      (is (= {:answer nil}
             (dscloj/predict :stub
                             {:inputs [], :outputs [{:name :answer, :spec :string}]}
                             {}
                             {:validate? false}))))))

(deftest predict-retries-test
  (testing ":retries re-calls the LLM after a validation failure and succeeds"
    (let [calls (atom 0)]
      (with-redefs [router/completion
                    (fn [_provider-config _request]
                      (let [n (swap! calls inc)]
                        (response-with
                         (str "[[ ## action_items ## ]]\n"
                              (if (= n 1) invalid-items-json valid-items-json)))))]
        (let [result (dscloj/predict :stub extraction-module
                                     {:transcript "..."}
                                     {:retries 1})]
          (is (= 2 @calls))
          (is (= [{:description "Send proposal" :owner "asha" :priority 1}]
                 (:action_items result)))))))

  (testing ":retries exhausted -> throws, with one call per attempt"
    (let [calls (atom 0)]
      (with-redefs [router/completion
                    (fn [_provider-config _request]
                      (swap! calls inc)
                      (response-with (str "[[ ## action_items ## ]]\n" invalid-items-json)))]
        (is (thrown? Exception
                     (dscloj/predict :stub extraction-module
                                     {:transcript "..."}
                                     {:retries 2})))
        (is (= 3 @calls)))))

  (testing "default is no retries: a single failing call throws"
    (let [calls (atom 0)]
      (with-redefs [router/completion
                    (fn [_provider-config _request]
                      (swap! calls inc)
                      (response-with (str "[[ ## action_items ## ]]\n" invalid-items-json)))]
        (is (thrown? Exception
                     (dscloj/predict :stub extraction-module {:transcript "..."})))
        (is (= 1 @calls)))))

  (testing ":retries with :validate? false does not retry or throw"
    (let [calls (atom 0)]
      (with-redefs [router/completion
                    (fn [_provider-config _request]
                      (swap! calls inc)
                      (response-with (str "[[ ## action_items ## ]]\n" invalid-items-json)))]
        (let [result (dscloj/predict :stub extraction-module
                                     {:transcript "..."}
                                     {:retries 2 :validate? false})]
          (is (= 1 @calls))
          ;; unvalidated but parsed JSON comes back as data
          (is (vector? (:action_items result))))))))
