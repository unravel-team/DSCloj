# 5. Mirror DSPy module semantics including ChainOfThought extraction in ReAct

Date: 2026-06-14

[<- Prev](0004-add-a-react-agent-module-built-on-predict.md)

## Status

Accepted

## Context

DSCloj is a port of DSPy. As we add modules, we repeatedly face a choice
between two principles: implement what the current consumer strictly needs, or
preserve DSPy's semantics even where a detail has no immediate payoff. A
concrete instance arose with ReAct's final extraction. DSPy's `ReAct` extracts
the final answer with `ChainOfThought` (reasoning prepended), not a plain
`Predict`, even though ReAct's per-turn `next_thought` already carries
reasoning — so the extra reasoning step looks redundant in isolation.

## Decision

We will keep DSCloj's module semantics aligned with DSPy even when a detail is
not strictly necessary for current requirements, unless there is a concrete
reason to diverge (which we will then record as its own decision). Applying this
now: `dscloj.react`'s final extraction uses `dscloj.cot/chain-of-thought`, not
`predict`, mirroring DSPy's `dspy.ChainOfThought(fallback_signature)`. This also
motivated implementing `dscloj.cot` as a faithful port (a `reasoning` field
prepended to the outputs, then `predict`).

## Consequences

- DSCloj stays a recognizable, predictable port: readers who know DSPy can
  reason about DSCloj's behavior, and DSPy's documentation and intuitions
  transfer.
- Optimizers and patterns from the DSPy ecosystem are more likely to map cleanly
  onto DSCloj as it grows.
- We accept some cost that pure local minimalism would avoid: the ReAct
  extraction makes the model emit a `reasoning` field and the result map carries
  an extra `:reasoning` key. This is a deliberate, low cost paid for fidelity.
- Future divergences from DSPy are allowed but must be justified and recorded,
  so drift is intentional rather than accidental.
