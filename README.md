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

**Modules** are maps with:
- `:inputs` - Vector of input field definitions
- `:outputs` - Vector of output field definitions  
- `:instructions` - Optional string describing the task instructions, rules, and examples

**Fields** are maps with:
- `:name` - Keyword identifier
- `:type` - String type ("str", "int", "float", "bool")
- `:description` - Human-readable description

The `predict` function:
1. Generates a prompt from the module specification
2. Injects your input values
3. Calls the LLM
4. Parses and type-converts the output

### More Examples

See the [`examples/`](examples/) directory for:
- Simple Q&A modules
- Financial comparison with instructions and rules
- Translation with custom LLM options
- Multiple output types (bool, float, str)
- Inspecting generated prompts

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
