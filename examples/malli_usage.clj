(ns examples.malli-usage
  (:require [dscloj.core :as dscloj]
            [malli.core :as m]))

;; =============================================================================
;; EXAMPLE 1: Simple Q&A Module with Malli Schemas
;; =============================================================================

(def qa-module-malli
  {:input-schema [:map
                  [:question [:string {:description "The question to answer"}]]]
   :output-schema [:map
                   [:answer [:string {:description "The answer to the question"}]]]
   :instructions "Provide concise and accurate answers."})

(comment
  ;; This module works identically to the traditional field-based approach
  ;; but with automatic validation
  (def result (dscloj/predict qa-module-malli 
                              {:question "What is the capital of France?"}
                              {:model "gpt-4"
                               :api-key (System/getenv "OPENAI_API_KEY")}))
  
  (:answer result)
  ;; => "Paris"
  
  ;; Invalid input will throw an exception
  (try
    (dscloj/predict qa-module-malli 
                   {:question 123}  ; Should be a string
                   {:model "gpt-4"
                    :api-key (System/getenv "OPENAI_API_KEY")})
    (catch Exception e
      (println "Validation error:" (.getMessage e))))
  )


;; =============================================================================
;; EXAMPLE 2: Multiple Types with Validation
;; =============================================================================

(def analysis-module
  {:input-schema [:map
                  [:text [:string {:description "Text to analyze"}]]
                  [:min_length [:int {:description "Minimum length requirement"}]]]
   :output-schema [:map
                   [:summary [:string {:description "Summary of the text"}]]
                   [:is_valid [:boolean {:description "Whether text meets requirements"}]]
                   [:confidence [:double {:description "Confidence score 0.0-1.0"}]]]
   :instructions "Analyze the text and check if it meets the minimum length requirement."})

(comment
  (def analysis-result 
    (dscloj/predict analysis-module
                   {:text "This is a sample text for analysis."
                    :min_length 10}
                   {:model "gpt-4"
                    :api-key (System/getenv "OPENAI_API_KEY")}))
  
  ;; All outputs are type-validated
  (:summary analysis-result)      ;; => string
  (:is_valid analysis-result)     ;; => boolean
  (:confidence analysis-result)   ;; => double (0.0-1.0)
  )


;; =============================================================================
;; EXAMPLE 3: Backward Compatibility - Mixing Schemas and Field Vectors
;; =============================================================================

;; Field vectors take precedence over schemas if both are provided
(def mixed-module
  {:inputs [{:name :question
             :type "str"
             :description "The question"}]
   :input-schema [:map
                  [:schema_field [:string {:description "This will be ignored"}]]]
   :outputs [{:name :answer
              :type "str"
              :description "The answer"}]
   :instructions "Answer the question."})

(comment
  ;; This will use the :inputs/:outputs field vectors
  (dscloj/predict mixed-module
                 {:question "What is 2+2?"}
                 {:model "gpt-4"
                  :api-key (System/getenv "OPENAI_API_KEY")})
  )


;; =============================================================================
;; EXAMPLE 4: Disabling Validation
;; =============================================================================

;; Sometimes you might want to skip validation (e.g., for debugging)
(def strict-module
  {:input-schema [:map
                  [:number [:int {:min 1 :max 100}]]]
   :output-schema [:map
                   [:result [:string]]]
   :instructions "Process the number."})

(comment
  ;; With validation (default)
  (dscloj/predict strict-module
                 {:number 50}
                 {:model "gpt-4"
                  :validate? true  ; This is the default
                  :api-key (System/getenv "OPENAI_API_KEY")})
  
  ;; Without validation
  (dscloj/predict strict-module
                 {:number 50}
                 {:model "gpt-4"
                  :validate? false  ; Skip Malli validation
                  :api-key (System/getenv "OPENAI_API_KEY")})
  )


;; =============================================================================
;; EXAMPLE 5: Complex Schema with Nested Descriptions
;; =============================================================================

