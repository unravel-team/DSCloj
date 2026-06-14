# 4. Add a ReAct agent module built on predict

Date: 2026-06-14

[<- Prev](0003-split-dscloj-core-into-predict-cot-and-react-namespaces.md) | [Next ->](0005-mirror-dspy-module-semantics-including-chainofthought-extraction-in-react.md)

## Status

Accepted

## Context

`predict` answers a single typed question: given a signature and inputs,
produce validated outputs in one round-trip. Real tasks often need the model to
take actions across several turns — search, read, create, update — before it can
produce a final answer. That is the tool-using agent pattern, which DSPy
provides as `dspy.ReAct`. The first consumer driving this need is a wiki
maintenance tool whose work is inherently agentic (the model calls tools that
create and update notes), and which cannot be expressed as a single `predict`.

A native-tool-calling approach would couple DSCloj to each provider's
tool-calling wire format. DSPy avoids this: its ReAct is built entirely on
`Predict`, emitting the next thought, tool name, and tool arguments as ordinary
structured text fields, running the tool, and appending an observation to a
trajectory.

## Decision

We will add `dscloj.react/react`, a ReAct loop built on `predict` with no
native LLM tool-calling. Each turn is a `predict` over an internal step-module
whose outputs are `next_thought`, `next_tool_name`, and `next_tool_args` (a
JSON object). The named tool's handler runs, its return value becomes an
observation appended to a growing trajectory string, and the loop repeats until
the model selects the built-in `finish` tool or the `:max-iters` budget is
exhausted. A final extraction turns the trajectory into the module's declared
outputs.

Tools are plain Clojure maps — `{:name :description :args :handler}` — where the
handler is an arbitrary function receiving keyword-keyed args parsed from the
model's JSON and returning an observation string. A thrown handler exception is
caught and its message becomes the observation, so the agent can recover.

## Consequences

- ReAct works on every provider `predict` supports, with no per-provider
  tool-calling code.
- Because tool handlers are ordinary functions, any invariant a caller needs to
  enforce (validation, authorization, path containment) lives in the handler,
  not in the model's hands — the model never gets direct authority over side
  effects.
- The loop exposes what consumers need to build on it: a per-turn `:on-step`
  callback for progress, a `:stopped` value distinguishing `:finished` from
  `:max-iters`, and the full `:trajectory` string for resumption or handoff.
- This is a deliberately minimal port. It omits DSPy features not yet needed:
  trajectory truncation on context-window overflow, async execution, streaming
  ReAct, and a typed Tool/argument-validation layer. These can be added later
  without changing the public shape.
