(ns examples.basic-usage
  (:require [dscloj.core :as dscloj]
            [malli.core :as m]))

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
  (def result (dscloj/predict qa-module 
                              {:question "What is the capital of France?"}
                              {:model "gpt-4"
                               :api-key (System/getenv "OPENAI_API_KEY")}))
  
  (:answer result)
  ;; => "Paris"
  )


;; =============================================================================
;; EXAMPLE 2: Simple Q&A Module with Malli Schemas
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
    (dscloj/predict translation-module
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
    (dscloj/predict qa-with-confidence-module
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
;; EXAMPLE 5: Multiple Types with Malli Validation
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
;; EXAMPLE 6: Backward Compatibility - Mixing Schemas and Field Vectors
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
;; EXAMPLE 7: Disabling Validation
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
;; EXAMPLE 8: Inspecting Modules and Generated Prompts
;; =============================================================================

(comment
  ;; View the prompt template for traditional modules
  (def prompt-template (dscloj/module->prompt qa-module))
  (println prompt-template)
  
  ;; Malli schemas are automatically converted to field vectors for prompting
  (def normalized (dscloj/normalize-module qa-module-malli))
  
  ;; Check the converted fields
  (:inputs normalized)
  ;; => [{:name :question, :type "str", :description "The question to answer"}]
  
  (:outputs normalized)
  ;; => [{:name :answer, :type "str", :description "The answer to the question"}]
  
  ;; View the generated prompt
  (println (dscloj/module->prompt qa-module-malli))
  
  ;; Useful for debugging what's sent to the LLM
  )


;; =============================================================================
;; EXAMPLE 9: Working with Validation Errors
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
;; EXAMPLE 10: Schema Reusability
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
