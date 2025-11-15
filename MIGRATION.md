# Migration Guide: Router API

This guide helps you migrate from DSCloj `0.1.0-alpha.1` to the new router API.

## Overview

DSCloj has migrated from using `litellm.core` to `litellm.router` for better provider management and runtime model switching. This is a **breaking change** that requires updating your code.

## What Changed

### Old API (0.1.0-alpha.1)

```clojure
(require '[dscloj.core :as dscloj])

;; Model and API key passed as options
(dscloj/predict :gpt4 qa-module 
                {:question "What is the capital of France?"}
                {:model "gpt-4"
                 :api-key (System/getenv "OPENAI_API_KEY")
                 :temperature 0.7})
```

### New API (Router-based)

```clojure
(require '[dscloj.core :as dscloj])

;; Option 1: Register provider first (recommended)
(dscloj/register-provider! :gpt4 
  {:provider :openai 
   :model "gpt-4" 
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

(dscloj/predict :gpt4 qa-module 
                {:question "What is the capital of France?"}
                :gpt4  ; provider-config (3rd argument)
                {:temperature 0.7})  ; options (4th argument)

;; Option 2: Ad-hoc provider (no registration)
(dscloj/predict :gpt4 qa-module 
                {:question "What is the capital of France?"}
                {:provider :openai 
                 :model "gpt-4" 
                 :config {:api-key (System/getenv "OPENAI_API_KEY")}}
                {:temperature 0.7})
```

## Breaking Changes

### 1. `predict` Function Signature

**Old:**
```clojure
(predict module input-map options)
```

**New:**
```clojure
(predict module input-map provider-config & [options])
```

**Changes:**
- 3rd argument is now `provider-config` (keyword or map)
- Options like `:temperature`, `:max-tokens` moved to 4th argument
- `:model` and `:api-key` are now part of `provider-config`, not options

### 2. `predict-stream` Function Signature

**Old:**
```clojure
(predict-stream module input-map options)
```

**New:**
```clojure
(predict-stream module input-map provider-config & [options])
```

**Changes:**
- Same as `predict` - provider-config is now 3rd argument
- Options moved to 4th argument

## Migration Steps

### Step 1: Choose Your Approach

You have three options for provider configuration:

#### Option A: Register Providers (Recommended)

Best for production code with multiple providers.

```clojure
;; One-time setup (e.g., in your app initialization)
(dscloj/register-provider! :gpt4 
  {:provider :openai 
   :model "gpt-4" 
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

(dscloj/register-provider! :claude 
  {:provider :anthropic 
   :model "claude-3-5-sonnet-20241022" 
   :config {:api-key (System/getenv "ANTHROPIC_API_KEY")}})

;; Use throughout your app
(dscloj/predict :gpt4 qa-module input :gpt4)
(dscloj/predict :gpt4 qa-module input :claude)
```

#### Option B: Quick Setup

Best for quick prototyping with environment variables.

```clojure
;; One-time setup - reads from environment variables
(dscloj/quick-setup!)

;; Automatically registers :openai, :anthropic, :gemini, etc.
(dscloj/predict :gpt4 qa-module input :openai)
```

#### Option C: Ad-hoc Providers

Best for one-off usage or testing.

```clojure
;; No setup needed - pass provider config directly
(dscloj/predict :gpt4 qa-module 
                input
                {:provider :openai 
                 :model "gpt-4" 
                 :config {:api-key (System/getenv "OPENAI_API_KEY")}})
```

### Step 2: Update Your Code

#### Before (Old API):

```clojure
(ns my-app.core
  (:require [dscloj.core :as dscloj]))

(defn answer-question [question]
  (dscloj/predict :gpt4 qa-module 
                  {:question question}
                  {:model "gpt-4"
                   :api-key (System/getenv "OPENAI_API_KEY")
                   :temperature 0.7
                   :max-tokens 100}))
```

#### After (New API):

```clojure
(ns my-app.core
  (:require [dscloj.core :as dscloj]))

;; Option 1: With registered provider
(dscloj/register-provider! :gpt4 
  {:provider :openai 
   :model "gpt-4" 
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

(defn answer-question [question]
  (dscloj/predict :gpt4 qa-module 
                  {:question question}
                  :gpt4  ; provider-config
                  {:temperature 0.7  ; options
                   :max-tokens 100}))

;; Option 2: With ad-hoc provider
(defn answer-question [question]
  (dscloj/predict :gpt4 qa-module 
                  {:question question}
                  {:provider :openai 
                   :model "gpt-4" 
                   :config {:api-key (System/getenv "OPENAI_API_KEY")}}
                  {:temperature 0.7
                   :max-tokens 100}))
```

### Step 3: Update Streaming Code

#### Before:

```clojure
(let [stream-ch (dscloj/predict-stream 
                  whales-module
                  {:query "Tell me about whales"}
                  {:model "gpt-4"
                   :api-key (System/getenv "OPENAI_API_KEY")
                   :debounce-ms 100})]
  (go-loop []
    (when-let [result (<! stream-ch)]
      (println result)
      (recur))))
```

#### After:

