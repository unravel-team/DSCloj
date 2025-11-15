# DSCloj

A Clojure library inspired by [DSPy](https://github.com/stanfordnlp/dspy), providing a declarative approach to building and optimizing language model pipelines.

DSCloj leverages [litellm-clj](https://github.com/unravel-team/litellm-clj) to provide a unified interface for working with various LLM providers while bringing DSPy's powerful programming model to the Clojure ecosystem.

[![Clojars Project](https://img.shields.io/clojars/v/tech.unravel/dscloj.svg)](https://clojars.org/tech.unravel/dscloj)
[![cljdoc badge](https://cljdoc.org/badge/tech.unravel/dscloj)](https://cljdoc.org/d/tech.unravel/dscloj)
[![Lint Status](https://github.com/unravel-team/DSCloj/actions/workflows/lint.yml/badge.svg)](https://github.com/unravel-team/DSCloj/actions)
[![Test Status](https://github.com/unravel-team/DSCloj/actions/workflows/test.yml/badge.svg)](https://github.com/unravel-team/DSCloj/actions)

## Introduction

DSCloj brings the power of declarative LLM programming to Clojure. Inspired by Stanford's DSPy framework.

## Quickstart Guide

### Installation

Add DSCloj to your `deps.edn`:

```clojure
{:deps {tech.unravel/dscloj {:mvn/version "0.1.0-alpha.1"}}}
```

### Basic Usage

DSCloj works by defining **modules** - declarative specifications of LLM tasks with typed inputs and outputs.

```clojure
(require '[dscloj.core :as dscloj])

;; 1. Define a module with Malli specs
(def qa-module
  {:inputs [{:name :question
             :spec :string
             :description "The question to answer"}]
   :outputs [{:name :answer
              :spec :string
              :description "The answer to the question"}]
   :instructions "Provide concise and accurate answers."})

;; 2. Register a provider (one-time setup)
(dscloj/register-provider! :gpt4 
  {:provider :openai 
   :model "gpt-4" 
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

;; Or use quick-setup to register common providers from environment
(dscloj/quick-setup!)  ; Registers :openai, :anthropic, :gemini, etc.

;; 3. Use the module with predict
(def result (dscloj/predict :gpt4 qa-module 
                            {:question "What is the capital of France?"}
                            :gpt4))  ; provider-config

;; 4. Access the structured output
(:answer result)
;; => "Paris"
```

### Key Concepts

**Modules** are maps with:
- `:inputs` - Vector of input field definitions
- `:outputs` - Vector of output field definitions
- `:instructions` - Optional string describing the task instructions, rules, and examples

**Fields** are maps with:
- `:name` - Keyword identifier
- `:spec` - Malli spec (e.g., `:string`, `:int`, `:double`, `:boolean`, or more complex specs like `[:int {:min 0 :max 100}]`)
- `:description` - Human-readable description

**Provider Management:**
- `register-provider!` - Register named provider configurations
- `quick-setup!` - Auto-register providers from environment variables
- `list-providers` - List all registered providers
- `get-provider` - Retrieve a specific provider config

The `predict` function:
1. Validates input data against Malli specs
2. Generates a prompt from the module specification
3. Injects your input values
4. Calls the LLM via router API
5. Parses and type-converts the output
6. Validates output data against Malli specs

### Provider Configuration

DSCloj uses litellm-clj's router API for flexible provider management:

```clojure
;; Option 1: Register providers (recommended)
(dscloj/register-provider! :gpt4 
  {:provider :openai 
   :model "gpt-4" 
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

(dscloj/register-provider! :claude 
  {:provider :anthropic 
   :model "claude-3-5-sonnet-20241022" 
   :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}})

;; Use registered providers
(dscloj/predict :gpt4 qa-module {:question "..."} :gpt4)
(dscloj/predict :gpt4 qa-module {:question "..."} :claude)

;; Option 2: Quick setup from environment
(dscloj/quick-setup!)  ; Reads OPENAI_API_KEY, ANTHROPIC_API_KEY, etc.

;; Option 3: Ad-hoc provider (no registration)
(dscloj/predict :gpt4 qa-module 
                {:question "..."}
                {:provider :openai 
                 :model "gpt-4" 
                 :config {:api-key (System/getenv "OPENAI_API_KEY")}})
```

### Malli Spec Support

DSCloj uses [Malli](https://github.com/metosin/malli) specs for defining field types with automatic validation:

```clojure
;; Simple specs
(def qa-module
  {:inputs [{:name :question
             :spec :string
             :description "The question to answer"}]
   :outputs [{:name :answer
              :spec :string
              :description "The answer"}]
   :instructions "Provide concise and accurate answers."})

;; Complex specs with constraints
(def constrained-module
  {:inputs [{:name :age
             :spec [:int {:min 0 :max 150}]}
            {:name :email
             :spec [:string {:pattern #"^[^@]+@[^@]+$"}]}]
   :outputs [{:name :message
              :spec :string}]})

;; Reusable specs
(def QuestionSpec [:string {:min 1 :description "The question"}])
(def AnswerSpec [:string {:min 1 :description "The answer"}])

(def module-with-reusable-specs
  {:inputs [{:name :question :spec QuestionSpec}]
   :outputs [{:name :answer :spec AnswerSpec}]})

;; Setup provider
(dscloj/register-provider! :gpt4 
  {:provider :openai :model "gpt-4" 
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

;; Invalid inputs/outputs are automatically validated
(dscloj/predict :gpt4 qa-module 
               {:question 123}  ; Throws validation error - should be string
               :gpt4)

;; Disable validation if needed
(dscloj/predict :gpt4 qa-module 
               {:question "..."}
               :gpt4
               {:validate? false})  ; Skip validation (4th argument for options)
```

### Streaming Support

DSCloj supports **streaming structured output** with progressive parsing and validation:

```clojure
(require '[dscloj.core :as dscloj]
         '[clojure.core.async :refer [go-loop <!]])

;; Define a module with structured outputs
(def whales-module
  {:inputs [{:name :query :spec :string}]
   :outputs [{:name :markdown_output :spec :string}]
   :instructions "Generate whale species information in markdown table format."})

;; Register provider
(dscloj/register-provider! :gpt4 
  {:provider :openai 
   :model "gpt-4" 
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

;; Stream predictions
(let [stream-ch (dscloj/predict-stream 
                  whales-module
                  {:query "Tell me about 3 whale species."}
                  :gpt4  ; provider-config
                  {:debounce-ms 100})]  ; options
  
  ;; Consume the stream progressively
  (go-loop []
    (when-let [parsed (<! stream-ch)]
      (println "Received update:" parsed)
      (recur))))
```

**Streaming Features:**
- Progressive parsing as tokens arrive
- Malli validation on final output (optional during stream)
- Debouncing to control emission rate
- core.async channels for composability
- Optional callbacks for chunk processing

See [`examples/streaming_whales.clj`](examples/streaming_whales.clj) for a complete example inspired by [Pydantic AI's streaming example](https://ai.pydantic.dev/examples/stream-whales/).

### Switching Between Providers

One of the key benefits of the router API is easy provider switching:

```clojure
;; Register multiple providers
(dscloj/register-provider! :simple-llm 
  {:provider :openai :model "gpt-4" 
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

(def openai-result (dscloj/predict :simple-llm qa-module {:question "What is AI?"}))

(dscloj/register-provider! :simple-llm
  {:provider :anthropic :model "claude-haiku-4-5" 
   :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}})

(def anthropic-result (dscloj/predict :simple-llm qa-module {:question "What is AI?"}))

;; Compare results
(println "OpenAI:" (:answer openai-result))
(println "Anthropic:" (:answer anthropic-result))

```

### More Examples

See [`examples/basic_usage.clj`](examples/basic_usage.clj) for:
- Provider registration and management
- Simple Q&A modules
- Translation with custom LLM options
- Multiple output types (bool, float, str)
- Complex Malli specs with constraints
- Validation and error handling
- Spec reusability
- Disabling validation
- Inspecting generated prompts
- Switching between providers

See [`examples/streaming_whales.clj`](examples/streaming_whales.clj) for:
- Streaming structured output
- Progressive parsing with Malli specs
- Real-time data display
- Handling optional fields during streaming
- core.async channel usage

### Supported LLM Providers

DSCloj uses [litellm-clj](https://github.com/unravel-team/litellm-clj) and supports:
- **OpenAI** - GPT-3.5, GPT-4, GPT-4o
- **Anthropic** - Claude 3.5 (Sonnet, Haiku), Claude 3 (Opus)
- **Google** - Gemini Pro, Gemini Pro Vision
- **Mistral** - Mistral Small/Medium/Large, Codestral
- **OpenRouter** - All OpenRouter models
- **Ollama** - Local models
- And many more...

## License

This project is licensed under the MIT License - see the LICENSE file for details.

---

## Acknowledgments

- [DSPy](https://github.com/stanfordnlp/dspy) - The original Python library that inspired this port
- [litellm-clj](https://github.com/unravel-team/litellm-clj) - Unified LLM interface for Clojure
