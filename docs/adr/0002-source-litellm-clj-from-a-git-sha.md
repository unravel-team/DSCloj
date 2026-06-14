# 2. Source litellm-clj from a Git SHA

Date: 2026-06-14

[<- Prev](0001-record-architecture-decisions.md) | [Next ->](0003-split-dscloj-core-into-predict-cot-and-react-namespaces.md)

## Status

Accepted

## Context

DSCloj depends on litellm-clj for all provider access. The published Clojars
release (`0.3.0-alpha`) lags behind the library's `main` branch, which carries
two fixes DSCloj benefits from directly: the list-vs-vector response-shape fix
(so indexed access into `:choices`/tool-calls is reliable) and the elimination
of reflective calls in streaming and error paths (which matters for any
consumer that compiles to a GraalVM native image). Waiting for a tagged release
would block DSCloj from those fixes for an unknown period, and both libraries
are developed together in the same organization.

## Decision

We will depend on litellm-clj as a Git dependency pinned to a specific `main`
commit (`:git/url` + `:git/sha`) rather than a Clojars `:mvn/version`. The SHA
is recorded explicitly in `deps.edn` with a comment explaining why, and is
bumped deliberately when we want newer upstream changes.

## Consequences

- DSCloj tracks litellm-clj fixes as soon as they land on `main`, without
  waiting for a release.
- The dependency is content-addressed and reproducible: the SHA pins an exact
  source tree, so every machine and CI run resolves identical code.
- Builds now require network access to GitHub on first resolution (cached
  afterward in `~/.gitlibs`), and DSCloj cannot itself be published to Clojars
  while it carries a Git dependency.
- The pin is a moving target by intent: it must be advanced by hand, and should
  be returned to a `:mvn/version` once litellm-clj cuts a release that includes
  the needed fixes.
