(ns examples.basic-usage
  (:require [dsclj.core :as dsclj]))

;; =============================================================================
;; EXAMPLE 1: Simple Q&A Module
;; =============================================================================

(def qa-module
  {:inputs [{:name :question
             :type "str"
             :description "The question to answer"}]
   :outputs [{:name :answer
              :type "str"
              :description "The answer to the question"}]
   :instructions "Provide concise and accurate answers."})

(comment
  ;; Call predict - it handles everything automatically:
  ;; - Generates prompt from module
  ;; - Injects input values
  ;; - Calls LLM
  ;; - Parses output
  (def result (dsclj/predict qa-module 
                              {:question "What is the capital of France?"}
                              {:model "gpt-4"
                               :api-key (System/getenv "OPENAI_API_KEY")}))
  
  (:answer result)
  ;; => "Paris"
  )


;; =============================================================================
;; EXAMPLE 2: Financial Comparison with Rules
;; =============================================================================

(def financial-comparison-module
  {:inputs [{:name :query 
             :type "str"
             :description "Current user query"}
            {:name :data
             :type "str"
             :description "Fetched financial data"}]
   :outputs [{:name :reasoning
              :type "str"
              :description "Brief explanation of the comparison"}
             {:name :summary
              :type "str"
              :description "Summary in markdown"}
             {:name :recommendation
              :type "str"
              :description "Investment recommendation"}]
   :instructions (str "Compare financial instruments based on provided data.\n\n"
                      "STRICT RULES:\n"
                      "1. ONLY use data provided in the input\n"
                      "2. Never make up financial information\n"
                      "3. Be clear about risks and limitations")})

(comment
  (def fin-result 
    (dsclj/predict financial-comparison-module
                   {:query "Compare HDFC Bank FD vs SBI FD"
                    :data "HDFC Bank FD: 7.0% interest, 5 years\nSBI FD: 6.8% interest, 5 years"}
                   {:model "gpt-4"
                    :api-key (System/getenv "OPENAI_API_KEY")}))
  
  (:reasoning fin-result)
  (:summary fin-result)
  (:recommendation fin-result)
  )


;; =============================================================================
;; EXAMPLE 3: Custom LLM Options
;; =============================================================================

(def translation-module
  {:inputs [{:name :text
             :type "str"
             :description "Text to translate"}
            {:name :target_language
             :type "str"
             :description "Target language"}]
   :outputs [{:name :translation
              :type "str"
              :description "Translated text"}]
   :instructions "Translate text accurately."})

(comment
  (def translation-result 
    (dsclj/predict translation-module
                   {:text "Hello, how are you?"
                    :target_language "Spanish"}
                   {:model "gpt-4"
                    :temperature 0.3
                    :max-tokens 100
                    :api-key (System/getenv "OPENAI_API_KEY")}))
  
  (:translation translation-result)
  ;; => "Hola, ¿cómo estás?"
  )


;; =============================================================================
;; EXAMPLE 4: Multiple Output Types (bool, float, str)
;; =============================================================================

(def qa-with-confidence-module
  {:inputs [{:name :question
             :type "str"
             :description "Question to answer"}]
   :outputs [{:name :answer
              :type "str"
              :description "The answer"}
             {:name :is_confident
              :type "bool"
              :description "Whether the answer is confident"}
             {:name :confidence_score
              :type "float"
              :description "Confidence from 0.0 to 1.0"}]
   :instructions "Answer with confidence assessment."})

(comment
  (def qa-result 
    (dsclj/predict qa-with-confidence-module
                   {:question "What is the speed of light?"}
                   {:model "gpt-4"
                    :api-key (System/getenv "OPENAI_API_KEY")}))
  
  ;; Types are automatically converted
  (:is_confident qa-result)     ;; => true (boolean)
  (:confidence_score qa-result) ;; => 0.95 (float)
  (:answer qa-result)           ;; => "The speed of light is..."
  
  (when (:is_confident qa-result)
    (if (> (:confidence_score qa-result) 0.8)
      (println "High confidence:" (:answer qa-result))
      (println "Low confidence:" (:answer qa-result))))
  )


;; =============================================================================
;; EXAMPLE 5: Inspecting Generated Prompts
;; =============================================================================

(comment
  ;; View the prompt template
  (def prompt-template (dsclj/module->prompt qa-module))
  (println prompt-template)
  
  ;; Useful for debugging what's sent to the LLM
  )
