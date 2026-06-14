(ns dscloj.react
  "ReAct (Reasoning + Acting) agent loop, built on `predict`.

  Mirrors DSPy's `ReAct` (dspy/predict/react.py): each turn is a structured
  prediction emitting next_thought / next_tool_name / next_tool_args; the
  chosen tool runs and its observation is appended to a growing trajectory
  string; the loop ends when the model calls `finish` (or the iteration
  budget runs out). A final chain-of-thought extraction turns the trajectory
  into the module's declared outputs. No native LLM tool-calling required."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [dscloj.predict :as predict]
            [dscloj.cot :as cot]))
(def ^:private trajectory-field
  {:name :trajectory
   :spec :string
   :description "Your past thoughts, tool calls, and their observations."})

(defn- format-tool
  "Render one tool for the agent instructions: index, name, description, and
  its argument fields (if any)."
  [idx {:keys [name description args]}]
  (str "(" (inc idx) ") " name ": " description
       (when (seq args)
         (str " Arguments: "
              (str/join ", "
                        (for [{arg-name :name arg-spec :spec arg-desc :description} args]
                          (str (clojure.core/name arg-name)
                               " (" (predict/spec->type-str arg-spec) ")"
                               (when arg-desc (str " - " arg-desc)))))))))

(defn- react-instructions
  "Build the agent instructions: the module's own instructions, the ReAct
  protocol, and the catalog of callable tools (finish included)."
  [module tools]
  (let [field-list (fn [fields]
                     (str/join ", " (map #(str "`" (clojure.core/name (:name %)) "`") fields)))]
    (str (when (:instructions module) (str (:instructions module) "\n\n"))
         "You are an Agent solving a task over multiple turns. You are given "
         (field-list (:inputs module))
         " and the trajectory of your work so far. Use the tools below to gather"
         " or change whatever is needed to produce " (field-list (:outputs module))
         ".\n\n"
         "Each turn, produce next_thought (your reasoning), next_tool_name, and"
         " next_tool_args. After each call, its observation is appended to your"
         " trajectory. Call the `finish` tool once you have everything needed to"
         " produce the output fields.\n\n"
         "next_tool_name must be exactly one of:\n"
         (str/join "\n" (map-indexed format-tool tools)))))

(defn- format-trajectory
  "Render accumulated STEPS into the trajectory string fed back to the agent."
  [steps]
  (if (empty? steps)
    "(no steps yet)"
    (str/join "\n\n"
              (apply concat
                     (map-indexed
                      (fn [idx {:keys [thought tool-name tool-args observation]}]
                        [(str "[[ ## thought_" idx " ## ]]\n" thought)
                         (str "[[ ## tool_name_" idx " ## ]]\n" tool-name)
                         (str "[[ ## tool_args_" idx " ## ]]\n"
                              (json/generate-string tool-args))
                         (str "[[ ## observation_" idx " ## ]]\n" observation)])
                      steps)))))

(def ^:private finish-tool
  {:name "finish"
   :description (str "Signal that all information needed to produce the output"
                     " fields is now available.")
   :args []
   :handler (constantly "Completed.")})

(defn- step-module
  "Internal per-turn module: module inputs + trajectory ->
  next_thought, next_tool_name, next_tool_args."
  [module tools]
  {:inputs (conj (vec (:inputs module)) trajectory-field)
   :outputs [{:name :next_thought
              :spec :string
              :description "Your reasoning about the situation and your next step."}
             {:name :next_tool_name
              :spec :string
              :description (str "The next tool to call, exactly one of: "
                                (str/join ", " (map :name tools)) ".")}
             {:name :next_tool_args
              ;; vector-form :map => rendered/parsed as a JSON object
              :spec [:map]
              :description "A JSON object of arguments for the chosen tool."}]
   :instructions (react-instructions module tools)})

(defn- extract-module
  "Internal final module: module inputs + trajectory -> the module's own
  declared outputs."
  [module]
  (assoc module :inputs (conj (vec (:inputs module)) trajectory-field)))

(defn react
  "Run a ReAct agent loop: the LLM interleaves reasoning and tool calls until
  it calls `finish` (or MAX-ITERS turns elapse), then a final extraction turns
  the trajectory into MODULE's declared outputs. Built on `predict`, so no
  native LLM tool-calling is required.

  Parameters:
  - provider-config: registered keyword or ad-hoc provider map (see `predict`)
  - module: a standard module; its :outputs are produced by the final extraction
  - input-map: map of MODULE input field names to values
  - tools: a vector of tool maps, each:
      {:name        \"create_note\"   ; string the model selects by
       :description \"...\"            ; what it does / when to use it
       :args        [{:name :title :spec :string :description \"...\"} ...] ; optional
       :handler     (fn [args-map] \"observation string\")}
    args-map keys are keywords parsed from the model's JSON. The handler's
    return value becomes the observation shown to the model; a thrown exception
    is caught and its message becomes the observation, so the agent can recover.
  - options (optional):
      :max-iters  maximum tool-calling turns (default 20)
      :on-step    fn called after each turn with
                  {:iteration n :thought s :tool-name s :tool-args m :observation s}
      :validate?  validate the FINAL extracted outputs (default true)
      :retries    re-prompt the final extraction on validation failure (default 0)
      plus any LLM options forwarded to the provider.

  Returns the extracted output map merged with:
    :trajectory  the full trajectory string
    :iterations  number of turns taken (the finishing turn included)
    :stopped     :finished (agent called finish) or :max-iters (budget exhausted)"
  [provider-config module input-map tools & [options]]
  (let [max-iters (get options :max-iters 20)
        on-step (get options :on-step)
        all-tools (conj (vec tools) finish-tool)
        by-name (into {} (map (juxt :name identity)) all-tools)
        smodule (step-module module all-tools)
        emodule (extract-module module)
        ;; The control fields are free-form; only the final extraction is
        ;; Malli-validated. Strip react-only keys before they reach the LLM.
        step-opts (assoc (dissoc options :max-iters :on-step :retries)
                         :validate? false)
        extract-opts (dissoc options :max-iters :on-step)
        ;; DSPy extracts the final answer with ChainOfThought, not plain
        ;; Predict: the per-turn next_thought already carries reasoning, but
        ;; the final extraction reasons once more over the whole trajectory.
        finish (fn [steps stopped]
                 (let [traj (format-trajectory steps)
                       extracted (cot/chain-of-thought provider-config emodule
                                                       (assoc input-map :trajectory traj)
                                                       extract-opts)]
                   (merge extracted {:trajectory traj
                                     :iterations (count steps)
                                     :stopped stopped})))]
    (loop [steps []
           idx 0]
      (if (>= idx max-iters)
        (finish steps :max-iters)
        (let [pred (predict/predict provider-config smodule
                                    (assoc input-map :trajectory (format-trajectory steps))
                                    step-opts)
              tool-name (:next_tool_name pred)
              tool-args (let [a (:next_tool_args pred)] (if (map? a) a {}))
              tool (get by-name tool-name)
              observation (if (nil? tool)
                            (str "Unknown tool: " tool-name ". Choose one of: "
                                 (str/join ", " (map :name all-tools)) ".")
                            (try ((:handler tool) tool-args)
                                 (catch Exception e
                                   (str "Execution error in " tool-name ": "
                                        (.getMessage e)))))
              step {:iteration idx
                    :thought (:next_thought pred)
                    :tool-name tool-name
                    :tool-args tool-args
                    :observation observation}
              steps' (conj steps step)]
          (when on-step (on-step step))
          (if (= tool-name "finish")
            (finish steps' :finished)
            (recur steps' (inc idx))))))))
