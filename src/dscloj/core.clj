(ns dscloj.core
  (:require [litellm.router :as router]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [malli.core :as m]
            [clojure.core.async :as async :refer [go-loop <! >! chan close!]]
            [litellm.streaming :as streaming]))

;; =============================================================================
;; Provider Management (Router API)
;; =============================================================================

(defn register-provider!
  "Register a named provider configuration for use with predict/predict-stream.
  
  Parameters:
  - config-name: Keyword identifier for this configuration (e.g., :gpt4, :claude)
  - provider-spec: Map with :provider, :model, and :config keys
  
  Example:
    (register-provider! :gpt4 
      {:provider :openai 
       :model \"gpt-4\" 
       :config {:api-key (System/getenv \"OPENAI_API_KEY\")}})"
  [config-name provider-spec]
  (router/register! config-name provider-spec))

(defn quick-setup!
  "Quick setup of common providers from environment variables.
  Sets up :openai, :anthropic, :gemini, etc. based on available API keys.
  
  Example:
    (quick-setup!)  ; Reads OPENAI_API_KEY, ANTHROPIC_API_KEY, etc."
  []
  (router/quick-setup!))


(defn list-providers
  "List all registered provider configurations.
  
  Returns a map of config-name -> provider-spec."
  []
  (router/list-providers))

;; =============================================================================
;; Malli Schema Support
;; =============================================================================

(defn complex-spec?
  "Check if a Malli spec requires JSON serialization.
  Returns true for :map, :map-of, :vector, :sequential, :set, :tuple, etc.
  Note: :enum is NOT included - enums are plain string values."
  [spec]
  (and (vector? spec)
       (#{:map :map-of :vector :sequential :set :tuple :or :and :maybe} (first spec))))

(defn spec->type-str
  "Convert Malli spec to string type representation.
  For complex types (maps, vectors, enums), returns a JSON-descriptive string."
  [spec]
  (cond
    ;; Primitives
    (= spec :string) "str"
    (= spec :int) "int"
    (= spec :double) "float"
    (= spec :float) "float"
    (= spec :boolean) "bool"
    (= spec :any) "any"
    (= spec 'string?) "str"
    (= spec 'int?) "int"
    (= spec 'double?) "float"
    (= spec 'float?) "float"
    (= spec 'boolean?) "bool"

    ;; Map - describe fields as JSON object
    (and (vector? spec) (= :map (first spec)))
    (let [fields (filter vector? (rest spec))
          field-strs (for [[k & rest] fields
                           :let [opts (when (map? (first rest)) (first rest))
                                 field-spec (if opts (second rest) (first rest))
                                 optional? (:optional opts)]]
                       (str (name k) (when optional? "?") ": " (spec->type-str field-spec)))]
      (str "json {" (str/join ", " field-strs) "}"))

    ;; Map-of - describe as JSON object with dynamic keys
    (and (vector? spec) (= :map-of (first spec)))
    (let [[_ key-spec val-spec] spec]
      (str "json object with " (spec->type-str key-spec) " keys and " (spec->type-str val-spec) " values"))

    ;; Vector/sequential - describe as JSON array
    (and (vector? spec) (#{:vector :sequential} (first spec)))
    (str "json array of " (spec->type-str (second spec)))

    ;; Enum - list options
    (and (vector? spec) (= :enum (first spec)))
    (str "one of: " (str/join ", " (map str (rest spec))))

    ;; Maybe - nullable
    (and (vector? spec) (= :maybe (first spec)))
    (str (spec->type-str (second spec)) " or null")

    ;; Wrapped specs like [:string {:min 1}] - recurse on first element
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

(defn- parse-json-value
  "Parse a string as JSON if it looks like JSON (starts with { or [).
  Returns the parsed Clojure data structure, or the original value if parsing fails."
  [value]
  (when value
    (let [trimmed (str/trim value)]
      (if (or (str/starts-with? trimmed "{")
              (str/starts-with? trimmed "["))
        (try
          (json/read-str trimmed :key-fn keyword)
          (catch Exception _ value))
        value))))

;; =============================================================================
;; JSON Schema Conversion (for function calling)
;; =============================================================================

(defn malli-spec->json-schema
  "Convert a Malli spec to JSON Schema format for function calling parameters.

  Supports common Malli types: :string, :int, :double, :boolean, :enum, :map, :vector, etc."
  [spec]
  (cond
    ;; Primitives
    (= spec :string) {:type "string"}
    (= spec :int) {:type "integer"}
    (= spec :double) {:type "number"}
    (= spec :float) {:type "number"}
    (= spec :boolean) {:type "boolean"}
    (= spec :any) {}
    (= spec 'string?) {:type "string"}
    (= spec 'int?) {:type "integer"}
    (= spec 'double?) {:type "number"}
    (= spec 'float?) {:type "number"}
    (= spec 'boolean?) {:type "boolean"}

    ;; Enum - list allowed values
    (and (vector? spec) (= :enum (first spec)))
    {:type "string" :enum (mapv str (rest spec))}

    ;; Maybe - nullable
    (and (vector? spec) (= :maybe (first spec)))
    (let [inner (malli-spec->json-schema (second spec))]
      (if (:type inner)
        (assoc inner :nullable true)
        inner))

    ;; Map with explicit fields
    (and (vector? spec) (= :map (first spec)))
    (let [fields (filter vector? (rest spec))
          required-fields (for [[k & rest] fields
                                :let [opts (when (map? (first rest)) (first rest))
                                      optional? (:optional opts)]
                                :when (not optional?)]
                            (name k))
          properties (into {}
                           (for [[k & rest] fields
                                 :let [opts (when (map? (first rest)) (first rest))
                                       field-spec (if opts (second rest) (first rest))]]
                             [(name k) (malli-spec->json-schema field-spec)]))]
      (cond-> {:type "object" :properties properties}
        (seq required-fields) (assoc :required (vec required-fields))))

    ;; Map-of - object with additionalProperties
    (and (vector? spec) (= :map-of (first spec)))
    {:type "object" :additionalProperties (malli-spec->json-schema (nth spec 2))}

    ;; Vector/sequential - array
    (and (vector? spec) (#{:vector :sequential} (first spec)))
    {:type "array" :items (malli-spec->json-schema (second spec))}

    ;; Set - array with unique items
    (and (vector? spec) (= :set (first spec)))
    {:type "array" :items (malli-spec->json-schema (second spec)) :uniqueItems true}

    ;; Tuple - array with positional items
    (and (vector? spec) (= :tuple (first spec)))
    {:type "array" :items (mapv malli-spec->json-schema (rest spec))}

    ;; Wrapped specs like [:string {:min 1}] - recurse on first element
    (vector? spec)
    (malli-spec->json-schema (first spec))

    :else {:type "string"}))

(defn outputs->tool-definition
  "Convert module outputs to a function calling tool definition.

  Creates a tool that accepts all output fields as parameters."
  [module]
  (let [{:keys [outputs instructions]} module
        properties (into {}
                         (for [{:keys [name spec description]} outputs]
                           [(clojure.core/name name)
                            (cond-> (malli-spec->json-schema spec)
                              description (assoc :description description))]))
        required (mapv #(clojure.core/name (:name %)) outputs)]
    {:type "function"
     :function {:name "submit_response"
                :description (or instructions "Submit the structured response")
                :parameters {:type "object"
                             :properties properties
                             :required required}}}))

(defn- parse-tool-call-response
  "Parse the tool call response from an LLM that used function calling.

  Returns a map of output field names to values."
  [response outputs]
  (let [tool-calls (-> response :choices first :message :tool-calls)
        first-call (first tool-calls)]
    (when first-call
      (let [arguments-str (-> first-call :function :arguments)
            parsed (try
                     (json/read-str arguments-str :key-fn keyword)
                     (catch Exception _ nil))]
        ;; Convert string keys to keyword keys matching output names
        (when parsed
          (into {}
                (for [{:keys [name]} outputs
                      :let [k (keyword (clojure.core/name name))
                            v (get parsed k (get parsed (clojure.core/name name)))]]
                  [name v])))))))

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
                                               (cond
                                                 (= type-str "bool")
                                                 "        # note: the value you produce must be True or False"

                                                 (and (vector? spec) (= :enum (first spec)))
                                                 "        # note: respond with just the value, no quotes"

                                                 (complex-spec? spec)
                                                 "        # note: respond with valid JSON"

                                                 :else ""))))))))
        
        ;; Instructions section
        instructions-section (when instructions
                               (str "[[ ## completed ## ]]\n"
                                    "In adhering to this structure, your instructions are: " instructions))
        
        ;; Combine all sections
        sections (filter some? [input-section output-section interaction-format instructions-section])]
    (str/join "\n" sections)))

(defn- strip-completion-marker
  "Remove the [[ ## completed ## ]] marker and anything after it from a string."
  [s]
  (when s
    (-> s
        (str/replace #"\[\[\s*##\s*completed\s*##\s*\]\].*$" "")
        (str/trim))))

(defn parse-output
  "Parse LLM output based on module's output field definitions.

  Parameters:
  - response: The LLM response string
  - module: The module definition with :outputs

  Returns a map with field names as keys and parsed values.
  For complex specs (maps, vectors, enums), parses JSON responses.

  Example:
    (parse-output llm-response {:outputs [{:name :answer :spec :string}
                                          {:name :confidence :spec :boolean}]})"
  [response {:keys [outputs]}]
  (let [;; Extract content between [[ ## field_name ## ]] or [[##field_name##]] markers
        extract-field (fn [field-name text]
                        (let [pattern (re-pattern (str "\\[\\[\\s*##\\s*" (name field-name) "\\s*##\\s*\\]\\]\\s*\\n([\\s\\S]*?)(?=\\n\\[\\[\\s*##|$)"))
                              match (re-find pattern text)]
                          (when match
                            (-> (second match)
                                (str/trim)
                                (strip-completion-marker)))))

        ;; Get base type from spec (unwrap [:string {:min 1}] -> :string)
        base-type (fn [spec]
                    (if (and (vector? spec) (not (complex-spec? spec)))
                      (first spec)
                      spec))

        ;; Convert string value to appropriate type based on spec
        convert-value (fn [value spec]
                        (let [base (base-type spec)]
                          (cond
                            (nil? value) nil

                            ;; Complex specs - parse as JSON
                            (complex-spec? spec)
                            (parse-json-value value)

                            ;; Booleans
                            (#{:boolean 'boolean?} base)
                            (contains? #{"True" "true" "TRUE"} value)

                            ;; Integers
                            (#{:int 'int?} base)
                            (try (Long/parseLong value) (catch Exception _ value))

                            ;; Floats
                            (#{:double :float 'double? 'float?} base)
                            (try (Double/parseDouble value) (catch Exception _ value))

                            :else value)))]
    (into {}
          (for [{:keys [name spec]} outputs]
            (let [raw-value (extract-field name response)
                  converted-value (convert-value raw-value spec)]
              [name converted-value])))))

(defn- predict-with-function-calling
  "Internal: Make a prediction using function calling for structured output."
  [provider-config module validated-input options]
  (let [{:keys [inputs outputs instructions]} module

        ;; Build a clean prompt with inputs and instructions
        input-section (str/join "\n\n"
                                (for [{:keys [name description]} inputs]
                                  (str (clojure.core/name name) ": " (get validated-input name ""))))

        prompt (str (when instructions (str instructions "\n\n"))
                    "Given the following inputs:\n" input-section
                    "\n\nCall the submit_response function with your answer.")

        ;; Create tool definition from outputs
        tool-def (outputs->tool-definition module)

        ;; Call LLM with function calling
        response (router/completion provider-config
                                    (merge {:messages [{:role :user :content prompt}]
                                            :tools [tool-def]
                                            :tool_choice {:type "function"
                                                          :function {:name "submit_response"}}}
                                           (dissoc options :validate? :with-metadata? :use-function-calling?)))]
    {:parsed (parse-tool-call-response response outputs)
     :response response}))

(defn- predict-with-markers
  "Internal: Make a prediction using marker-based parsing for structured output."
  [provider-config module validated-input options]
  (let [;; Generate base prompt from module
        base-prompt (module->prompt module)

        ;; Add input values to the prompt
        input-section (str/join "\n\n"
                                (for [{:keys [name]} (:inputs module)]
                                  (str "[[ ## " (clojure.core/name name) " ## ]]\n"
                                       (get validated-input name ""))))

        ;; Combine into full prompt
        full-prompt (str base-prompt "\n\n" input-section)

        ;; Call LLM via router API
        response (router/completion provider-config
                                    (merge {:messages [{:role :user :content full-prompt}]}
                                           (dissoc options :validate? :with-metadata? :use-function-calling?)))]
    {:parsed (parse-output (-> response :choices first :message :content) module)
     :response response}))

(defn predict
  "Make a prediction using an LLM via the router API.

  Parameters:
  - provider-config: Either a keyword referencing a registered provider config, or a map with:
                     {:provider :openai :model \"gpt-4\" :config {:api-key \"...\"}}
  - module: The module definition with :inputs/:outputs fields containing :spec for Malli schemas
  - input-map: Map of input field names to values
  - options: Optional configuration map (e.g., :temperature, :validate?, :with-metadata?)

  Options:
  - :temperature - Temperature for sampling
  - :validate? - Whether to validate inputs/outputs with Malli specs (default: true)
  - :with-metadata? - Return full response with :outputs, :usage, :model (default: false)
  - :use-function-calling? - Force function calling on/off (default: auto-detect)
  - Any other LLM-specific options

  Returns:
  - If :with-metadata? is false (default): parsed output map based on module's output fields
  - If :with-metadata? is true: {:outputs {...} :usage {:prompt_tokens N :completion_tokens N :total_tokens N} :model \"...\"}

  Examples:
    ;; Using registered provider
    (register-provider! :gpt4 {:provider :openai :model \"gpt-4\" :config {:api-key \"sk-...\"}})
    (predict :gpt4 qa-module {:question \"What is 2+2?\"})

    ;; With metadata for tracing/cost tracking
    (predict :gpt4 qa-module {:question \"What is 2+2?\"} {:with-metadata? true})
    ;; => {:outputs {:answer \"4\"} :usage {:prompt_tokens 50 :completion_tokens 10 :total_tokens 60} :model \"gpt-4\"}

    ;; Ad-hoc provider (no registration)
    (predict {:provider :anthropic :model \"claude-3-5-sonnet-20241022\"
              :config {:api-key \"sk-...\"}}
             qa-module
             {:question \"What is 2+2?\"})"
  [provider-config module input-map & [options]]
  (let [;; Validate inputs if requested
        should-validate? (get options :validate? true)
        with-metadata? (get options :with-metadata? false)
        validated-input (if should-validate?
                          (validate-inputs (:inputs module) input-map)
                          input-map)

        ;; Determine if we should use function calling
        ;; Auto-detect based on provider support, or use explicit option
        use-fc? (if (contains? options :use-function-calling?)
                  (:use-function-calling? options)
                  (try
                    (router/supports-function-calling? provider-config)
                    (catch Exception _ false)))

        ;; Helper to check if parsed result is empty (nil or all nil values)
        empty-result? (fn [m] (or (nil? m)
                                  (and (map? m) (every? nil? (vals m)))))

        ;; Make the prediction using the appropriate method
        {:keys [parsed response]} (if use-fc?
                                    (let [fc-result (try
                                                      (predict-with-function-calling provider-config module validated-input options)
                                                      (catch Exception e nil))]
                                      ;; Fall back to markers if function calling fails or returns empty
                                      (if (or (nil? fc-result) (empty-result? (:parsed fc-result)))
                                        (predict-with-markers provider-config module validated-input options)
                                        fc-result))
                                    (predict-with-markers provider-config module validated-input options))

        ;; Validate outputs if requested
        validated-output (if should-validate?
                           (validate-outputs (:outputs module) parsed)
                           parsed)]
    (if with-metadata?
      {:outputs validated-output
       :usage (:usage response)
       :model (:model response)}
      validated-output)))

;; =============================================================================
;; Streaming Support
;; =============================================================================

(defn parse-streaming-json-array
  "Progressively parse a JSON array from accumulated text.
  Returns a vector of parsed items found so far.
  
  This handles incomplete JSON by extracting complete objects and leaving
  incomplete ones for the next iteration."
  [accumulated-text]
  (let [;; Try to find complete JSON objects within array brackets
        ;; Look for pattern: [{...}, {...}, ...]
        array-pattern #"\[\s*(.*?)\s*\]"
        match (re-find array-pattern accumulated-text)]
    (if match
      (let [items-text (second match)
            ;; Split by commas at the top level (not nested)
            ;; This is a simplified parser - in production you'd use a proper JSON parser
            items (try
                    ;; Attempt to parse as EDN/JSON
                    (when (seq items-text)
                      (read-string (str "[" items-text "]")))
                    (catch Exception _ []))]
        items)
      [])))

(defn parse-streaming-output
  "Parse streaming LLM output progressively.
  
  Parameters:
  - accumulated-text: The accumulated response text so far
  - module: The module definition with :outputs
  
  Returns parsed output, which may be partial/incomplete."
  [accumulated-text module]
  (parse-output accumulated-text module))

(defn predict-stream
  "Stream predictions from an LLM with progressive structured output parsing via router API.
  
  Parameters:
  - provider-config: Either a keyword referencing a registered provider config, or a map with:
                     {:provider :openai :model \"gpt-4\" :config {:api-key \"...\"}}
  - module: The module definition with :inputs/:outputs fields
  - input-map: Map of input field names to values
  - options: Configuration map with :on-chunk callback, :debounce-ms, etc.
  
  Options:
  - :temperature - Temperature for sampling
  - :validate? - Whether to validate inputs/outputs with Malli specs (default: false for streaming)
  - :debounce-ms - Milliseconds to debounce emissions (default: 10)
  - :on-chunk - Optional callback function called with each chunk
  
  Returns: core.async channel that emits progressively parsed output maps.
  
  Examples:
    ;; Using registered provider
    (register-provider! :gpt4 {:provider :openai :model \"gpt-4\" :config {:api-key \"sk-...\"}})
    (let [ch (predict-stream :gpt4 whales-module {:query \"Tell me about whales\"})]
      (go-loop []
        (when-let [output (<! ch)]
          (println output)
          (recur))))"
  [provider-config module input-map & [options]]
  (let [should-validate? (get options :validate? false)
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
        
        ;; Create output channel
        output-ch (chan)
        
        ;; Call LLM with streaming enabled via router API
        stream-ch (router/completion provider-config
                                    (merge {:messages [{:role :user :content full-prompt}]
                                            :stream true}
                                           (dissoc options :on-chunk :debounce-ms :validate?)))
        
        debounce-ms (get options :debounce-ms 10)
        on-chunk-fn (get options :on-chunk)
        
        ;; Track accumulated content and last emission time
        accumulated (atom "")
        last-emit-time (atom 0)]
    
    ;; Process stream in background
    (go-loop []
      (if-let [chunk (<! stream-ch)]
        (do
          ;; Extract content from chunk
          (when-let [content (streaming/extract-content chunk)]
            (swap! accumulated str content)
            
            ;; Call on-chunk callback if provided
            (when on-chunk-fn
              (on-chunk-fn chunk))
            
            ;; Debounce emissions
            (let [now (System/currentTimeMillis)
                  elapsed (- now @last-emit-time)]
              (when (>= elapsed debounce-ms)
                (let [parsed (parse-streaming-output @accumulated module)]
                  (when (seq parsed)
                    (>! output-ch parsed))
                  (reset! last-emit-time now)))))
          (recur))
        ;; Stream complete - emit final result and close
        (do
          (let [final-parsed (parse-streaming-output @accumulated module)
                validated-output (if should-validate?
                                  (try
                                    (validate-outputs (:outputs module) final-parsed)
                                    (catch Exception e
                                      (println "Warning: Final output validation failed:" (.getMessage e))
                                      final-parsed))
                                  final-parsed)]
            (when (seq validated-output)
              (>! output-ch validated-output)))
          (close! output-ch))))
    
    ;; Return the output channel
    output-ch))