```clojure
;; Register provider first
(dscloj/register-provider! :gpt4 
  {:provider :openai 
   :model "gpt-4" 
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

(let [stream-ch (dscloj/predict-stream 
                  whales-module
                  {:query "Tell me about whales"}
                  :gpt4  ; provider-config
                  {:debounce-ms 100})]  ; options
  (go-loop []
    (when-let [result (<! stream-ch)]
      (println result)
      (recur))))
```

## Benefits of the New API

### 1. Runtime Provider Switching

```clojure
;; Register multiple providers
(dscloj/register-provider! :gpt4 {...})
(dscloj/register-provider! :claude {...})
(dscloj/register-provider! :gemini {...})

;; Switch providers at runtime - just change the keyword
(dscloj/predict provider-config module input :gpt4)
(dscloj/predict provider-config module input :claude)
(dscloj/predict provider-config module input :gemini)
```

### 2. Multi-Provider Support

```clojure
;; Compare results from different providers
(def openai-result (dscloj/predict provider-config module input :gpt4))
(def anthropic-result (dscloj/predict provider-config module input :claude))

(when (not= openai-result anthropic-result)
  (println "Different results!"))
```

### 3. Cleaner Configuration

```clojure
;; Old: Model/API key mixed with sampling options
{:model "gpt-4"
 :api-key "sk-..."
 :temperature 0.7
 :max-tokens 100}

;; New: Clear separation
;; Provider config (what LLM to use)
{:provider :openai :model "gpt-4" :config {:api-key "sk-..."}}

;; Options (how to sample)
{:temperature 0.7 :max-tokens 100}
```

### 4. Environment-based Setup

```clojure
;; Quick setup for development
(dscloj/quick-setup!)  ; Reads all API keys from environment

;; Production: explicit registration
(dscloj/register-provider! :production-gpt4 {...})
```

## Common Migration Patterns

### Pattern 1: Single Provider Application

**Before:**
```clojure
(defn init! []
  ;; No setup needed
  )

(defn process [input]
  (dscloj/predict provider-config module input 
                  {:model "gpt-4" 
                   :api-key (System/getenv "OPENAI_API_KEY")}))
```

**After:**
```clojure
(defn init! []
  (dscloj/register-provider! :default
    {:provider :openai 
     :model "gpt-4" 
     :config {:api-key (System/getenv "OPENAI_API_KEY")}}))

(defn process [input]
  (dscloj/predict provider-config module input :default))
```

### Pattern 2: Multiple Providers

**Before:**
```clojure
(defn process-with-gpt4 [input]
  (dscloj/predict provider-config module input {:model "gpt-4" :api-key openai-key}))

(defn process-with-claude [input]
  (dscloj/predict provider-config module input {:model "claude-3-5-sonnet-20241022" :api-key anthropic-key}))
```

**After:**
```clojure
(defn init! []
  (dscloj/register-provider! :gpt4 
    {:provider :openai :model "gpt-4" :config {:api-key openai-key}})
  (dscloj/register-provider! :claude 
    {:provider :anthropic :model "claude-3-5-sonnet-20241022" :config {:api-key anthropic-key}}))

(defn process [input provider-name]
  (dscloj/predict provider-config module input provider-name))

;; Usage
(process input :gpt4)
(process input :claude)
```

### Pattern 3: Testing

**Before:**
```clojure
(deftest predict-test
  (let [result (dscloj/predict provider-config module input 
                                {:model "gpt-3.5-turbo" 
                                 :api-key test-key})]
    (is (= expected result))))
```

**After:**
```clojure
(deftest predict-test
  ;; Use ad-hoc provider for tests
  (let [result (dscloj/predict provider-config module input 
                                {:provider :openai 
                                 :model "gpt-3.5-turbo" 
                                 :config {:api-key test-key}})]
    (is (= expected result))))
```

## Troubleshooting

### Error: "Wrong number of arguments to predict"

**Cause:** You're using the old API signature.

**Fix:** Add provider-config as 3rd argument:
```clojure
;; Wrong
(dscloj/predict provider-config module input {:model "gpt-4" :api-key "..."})

;; Right
(dscloj/predict provider-config module input 
                {:provider :openai :model "gpt-4" :config {:api-key "..."}})
```

### Error: "Provider not found"

**Cause:** You're using a keyword provider that hasn't been registered.

**Fix:** Register the provider first:
```clojure
(dscloj/register-provider! :gpt4 
  {:provider :openai :model "gpt-4" :config {:api-key "..."}})
```

### Options Not Working

**Cause:** Options like `:temperature` are in the wrong position.

**Fix:** Move options to 4th argument:
```clojure
;; Wrong
(dscloj/predict provider-config module input :gpt4 :temperature 0.7)

;; Right
(dscloj/predict provider-config module input :gpt4 {:temperature 0.7})
```

## Need Help?

If you encounter issues migrating:

1. Check the [examples](examples/) directory for working code
2. Review the [README.md](README.md) for complete API documentation
3. Open an issue on GitHub with your migration question

## Summary

The new router API provides:
- ✅ Better separation of concerns (provider config vs. sampling options)
- ✅ Runtime provider switching
- ✅ Multi-provider support
- ✅ Environment-based configuration
- ✅ Cleaner, more maintainable code

While this is a breaking change, the migration is straightforward and the new API is more flexible and powerful.
