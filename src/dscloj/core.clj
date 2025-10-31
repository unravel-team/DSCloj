(ns dscloj.core
  (:require [litellm.core :as litellm]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.util :as mu]))

;; =============================================================================
;; Malli Schema Support
;; =============================================================================

(defn malli-type->str
  "Convert Malli type to string type representation."
  [malli-type]
  (cond
    (= malli-type :string) "str"
    (= malli-type :int) "int"
    (= malli-type :double) "float"
    (= malli-type :float) "float"
    (= malli-type :boolean) "bool"
    (= malli-type 'string?) "str"
    (= malli-type 'int?) "int"
    (= malli-type 'double?) "float"
    (= malli-type 'float?) "float"
    (= malli-type 'boolean?) "bool"
    :else "str"))

(defn malli-schema->field
  "Convert a Malli schema property to a field definition.
  
  Parameters:
  - prop-name: The property name (keyword)
  - prop-schema: The Malli schema for this property
  
  Returns a field map with :name, :type, and :description."
  [prop-name prop-schema]
  (let [;; Handle both simple types and vector schemas
        schema-vec (if (vector? prop-schema) prop-schema [prop-schema])
        base-type (first schema-vec)
        ;; Check if second element is a map (properties)
        props (when (and (> (count schema-vec) 1)
                        (map? (second schema-vec)))
                (second schema-vec))
        description (or (:description props) 
                       (str "Field " (name prop-name)))]
    {:name prop-name
     :type (malli-type->str base-type)
     :description description}))

(defn malli-schema->fields
  "Convert a Malli :map schema to a vector of field definitions.
  
  Parameters:
  - schema: A Malli schema (typically a :map schema)
  
  Returns a vector of field maps with :name, :type, and :description."
  [schema]
  (when schema
    (let [parsed (m/schema schema)
          properties (m/properties parsed)
          children (m/children parsed)]
      (mapv (fn [[prop-name _props prop-schema]]
              (malli-schema->field prop-name prop-schema))
            children))))

(defn normalize-module
  "Normalize a module definition to use field vectors.
  
  If the module uses Malli schemas (:input-schema, :output-schema),
  convert them to field vectors (:inputs, :outputs).
  
  Parameters:
  - module: Module definition (can use either field vectors or Malli schemas)
  
  Returns a normalized module with :inputs and :outputs as field vectors."
  [module]
  (let [inputs (or (:inputs module)
                   (when-let [schema (:input-schema module)]
                     (malli-schema->fields schema)))
        outputs (or (:outputs module)
                    (when-let [schema (:output-schema module)]
                      (malli-schema->fields schema)))]
    (assoc module
           :inputs inputs
           :outputs outputs)))

(defn validate-input
  "Validate input data against module's input schema (if using Malli).
  
  Parameters:
  - module: Module definition with optional :input-schema
  - input-map: Map of input field names to values
  
  Returns input-map if valid, throws exception if invalid."
  [module input-map]
  (if-let [schema (:input-schema module)]
    (if (m/validate schema input-map)
      input-map
      (throw (ex-info "Input validation failed"
                      {:errors (m/explain schema input-map)
                       :input input-map})))
    input-map))

(defn validate-output
  "Validate output data against module's output schema (if using Malli).
  
  Parameters:
  - module: Module definition with optional :output-schema
  - output-map: Map of output field names to values
  
  Returns output-map if valid, throws exception if invalid."
  [module output-map]
  (if-let [schema (:output-schema module)]
    (if (m/validate schema output-map)
      output-map
      (throw (ex-info "Output validation failed"
                      {:errors (m/explain schema output-map)
                       :output output-map})))
    output-map))

;; =============================================================================
;; Core Functions
;; =============================================================================

(defn module->prompt
  "Convert a module signature/schema into a prompt template.
  
  A module is a map with either:
  - :inputs/:outputs - Vectors of field definitions with :name, :type, :description keys
  - :input-schema/:output-schema - Malli schemas defining the structure
  - :instructions - Optional string describing the task instructions, rules, and examples
  
  Example:
    (module->prompt example-module)
  
  Returns a formatted prompt string."
  [module]
  (let [normalized (normalize-module module)
        {:keys [inputs outputs instructions]} normalized]
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
      (str/join "\n" sections))))

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
            (let [raw-value (extract-field name response)
                  converted-value (convert-type raw-value type)]
              [name converted-value])))))

(defn predict
  "Make a prediction using an LLM.
  
  Parameters:
  - module: The module definition with :inputs/:outputs or :input-schema/:output-schema
  - input-map: Map of input field names to values
  - options: Optional configuration map (e.g., :model, :temperature, :validate?)
  
  Options:
  - :model - LLM model to use
  - :temperature - Temperature for sampling
  - :validate? - Whether to validate inputs/outputs with Malli (default: true if schemas present)
  
  Returns parsed output as a map based on module's output fields.
  
  Example:
    (predict qa-module {:question \"What is 2+2?\"} {:model \"gpt-4\"})"
  [module input-map & [options]]
  (let [;; Validate input if Malli schema is present
        should-validate? (get options :validate? true)
        validated-input (if (and should-validate? (:input-schema module))
                         (validate-input module input-map)
                         input-map)
        
        ;; Normalize module to use field vectors
        normalized (normalize-module module)
        
        ;; Generate base prompt from module
        base-prompt (module->prompt normalized)
        
        ;; Add input values to the prompt
        input-section (str/join "\n\n"
                                (for [{:keys [name]} (:inputs normalized)]
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
                             normalized)
        
        ;; Validate output if Malli schema is present
        validated-output (if (and should-validate? (:output-schema module))
                          (validate-output module parsed)
                          parsed)]
    validated-output))
