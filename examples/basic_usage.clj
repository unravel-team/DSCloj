(ns examples.basic-usage
  (:require [dscloj.core :as dscloj]
            [malli.core :as m]))

;; =============================================================================
;; SETUP: Register Provider Configurations
;; =============================================================================

(comment
  ;; Option 1: Quick setup from environment variables
  ;; This will automatically register :openai, :anthropic, :gemini, etc.
  ;; based on available API keys in environment
  (dscloj/quick-setup!)
  
  ;; Option 2: Register custom providers
  (dscloj/register-provider! :gpt4 
    {:provider :openai 
     :model "gpt-4" 
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  (dscloj/register-provider! :gpt4-mini 
    {:provider :openai 
     :model "gpt-4o-mini" 
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  (dscloj/register-provider! :claude 
    {:provider :anthropic 
     :model "claude-3-5-sonnet-20241022" 
     :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}})
  
  ;; List all registered providers
  (dscloj/list-providers)
  )


;; =============================================================================
;; EXAMPLE 1: Simple Q&A Module with Router API
;; =============================================================================

(def qa-module
  {:inputs [{:name :question
             :spec :string
             :description "The question to answer"}]
   :outputs [{:name :answer
              :spec :string
              :description "The answer to the question"}]
   :instructions "Provide concise and accurate answers."})

(comment
  ;; Setup provider first
  (dscloj/register-provider! :gpt4 
    {:provider :openai 
     :model "gpt-4" 
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  ;; Using registered provider
  (def result (dscloj/predict qa-module 
                              {:question "What is the capital of France?"}
                              :gpt4))  ; provider-config as third argument
  
  (:answer result)
  ;; => "Paris"
  
  ;; Ad-hoc provider (no registration needed)
  (def result2 (dscloj/predict qa-module 
                               {:question "What is the capital of France?"}
                               {:provider :openai 
                                :model "gpt-4" 
                                :config {:api-key (System/getenv "OPENAI_API_KEY")}}))
  
  ;; Using different provider (Anthropic)
  (dscloj/register-provider! :claude 
    {:provider :anthropic 
     :model "claude-3-5-sonnet-20241022" 
     :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}})
  
  (def result3 (dscloj/predict qa-module 
                               {:question "What is the capital of France?"}
                               :claude))
  
  ;; Invalid input will throw a validation exception
  (try
    (dscloj/predict qa-module 
                   {:question 123}  ; Should be a string
                   :gpt4)
    (catch Exception e
      (println "Validation error:" (.getMessage e))))
  )


;; =============================================================================
;; EXAMPLE 2: Custom LLM Options
;; =============================================================================

(def translation-module
  {:inputs [{:name :text
             :spec :string
             :description "Text to translate"}
            {:name :target_language
             :spec :string
             :description "Target language"}]
   :outputs [{:name :translation
              :spec :string
              :description "Translated text"}]
   :instructions "Translate text accurately."})

(comment
  (dscloj/register-provider! :gpt4 
    {:provider :openai 
     :model "gpt-4" 
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  ;; Pass options as fourth argument
  (def translation-result 
    (dscloj/predict translation-module
                   {:text "Hello, how are you?"
                    :target_language "Spanish"}
                   :gpt4
                   {:temperature 0.3
                    :max-tokens 100}))  ; options in fourth argument
  
  (:translation translation-result)
  ;; => "Hola, ¿cómo estás?"
  )


;; =============================================================================
;; EXAMPLE 3: Multiple Output Types (bool, float, str)
;; =============================================================================

(def qa-with-confidence-module
  {:inputs [{:name :question
             :spec :string
             :description "Question to answer"}]
   :outputs [{:name :answer
              :spec :string
              :description "The answer"}
             {:name :is_confident
              :spec :boolean
              :description "Whether the answer is confident"}
             {:name :confidence_score
              :spec :double
              :description "Confidence from 0.0 to 1.0"}]
   :instructions "Answer with confidence assessment."})

(comment
  (dscloj/register-provider! :gpt4 
    {:provider :openai 
     :model "gpt-4" 
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  (def qa-result 
    (dscloj/predict qa-with-confidence-module
                   {:question "What is the speed of light?"}
                   :gpt4))
  
  ;; Types are automatically converted and validated
  (:is_confident qa-result)     ;; => true (boolean)
  (:confidence_score qa-result) ;; => 0.95 (float)
  (:answer qa-result)           ;; => "The speed of light is..."
  
  (when (:is_confident qa-result)
    (if (> (:confidence_score qa-result) 0.8)
      (println "High confidence:" (:answer qa-result))
      (println "Low confidence:" (:answer qa-result))))
  )


;; =============================================================================
;; EXAMPLE 4: Complex Malli Specs with Constraints
;; =============================================================================

(def analysis-module
  {:inputs [{:name :text
             :spec [:string {:min 1}]
             :description "Text to analyze"}
            {:name :min_length
             :spec [:int {:min 0}]
             :description "Minimum length requirement"}]
   :outputs [{:name :summary
              :spec :string
              :description "Summary of the text"}
             {:name :is_valid
              :spec :boolean
              :description "Whether text meets requirements"}
             {:name :confidence
              :spec [:double {:min 0.0 :max 1.0}]
              :description "Confidence score 0.0-1.0"}]
   :instructions "Analyze the text and check if it meets the minimum length requirement."})

(comment
  (dscloj/register-provider! :gpt4 
    {:provider :openai 
     :model "gpt-4" 
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  (def analysis-result 
    (dscloj/predict analysis-module
                   {:text "This is a sample text for analysis."
                    :min_length 10}
                   :gpt4))
  
  ;; All outputs are type-validated against Malli specs
  (:summary analysis-result)      ;; => string
  (:is_valid analysis-result)     ;; => boolean
  (:confidence analysis-result)   ;; => double (0.0-1.0)
  )


;; =============================================================================
;; EXAMPLE 5: Disabling Validation
;; =============================================================================

;; Sometimes you might want to skip validation (e.g., for debugging)
(def strict-module
  {:inputs [{:name :number
             :spec [:int {:min 1 :max 100}]}]
   :outputs [{:name :result
              :spec :string}]
   :instructions "Process the number."})

(comment
  (dscloj/register-provider! :gpt4 
    {:provider :openai 
     :model "gpt-4" 
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  ;; With validation (default)
  (dscloj/predict strict-module
                 {:number 50}
                 :gpt4
                 {:validate? true})  ; This is the default
  
  ;; Without validation
  (dscloj/predict strict-module
                 {:number 50}
                 :gpt4
                 {:validate? false})  ; Skip Malli validation
  )


;; =============================================================================
;; EXAMPLE 6: Inspecting Modules and Generated Prompts
;; =============================================================================

(comment
  ;; View the prompt template
  (def prompt-template (dscloj/module->prompt qa-module))
  (println prompt-template)
  
  ;; Useful for debugging what's sent to the LLM
  )


;; =============================================================================
;; EXAMPLE 7: Working with Validation Errors
;; =============================================================================

(def typed-module
  {:inputs [{:name :age
             :spec [:int {:min 0 :max 150}]}
            {:name :name
             :spec [:string {:min 1}]}]
   :outputs [{:name :message
              :spec :string}]})

(comment
  (dscloj/register-provider! :gpt4 
    {:provider :openai 
     :model "gpt-4" 
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  ;; This will throw a validation error with details
  (try
    (dscloj/predict typed-module
                   {:age "not-a-number"  ; Invalid: should be int
                    :name "John"}
                   :gpt4)
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (println "Error:" (.getMessage e))
        (println "Details:" (:errors data))
        (println "Field:" (:field data))
        (println "Value:" (:value data)))))
  
  ;; Validation errors are detailed and helpful
  )


;; =============================================================================
;; EXAMPLE 8: Reusable Specs
;; =============================================================================

;; Define reusable Malli specs
(def QuestionSpec [:string {:min 1 :description "The question to answer"}])
(def AnswerSpec [:string {:min 1 :description "The answer"}])
(def ConfidenceSpec [:double {:min 0.0 :max 1.0 :description "Confidence score"}])

(def qa-with-context-module
  {:inputs [{:name :question
             :spec QuestionSpec}
            {:name :context
             :spec [:string {:description "Additional context"}]}]
   :outputs [{:name :answer
              :spec AnswerSpec}
             {:name :confidence
              :spec ConfidenceSpec}]
   :instructions "Answer based on the question and context."})

(comment
  (dscloj/register-provider! :gpt4 
    {:provider :openai 
     :model "gpt-4" 
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  (dscloj/predict qa-with-context-module
                 {:question "What is the capital?"
                  :context "We are discussing France."}
                 :gpt4)
  )


;; =============================================================================
;; EXAMPLE 9: Switching Between Providers
;; =============================================================================

(comment
  ;; Register multiple providers
  (dscloj/register-provider! :gpt4 
    {:provider :openai 
     :model "gpt-4" 
     :config {:api-key (System/getenv "OPENAI_API_KEY")}})
  
  (dscloj/register-provider! :claude 
    {:provider :anthropic 
     :model "claude-3-5-sonnet-20241022" 
     :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}})
  
  (dscloj/register-provider! :gemini 
    {:provider :gemini 
     :model "gemini-pro" 
     :config {:api-key (System/getenv "GOOGLE_API_KEY")}})
  
  ;; Same module, different providers - switch by just changing the config name
  (def openai-result (dscloj/predict qa-module 
                                     {:question "What is AI?"}
                                     :gpt4))
  
  (def anthropic-result (dscloj/predict qa-module 
                                        {:question "What is AI?"}
                                        :claude))
  
  (def gemini-result (dscloj/predict qa-module 
                                     {:question "What is AI?"}
                                     :gemini))
  
  ;; Compare results
  (println "OpenAI:" (:answer openai-result))
  (println "Anthropic:" (:answer anthropic-result))
  (println "Gemini:" (:answer gemini-result))
  )
