(ns dscloj.core
  (:require [litellm.core :as litellm]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.util :as mu]))

;; =============================================================================
;; Malli Schema Support
;; =============================================================================

(defn spec->type-str
  "Convert Malli spec to string type representation."
  [spec]
  (cond
    (= spec :string) "str"
    (= spec :int) "int"
    (= spec :double) "float"
    (= spec :float) "float"
    (= spec :boolean) "bool"
    (= spec 'string?) "str"
    (= spec 'int?) "int"
    (= spec 'double?) "float"
    (= spec 'float?) "float"
    (= spec 'boolean?) "bool"
    (vector? spec) (spec->type-str (first spec))
    :else "str"))

(defn validate-field
  "Validate a single field value against its Malli spec.
  
  Parameters:
  - field: Field definition with :name, :spec, :description
  - value: The value to validate
  
  Returns value if valid, throws exception if invalid."
  [field value]
  (let [{:keys [name spec]} field]
    (if (m/validate spec value)
      value
      (throw (ex-info (str "Validation failed for field " name)
                      {:field name
                       :spec spec
                       :value value
                       :errors (m/explain spec value)})))))

(defn validate-inputs
  "Validate all input fields against their Malli specs.
  
  Parameters:
  - fields: Vector of field definitions with :name, :spec, :description
  - input-map: Map of field names to values
  
  Returns input-map if valid, throws exception if invalid."
  [fields input-map]
  (doseq [field fields]
    (let [{:keys [name spec]} field]
      (when spec
        (when-let [value (get input-map name)]
          (validate-field field value)))))
  input-map)

(defn validate-outputs
  "Validate all output fields against their Malli specs.
  
  Parameters:
  - fields: Vector of field definitions with :name, :spec, :description
  - output-map: Map of field names to values
  
  Returns output-map if valid, throws exception if invalid."
  [fields output-map]
  (doseq [field fields]
    (let [{:keys [name spec]} field]
      (when spec
        (when-let [value (get output-map name)]
          (validate-field field value)))))
  output-map)

;; =============================================================================
;; Core Functions
;; =============================================================================

(defn module->prompt
  "Convert a module signature/schema into a prompt template.
  
  A module is a map with:
  - :inputs - Vector of field definitions with :name, :spec, :description keys
  - :outputs - Vector of field definitions with :name, :spec, :description keys
  - :instructions - Optional string describing the task instructions, rules, and examples
  
  Example:
    (module->prompt example-module)
  
  Returns a formatted prompt string."
  [module]
  (let [{:keys [inputs outputs instructions]} module
        format-field (fn [idx {:keys [name spec description]}]
                       (let [type-str (spec->type-str spec)]
                         (str (inc idx) ". `" (clojure.core/name name) "` (" type-str "): " description)))
        
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
                                      (for [{:keys [name spec]} outputs]
                                        (let [type-str (spec->type-str spec)]
                                          (str "[[ ## " (clojure.core/name name) " ## ]]\n"
                                               "{" (clojure.core/name name) "}"
                                               (when (= type-str "bool")
                                                 "        # note: the value you produce must be True or False"))))))))
        
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
    (parse-output llm-response {:outputs [{:name :answer :spec :string}
                                          {:name :confidence :spec :boolean}]})"
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
          (for [{:keys [name spec]} outputs]
            (let [type-str (spec->type-str spec)
                  raw-value (extract-field name response)
                  converted-value (convert-type raw-value type-str)]
              [name converted-value])))))

(defn predict
  "Make a prediction using an LLM.
  
  Parameters:
  - module: The module definition with :inputs/:outputs fields containing :spec for Malli schemas
  - input-map: Map of input field names to values
  - options: Optional configuration map (e.g., :model, :temperature, :validate?)
  
  Options:
  - :model - LLM model to use
  - :temperature - Temperature for sampling
  - :validate? - Whether to validate inputs/outputs with Malli specs (default: true)
  
  Returns parsed output as a map based on module's output fields.
  
  Example:
    (predict qa-module {:question \"What is 2+2?\"} {:model \"gpt-4\"})"
  [module input-map & [options]]
  (let [;; Validate inputs if requested
        should-validate? (get options :validate? true)
        validated-input (if should-validate?
                         (validate-inputs (:inputs module) input-map)
                         input-map)
        
        ;; Generate base prompt from module
        base-prompt (module->prompt module)
        
        ;; Add input values to the prompt
        input-section (str/join "\n\n"
                                (for [{:keys [name]} (:inputs module)]
                                  (str "[[ ## " (clojure.core/name name) " ## ]]\n"
                                       (get validated-input name ""))))
        
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
                             module)
        
        ;; Validate outputs if requested
        validated-output (if should-validate?
                          (validate-outputs (:outputs module) parsed)
                          parsed)]
    validated-output))
