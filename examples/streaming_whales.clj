(ns examples.streaming-whales
  "Information about whales — an example of streamed structured response validation.
  
  This script streams structured responses from GPT-4 about whales, validates the data
  and displays it progressively as the data is received.
  
  Run with:
    clj -M -m examples.streaming-whales"
  (:require [dscloj.core :as dscloj]
            [clojure.core.async :refer [go-loop <!]]
            [clojure.pprint :as pp]))

;; =============================================================================
;; Whale Species Module with Malli Specs
;; =============================================================================

;; Define the Malli spec for a single whale
(def WhaleSpec
  [:map
   [:name :string]
   [:length [:double {:description "Average length of an adult whale in meters."}]]
   [:weight {:optional true}
    [:double {:min 50 :description "Average weight of an adult whale in kilograms."}]]
   [:ocean {:optional true} :string]
   [:description {:optional true} :string]])

;; Module that outputs information about whale species as markdown
(def whales-module
  {:inputs [{:name :query
             :spec :string
             :description "Request for whale species information"}]
   :outputs [{:name :markdown_output
              :spec :string
              :description "Markdown formatted table with whale species information. Each row should contain: ID, Name, Length (m), Weight (kg), Ocean, Description"}]
   :instructions "Generate detailed information about whale species in a markdown table format. 
   
   Use this exact format:
   
   | ID | Name | Length (m) | Weight (kg) | Ocean | Description |
   |---|---|---|---|---|---|
   | 1 | Blue Whale | 25.0 | 150000 | All oceans | The largest animal on Earth |
   | 2 | Humpback Whale | 15.0 | 40000 | All oceans | Known for complex songs |
   
   Provide accurate scientific data for length (in meters) and weight (in kilograms)."})

;; =============================================================================
;; Display Helpers
;; =============================================================================

(defn display-markdown
  "Display markdown output with nice formatting."
  [markdown]
  (println "\n" (clojure.string/join (repeat 80 "=")))
  (println "📊 WHALE SPECIES TABLE (Markdown)")
  (println (clojure.string/join (repeat 80 "=")))
  (println)
  (println markdown)
  (println)
  (println (clojure.string/join (repeat 80 "=")))
  (println "Streaming Structured responses from GPT-4")
  (println (clojure.string/join (repeat 80 "="))))

;; =============================================================================
;; Main Streaming Example
;; =============================================================================

(defn -main
  "Run the streaming whales example."
  [& args]
  (println "\n🐋 Streaming Whale Species Information 🐋")
  (println "Requesting data from GPT-4...\n")
  
  ;; Check for API key
  (when-not (System/getenv "OPENAI_API_KEY")
    (println "ERROR: OPENAI_API_KEY environment variable not set!")
    (System/exit 1))
  
  ;; Start streaming
  (let [stream-ch (dscloj/predict-stream
                   whales-module
                   {:query "Generate me details of 5 species of Whale."}
                   {:model "gpt-4"
                    :api-key (System/getenv "OPENAI_API_KEY")
                    :debounce-ms 100
                    :validate? false})]
    
    ;; Consume the stream and display progressively
    (go-loop [last-markdown nil]
      (if-let [parsed (<! stream-ch)]
        (let [current-markdown (:markdown_output parsed)]
          ;; Only redisplay if markdown changed
          (when (and current-markdown (not= current-markdown last-markdown))
            (print (str "\033[H\033[2J")) ; Clear screen (ANSI escape code)
            (println "\n🐋 Streaming Whale Species Information 🐋")
            (println "Receiving data...\n")
            (display-markdown current-markdown)
            (flush))
          (recur current-markdown))
        ;; Stream complete
        (do
          (println "\n✅ Stream complete!")
          (System/exit 0))))
    
    ;; Keep main thread alive
    (Thread/sleep 60000)))

;; =============================================================================
;; REPL Helpers (requires markdown-clj in dev deps)
;; =============================================================================

(comment
  ;; Pretty print markdown in REPL using markdown-clj
  (require '[markdown.core :as md])
  
  (defn print-md
    "Pretty print markdown string with ANSI colors in terminal."
    [markdown-str]
    (println "\n" (clojure.string/join (repeat 80 "=")))
    (println "📊 MARKDOWN OUTPUT")
    (println (clojure.string/join (repeat 80 "=")))
    (println)
    (println markdown-str)
    (println)
    (println (clojure.string/join (repeat 80 "="))))
  
  ;; Run the example
  (-main)
  
  ;; Test with simple output and pretty print
  (let [stream-ch (dscloj/predict-stream
                   whales-module
                   {:query "Generate me details of 5 species of Whale."}
                   {:model "gpt-4"
                    :api-key (System/getenv "OPENAI_API_KEY")
                    :debounce-ms 200
                    :validate? false})]
    (go-loop []
      (when-let [parsed (<! stream-ch)]
        (println "\n--- Received Update ---")
        (when-let [md (:markdown_output parsed)]
          (print-md md))
        (recur))))
  
  ;; Convert markdown to HTML for viewing in browser
  (defn md->html
    "Convert markdown to HTML (requires markdown-clj)."
    [markdown-str]
    (md/md-to-html-string markdown-str))
  
  ;; Example: Convert and view markdown as HTML
  (let [sample-md "| ID | Name | Length (m) |\n|---|---|---|\n| 1 | Blue Whale | 25.0 |"]
    (println (md->html sample-md)))
  )
