(ns dscloj.predict
  "Core declarative prediction: modules (typed inputs/outputs), prompt
  generation, output parsing, Malli validation, and the `predict` /
  `predict-stream` entry points. The base layer the `cot` and `react`
  modules build on."
  (:require [litellm.router :as router]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.json-schema :as json-schema]
            [cheshire.core :as json]
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

(def ^:private composite-spec-heads
  "Vector-form Malli spec heads that describe composite (array/nested) values."
  #{:vector :sequential :set :map :map-of :tuple})

(defn composite-spec?
  "Return true if the Malli spec describes a composite value (array or nested map).

  Composite specs are vector-form specs whose head is one of :vector,
  :sequential, :set, :map, :map-of, or :tuple, as well as [:maybe X] where
  X is itself composite. Scalar specs (including property-only forms like
  [:string {:min 1}] and [:maybe :string]) are not composite."
  [spec]
  (boolean
   (and (vector? spec)
        (let [head (first spec)]
          (or (contains? composite-spec-heads head)
              (and (= head :maybe)
                   (composite-spec? (last spec))))))))

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
    (composite-spec? spec) "json"
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
                                                 "        # note: the value you produce must be True or False")
                                               (when (= type-str "json")
                                                 (str "        # note: the value you produce must be valid JSON matching this JSON Schema: "
                                                      (json/generate-string (json-schema/transform spec)))))))))))
        
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
        ;; text is nil when the model returns a message with no content
        ;; (e.g. a reasoning model that spent its budget thinking); treat
        ;; that as "no fields present" rather than letting re-find NPE.
        extract-field (fn [field-name text]
                        (when text
                          (let [pattern (re-pattern (str "\\[\\[\\s*##\\s*" (name field-name) "\\s*##\\s*\\]\\]\\s*\\n([\\s\\S]*?)(?=\\n\\[\\[\\s*##|$)"))
                                match (re-find pattern text)]
                            (when match
                              (str/trim (second match))))))
        
        ;; Strip optional markdown code fences (```json ... ``` or ``` ... ```)
        strip-code-fences (fn [value]
                            (let [trimmed (str/trim value)]
                              (if-let [match (re-find #"(?s)^```(?:json)?\s*\n?(.*?)\n?```$" trimmed)]
                                (str/trim (second match))
                                trimmed)))

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
                         ;; parse-string-strict keeps top-level arrays as vectors
                         ;; (lazy seqs would fail [:vector ...] Malli validation)
                         (= type-str "json") (try (json/parse-string-strict (strip-code-fences value) true)
                                                  (catch Exception _ value))
                         :else value))]


    (into {}
          (for [{:keys [name spec]} outputs]
            (let [type-str (spec->type-str spec)
                  raw-value (extract-field name response)
                  converted-value (convert-type raw-value type-str)]
              [name converted-value])))))

(defn- validation-feedback-messages
  "Build the retry messages vector after a failed output validation.

  Parameters:
  - full-prompt: The original user prompt string
  - raw-response: The raw LLM response that failed validation
  - exception: The validation exception thrown by validate-outputs

  Returns a messages vector with the original user prompt, the assistant's
  previous raw response, and a user message describing the validation error
  and asking for a corrected response."
  [full-prompt raw-response exception]
  (let [errors (:errors (ex-data exception))
        error-text (str (ex-message exception)
                        (when errors
                          (str "\n" (pr-str errors))))]
    [{:role :user :content full-prompt}
     {:role :assistant :content raw-response}
     {:role :user
      :content (str "Your previous response failed validation with this error:\n"
                    error-text
                    "\n\nPlease respond again, re-emitting ALL output fields in the correct format.")}]))

(defn predict
  "Make a prediction using an LLM via the router API.

  Parameters:
  - provider-config: Either a keyword referencing a registered provider config, or a map with:
                     {:provider :openai :model \"gpt-4\" :config {:api-key \"...\"}}
  - module: The module definition with :inputs/:outputs fields containing :spec for Malli schemas
  - input-map: Map of input field names to values
  - options: Optional configuration map (e.g., :temperature, :validate?)

  Options:
  - :temperature - Temperature for sampling
  - :validate? - Whether to validate inputs/outputs with Malli specs (default: true)
  - :retries - Number of additional LLM calls to attempt when output validation
               fails (default: 0). Each retry feeds the validation error back to
               the LLM. Has no effect when :validate? is false.
  - Any other LLM-specific options

  Returns parsed output as a map based on module's output fields.

  Examples:
    ;; Using registered provider
    (register-provider! :gpt4 {:provider :openai :model \"gpt-4\" :config {:api-key \"sk-...\"}})
    (predict :gpt4 qa-module {:question \"What is 2+2?\"})

    ;; Ad-hoc provider (no registration)
    (predict {:provider :anthropic :model \"claude-3-5-sonnet-20241022\"
              :config {:api-key \"sk-...\"}}
             qa-module
             {:question \"What is 2+2?\"})"
  [provider-config module input-map & [options]]
  (let [;; Validate inputs if requested
        should-validate? (get options :validate? true)
        retries (get options :retries 0)
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

        ;; Options forwarded to the LLM (strip dscloj-only keys)
        llm-options (dissoc options :validate? :retries)]
    (loop [messages [{:role :user :content full-prompt}]
           remaining retries]
      (let [;; Call LLM via router API
            response (router/completion provider-config
                                        (merge {:messages messages} llm-options))
            raw-response (-> response
                             :choices
                             first
                             :message
                             :content)

            ;; Parse structured output
            parsed (parse-output raw-response module)]
        (if-not should-validate?
          parsed
          ;; Validate outputs, retrying with feedback when attempts remain
          (let [outcome (try
                          {:result (validate-outputs (:outputs module) parsed)}
                          (catch Exception e
                            {:error e}))]
            (cond
              (contains? outcome :result) (:result outcome)
              (pos? remaining) (recur (validation-feedback-messages full-prompt raw-response (:error outcome))
                                      (dec remaining))
              :else (throw (:error outcome)))))))))

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
                                           (dissoc options :on-chunk :debounce-ms :validate? :retries)))
        
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
