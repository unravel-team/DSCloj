(ns dsclj.core
  (:require [litellm.core :as litellm]
            [malli.core :as m]
            [clojure.string :as str]))

(defn module->prompt
  "Convert a module signature/schema into a prompt template.
  
  A module is a map with:
  - :inputs - A vector of field definitions with :name, :type, :description keys
  - :outputs - A vector of field definitions with :name, :type, :description keys
  - :rules - Optional string with detailed rules and examples
  - :objective - Optional string describing the task objective
  
  Example:
    (module->prompt example-module)
  
  Returns a formatted prompt string."
  [{:keys [inputs outputs rules objective]}]
  (let [format-field (fn [idx {:keys [name type description]}]
                       (str (inc idx) ". `" (clojure.core/name name) "` (" type "): " description))
        
        ;; Input fields section
        input-section (when (seq inputs)
                        (str "Your input fields are:\n"
                             (str/join "\n" (map-indexed format-field inputs))))
        
        ;; Output fields section
        output-section (when (seq outputs)
                         (str "Your output fields are:\n"
                              (str/join "\n" (map-indexed format-field outputs))))
        
        ;; Interaction format section (includes both inputs and outputs)
        interaction-format (when (or (seq inputs) (seq outputs))
                             (str "All interactions will be structured in the following way, with the appropriate values filled in.\n\n"
                                  (str/join "\n\n"
                                    (concat
                                      (for [{:keys [name]} inputs]
                                        (str "[[ ## " (clojure.core/name name) " ## ]]\n"
                                             "{" (clojure.core/name name) "}"))
                                      (for [{:keys [name type]} outputs]
                                        (str "[[ ## " (clojure.core/name name) " ## ]]\n"
                                             "{" (clojure.core/name name) "}"
                                             (when (= type "bool")
                                               "        # note: the value you produce must be True or False")))))))
        
        ;; Objective section
        objective-section (when objective
                            (str "[[ ## completed ## ]]\n"
                                 "In adhering to this structure, your objective is: " objective))
        
        ;; Combine all sections (rules inserted between output and interaction format)
        sections (filter some? [input-section output-section rules interaction-format objective-section])]
    (str/join "\n" sections)))

(defn parse-output
  "Parse LLM output based on module's output field definitions.
  
  Parameters:
  - response: The LLM response string
  - module: The module definition with :outputs
  
  Returns a map with field names as keys and parsed values.
  
  Example:
    (parse-output llm-response {:outputs [{:name :answer :type \"str\"}
                                          {:name :confidence :type \"bool\"}]})"
  [response {:keys [outputs]}]
  (let [;; Extract content between [[ ## field_name ## ]] markers
        extract-field (fn [field-name text]
                        (let [pattern (re-pattern (str "\\[\\[ ## " (name field-name) " ## \\]\\]\\s*\\n([\\s\\S]*?)(?=\\n\\[\\[ ## |$)"))
                              match (re-find pattern text)]
                          (when match
                            (str/trim (second match)))))
        
        ;; Convert string value to appropriate type
        convert-type (fn [value type-str]
                       (cond
                         (nil? value) nil
                         (= type-str "bool") (or (= value "True") 
                                                  (= value "true")
                                                  (= value "TRUE"))
                         (= type-str "int") (try (Long/parseLong value)
                                                 (catch Exception _ value))
                         (= type-str "float") (try (Double/parseDouble value)
                                                   (catch Exception _ value))
                         :else value))]


    (into {}
          (for [{:keys [name type]} outputs]
            (do (println name type response)
             (let [raw-value (extract-field name response)
                   converted-value (convert-type raw-value type)]
               [name converted-value]))))))

(defn predict
  "Make a prediction using an LLM.
  
  Parameters:
  - module: The module definition with :inputs and :outputs
  - input-map: Map of input field names to values
  - options: Optional configuration map (e.g., :model, :temperature)
  
  Returns parsed output as a map based on module's output fields.
  
  Example:
    (predict qa-module {:question \"What is 2+2?\"} {:model \"gpt-4\"})"
  [module input-map & [options]]
  (let [;; Generate base prompt from module
        base-prompt (module->prompt module)
        
        ;; Add input values to the prompt
        input-section (str/join "\n\n"
                                (for [{:keys [name]} (:inputs module)]
                                  (str "[[ ## " (clojure.core/name name) " ## ]]\n"
                                       (get input-map name ""))))
        
        ;; Combine into full prompt
        full-prompt (str base-prompt "\n\n" input-section)
        
        ;; Call LLM
        response (litellm/completion :openai
                                     (or (:model options) "gpt-3.5-turbo")
                                     {:messages [{:role :user :content full-prompt}]}
                                     (dissoc options :model))
        
        ;; Parse and return structured output
        parsed (parse-output (-> response
                                 :choices
                                 first
                                 :message
                                 :content)
                             module)]
    parsed))


(def example-module
  {:inputs  [{:name :question 
              :type "str"
              :description "The question to answer"
              :required true}]
   
   :outputs [{:name :reasoning
              :type "str"
              :description "Step-by-step reasoning process"}
             {:name :answer 
              :type "str"
              :description "The final answer"}
             {:name :confidence 
              :type "float"
              :description "Confidence score between 0.0 and 1.0"}]
   
   :objective "Answer questions with clear reasoning and confidence scores."})

(comment
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
     :objective "Provide concise and accurate answers."})
  
  ;; Call predict - it handles everything automatically:
  ;; - Generates prompt from module
  ;; - Injects input values
  ;; - Calls LLM
  ;; - Parses output
  (def result (predict qa-module 
                       {:question "What is the capital of France?"}
                       {:model "gpt-4"
                        :api-key (System/getenv "OPENAI_API_KEY")}))
  
  (:answer result)
  ;; => "Paris"
  
  
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
     :rules (str "STRICT RULES:\n"
                 "1. ONLY use data provided in the input\n"
                 "2. Never make up financial information\n"
                 "3. Be clear about risks and limitations")
     :objective "Compare financial instruments based on provided data."})
  
  (def fin-result 
    (predict financial-comparison-module
             {:query "Compare HDFC Bank FD vs SBI FD"
              :data "HDFC Bank FD: 7.0% interest, 5 years\nSBI FD: 6.8% interest, 5 years"}
             {:model "gpt-4"
              :api-key (System/getenv "OPENAI_API_KEY")}))
  
  (:reasoning fin-result)
  (:summary fin-result)
  (:recommendation fin-result)
  
  
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
     :objective "Translate text accurately."})
  
  (def translation-result 
    (predict translation-module
             {:text "Hello, how are you?"
              :target_language "Spanish"}
             {:model "gpt-4"
              :temperature 0.3
              :max-tokens 100
              :api-key (System/getenv "OPENAI_API_KEY")}))
  
  (:translation translation-result)
  ;; => "Hola, ¿cómo estás?"
  
  
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
     :objective "Answer with confidence assessment."})
  
  (def qa-result 
    (predict qa-with-confidence-module
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
  
  
  ;; =============================================================================
  ;; EXAMPLE 5: Inspecting Generated Prompts
  ;; =============================================================================
  
  ;; View the prompt template
  (def prompt-template (module->prompt qa-module))
  (println prompt-template)
  
  ;; Useful for debugging what's sent to the LLM
  
  )
