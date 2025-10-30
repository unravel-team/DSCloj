(ns dsclj.core
  (:require [litellm.core :as litellm]
            [malli.core :as m]
            [clojure.string :as str]))

(defn module->prompt
  "Convert a module signature/schema into a prompt template.
  
  A module is a map with:
  - :inputs - A vector of field definitions with :name, :type, :description keys
  - :outputs - A vector of field definitions with :name, :type, :description keys
  - :instructions - Optional string describing the task instructions, rules, and examples
  
  Example:
    (module->prompt example-module)
  
  Returns a formatted prompt string."
  [{:keys [inputs outputs instructions]}]
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
        
        ;; Instructions section
        instructions-section (when instructions
                               (str "[[ ## completed ## ]]\n"
                                    "In adhering to this structure, your instructions are: " instructions))
        
        ;; Combine all sections
        sections (filter some? [input-section output-section interaction-format instructions-section])]
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
  (let [;; Extract content between [[ ## field_name ## ]] or [[##field_name##]] markers
        extract-field (fn [field-name text]
                        (let [pattern (re-pattern (str "\\[\\[\\s*##\\s*" (name field-name) "\\s*##\\s*\\]\\]\\s*\\n([\\s\\S]*?)(?=\\n\\[\\[\\s*##|$)"))
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
