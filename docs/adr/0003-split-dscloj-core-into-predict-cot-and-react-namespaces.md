# 3. Split dscloj.core into predict, cot, and react namespaces

Date: 2026-06-14

[<- Prev](0002-source-litellm-clj-from-a-git-sha.md) | [Next ->](0004-add-a-react-agent-module-built-on-predict.md)

## Status

Accepted

## Context

DSCloj began as a single namespace, `dscloj.core`, holding provider management,
spec/prompt/parsing helpers, and `predict`/`predict-stream`. Adding more DSPy
modules (Chain-of-Thought, ReAct, and others to come) to one file would make it
grow without bound and would blur the dependency relationships between modules.
DSPy itself is organized as small modules under `dspy.predict.*` with a
top-level `dspy` package that re-exports them. We want the same shape, provided
it introduces no cyclic namespace dependencies.

## Decision

We will split the implementation into focused namespaces along DSPy's module
boundaries:

- `dscloj.predict` — the base layer: provider management, Malli/spec helpers,
  prompt generation, output parsing, and `predict`/`predict-stream`.
- `dscloj.cot` — Chain-of-Thought, depending on `dscloj.predict`.
- `dscloj.react` — the ReAct agent loop, depending on `dscloj.predict` and
  `dscloj.cot`.

`dscloj.core` becomes a thin aggregating facade — the analogue of DSPy's
top-level `dspy` package — that requires the three namespaces and re-exports
their public vars via an `import-vars` macro, so `(require '[dscloj.core])`
still yields the whole API and existing code keeps working.

The dependency graph is a DAG (`predict <- cot <- react`, and `predict <-
react`, with `core` depending on all three), so there are no cycles.

## Consequences

- Each module is small and its dependencies are explicit; new DSPy modules slot
  in as new namespaces without enlarging existing files.
- The public API and existing requires (`dscloj.core`) are preserved, so the
  README, examples, and downstream consumers are unaffected.
- The facade relies on a runtime `import-vars` macro that copies `:doc` and
  `:arglists`, so REPL/editor introspection on `dscloj.core/...` stays
  full-fidelity. clj-kondo cannot see through that macro, so a small project
  hook (`.clj-kondo/hooks`) teaches it that the macro expands to aliasing
  `def`s; without that hook static analysis would report false unresolved-var
  errors.
- Tests that reach private helpers must target the owning namespace (e.g.
  `#'dscloj.react/step-module`) rather than `dscloj.core`.
