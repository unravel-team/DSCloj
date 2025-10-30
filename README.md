# DSClj

A Clojure library inspired by [DSPy](https://github.com/stanfordnlp/dspy), providing a declarative approach to building and optimizing language model pipelines.

DSClj leverages [litellm-clj](https://github.com/unravel-team/litellm-clj) to provide a unified interface for working with various LLM providers while bringing DSPy's powerful programming model to the Clojure ecosystem.

[![Clojars Project](https://img.shields.io/clojars/v/io.unravel/dsclj.svg)](https://clojars.org/io.unravel/dsclj)
[![cljdoc badge](https://cljdoc.org/badge/io.unravel/dsclj)](https://cljdoc.org/d/io.unravel/dsclj)
[![Lint Status](https://github.com/unravel-team/DSClj/workflows/lint/badge.svg)](https://github.com/unravel-team/DSClj/actions)
[![Test Status](https://github.com/unravel-team/DSClj/workflows/test/badge.svg)](https://github.com/unravel-team/DSClj/actions)

## Introduction

DSClj brings the power of declarative LLM programming to Clojure. Inspired by Stanford's DSPy framework, it enables you to:

- **Compose LLM operations** declaratively using functional programming patterns
- **Optimize prompts and pipelines** automatically based on validation metrics
- **Work with any LLM provider** through the unified litellm-clj interface
- **Build maintainable AI applications** with testable, modular components

Whether you're building RAG systems, agents, or complex LLM pipelines, DSClj provides the abstractions and tools to make your code more robust and maintainable.
