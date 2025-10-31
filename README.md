# DSCloj

A Clojure library inspired by [DSPy](https://github.com/stanfordnlp/dspy), providing a declarative approach to building and optimizing language model pipelines.

DSCloj leverages [litellm-clj](https://github.com/unravel-team/litellm-clj) to provide a unified interface for working with various LLM providers while bringing DSPy's powerful programming model to the Clojure ecosystem.

[![Clojars Project](https://img.shields.io/clojars/v/io.unravel/dscloj.svg)](https://clojars.org/io.unravel/dscloj)
[![cljdoc badge](https://cljdoc.org/badge/io.unravel/dscloj)](https://cljdoc.org/d/io.unravel/dscloj)
[![Lint Status](https://github.com/unravel-team/DSCloj/workflows/lint/badge.svg)](https://github.com/unravel-team/DSCloj/actions)
[![Test Status](https://github.com/unravel-team/DSCloj/workflows/test/badge.svg)](https://github.com/unravel-team/DSCloj/actions)

## Introduction

DSCloj brings the power of declarative LLM programming to Clojure. Inspired by Stanford's DSPy framework.

## Quickstart Guide

### Installation

Add DSCloj to your `deps.edn`:

```clojure
{:deps {io.unravel/dscloj {:mvn/version "0.1.0"}}}
```

### Basic Usage

DSCloj works by defining **modules** - declarative specifications of LLM tasks with typed inputs and outputs.

```clojure
(require '[dscloj.core :as dscloj])

;; 1. Define a module
(def qa-module
  {:inputs [{:name :question
             :type "str"
             :description "The question to answer"}]
   :outputs [{:name :answer
              :type "str"
              :description "The answer to the question"}]
   :instructions "Provide concise and accurate answers."})

;; 2. Use the module with predict
(def result (dscloj/predict qa-module 
                            {:question "What is the capital of France?"}
                            {:model "gpt-4"
                             :api-key (System/getenv "OPENAI_API_KEY")}))

;; 3. Access the structured output
(:answer result)
;; => "Paris"
```

### Key Concepts

**Modules** are maps with either:
- **Field Vectors** (traditional):
  - `:inputs` - Vector of input field definitions
  - `:outputs` - Vector of output field definitions
- **Malli Schemas** (recommended):
  - `:input-schema` - Malli schema defining input structure
  - `:output-schema` - Malli schema defining output structure
- `:instructions` - Optional string describing the task instructions, rules, and examples

**Fields** (when using field vectors) are maps with:
- `:name` - Keyword identifier
- `:type` - String type ("str", "int", "float", "bool")
- `:description` - Human-readable description

The `predict` function:
1. Validates input data (if using Malli schemas)
2. Generates a prompt from the module specification
3. Injects your input values
4. Calls the LLM
5. Parses and type-converts the output
6. Validates output data (if using Malli schemas)

### Malli Schema Support

DSCloj supports [Malli](https://github.com/metosin/malli) schemas for defining module inputs and outputs with automatic validation:

```clojure
(require '[malli.core :as m])

;; Define a module with Malli schemas
(def qa-module-malli
  {:input-schema [:map
                  [:question [:string {:description "The question to answer"}]]]
   :output-schema [:map
                   [:answer [:string {:description "The answer"}]]]
   :instructions "Provide concise and accurate answers."})

;; Use it just like traditional modules
(def result (dscloj/predict qa-module-malli 
                            {:question "What is the capital of France?"}
                            {:model "gpt-4"
                             :api-key (System/getenv "OPENAI_API_KEY")}))

;; Invalid inputs/outputs are automatically validated
(dscloj/predict qa-module-malli 
               {:question 123}  ; Throws validation error - should be string
               {:model "gpt-4"})
```

**Benefits of Malli Schemas:**
- Type safety with automatic validation
- Reusable schema definitions
- Detailed error messages for debugging
- Better IDE support and autocomplete
- Backward compatible with field vectors

### More Examples

See the [`examples/`](examples/) directory for:
- **basic_usage.clj**: Simple Q&A modules, financial comparison with instructions and rules, translation with custom LLM options, multiple output types (bool, float, str), inspecting generated prompts
- **malli_usage.clj**: Malli schema definitions, validation examples, schema reusability, error handling, backward compatibility

### Supported LLM Providers

DSCloj uses [litellm-clj](https://github.com/unravel-team/litellm-clj) and supports:
- OpenAI (GPT-3.5, GPT-4)
- Anthropic (Claude)
- Google (PaLM, Gemini)
- Azure OpenAI
- And many more...

## License

This project is licensed under the MIT License - see the LICENSE file for details.

---

## Acknowledgments

- [LiteLLM](https://github.com/stanfordnlp/dspy) - The original Python library that inspired this port