(def financial-module-malli
  {:input-schema [:map
                  [:query [:string {:description "User's financial query"}]]
                  [:amount [:double {:description "Amount in dollars"}]]
                  [:years [:int {:description "Investment duration in years"}]]]
   :output-schema [:map
                   [:calculation [:string {:description "Detailed calculation"}]]
                   [:recommendation [:string {:description "Investment recommendation"}]]
                   [:is_safe [:boolean {:description "Whether this is a safe investment"}]]
                   [:expected_return [:double {:description "Expected return percentage"}]]]
   :instructions (str "Analyze the financial scenario.\\n\\n"
                      "STRICT RULES:\\n"
                      "1. Only use provided data\\n"
                      "2. Be conservative in recommendations\\n"
                      "3. Always assess risk")})

(comment
  (def fin-result 
    (dscloj/predict financial-module-malli
                   {:query "Should I invest in index funds?"
                    :amount 10000.0
                    :years 10}
                   {:model "gpt-4"
                    :api-key (System/getenv "OPENAI_API_KEY")}))
  
  (:calculation fin-result)
  (:recommendation fin-result)
  (:is_safe fin-result)         ;; => boolean
  (:expected_return fin-result) ;; => double
  )


;; =============================================================================
;; EXAMPLE 6: Inspecting Schema-based Modules
;; =============================================================================

(comment
  ;; Malli schemas are automatically converted to field vectors for prompting
  (def normalized (dscloj/normalize-module qa-module-malli))
  
  ;; Check the converted fields
  (:inputs normalized)
  ;; => [{:name :question, :type "str", :description "The question to answer"}]
  
  (:outputs normalized)
  ;; => [{:name :answer, :type "str", :description "The answer to the question"}]
  
  ;; View the generated prompt
  (println (dscloj/module->prompt qa-module-malli))
  )


;; =============================================================================
;; EXAMPLE 7: Working with Validation Errors
;; =============================================================================

(def typed-module
  {:input-schema [:map
                  [:age [:int {:min 0 :max 150}]]
                  [:name [:string {:min-length 1}]]]
   :output-schema [:map
                   [:message [:string]]]})

(comment
  ;; This will throw a validation error with details
  (try
    (dscloj/predict typed-module
                   {:age "not-a-number"  ; Invalid: should be int
                    :name "John"}
                   {:model "gpt-4"
                    :api-key (System/getenv "OPENAI_API_KEY")})
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (println "Error:" (.getMessage e))
        (println "Details:" (:errors data))
        (println "Input:" (:input data)))))
  
  ;; Validation errors are detailed and helpful
  )


;; =============================================================================
;; EXAMPLE 8: Schema Reusability
;; =============================================================================

;; Define reusable schemas
(def QuestionInput
  [:map
   [:question [:string {:description "The question to answer"}]]
   [:context {:optional true} [:string {:description "Additional context"}]]])

(def AnswerOutput
  [:map
   [:answer [:string {:description "The answer"}]]
   [:confidence [:double {:description "Confidence score"}]]])

(def qa-with-context-module
  {:input-schema QuestionInput
   :output-schema AnswerOutput
   :instructions "Answer based on the question and context if provided."})

(comment
  (dscloj/predict qa-with-context-module
                 {:question "What is the capital?"
                  :context "We are discussing France."}
                 {:model "gpt-4"
                  :api-key (System/getenv "OPENAI_API_KEY")})
  )


;; =============================================================================
;; Benefits of Malli Schemas
;; =============================================================================

;; 1. Type Safety: Automatic validation of inputs and outputs
;; 2. Reusability: Share schema definitions across modules
;; 3. Documentation: Schemas serve as living documentation
;; 4. IDE Support: Better autocomplete and type hints
;; 5. Error Messages: Detailed validation errors help debugging
;; 6. Backward Compatible: Works alongside traditional field vectors
