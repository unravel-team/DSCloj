# Release Notes: DSCloj 0.1.0-alpha.2

**Release Date:** 2025-11-17

## Overview

This release introduces the **Router API** - a major architectural improvement that enables better provider management, runtime model switching, and cleaner separation of concerns. This is a **breaking change** that requires code updates.

## 🚨 Breaking Changes

### Router API Migration

DSCloj has migrated from `litellm.core` to `litellm.router` for improved provider management. The `predict` and `predict-stream` function signatures have changed.

#### Old API (0.1.0-alpha.1)
```clojure
(dscloj/predict module input-map options)

(dscloj/predict qa-module 
                {:question "What is AI?"}
                {:model "gpt-4"
                 :api-key (System/getenv "OPENAI_API_KEY")
                 :temperature 0.7})
```

#### New API (0.1.0-alpha.2)
```clojure
(dscloj/predict module input-map provider-config & [options])

;; Option 1: With registered provider
(dscloj/register-provider! :gpt4 
  {:provider :openai 
   :model "gpt-4" 
   :config {:api-key (System/getenv "OPENAI_API_KEY")}})

(dscloj/predict qa-module 
                {:question "What is AI?"}
                :gpt4
                {:temperature 0.7})

;; Option 2: Ad-hoc provider
(dscloj/predict qa-module 
                {:question "What is AI?"}
                {:provider :openai 
                 :model "gpt-4" 
                 :config {:api-key (System/getenv "OPENAI_API_KEY")}}
                {:temperature 0.7})
```

**Key Changes:**
- 3rd argument is now `provider-config` (keyword or map)
- Sampling options (`:temperature`, `:max-tokens`, etc.) moved to 4th argument
- `:model` and `:api-key` are now part of `provider-config`, not options
- Same changes apply to `predict-stream`

## 🐛 Bug Fixes

- Improved error handling for provider configuration
- Better validation of provider-config parameter

## 💡 Benefits

The router API provides:
- ✅ Better separation of concerns (provider config vs. sampling options)
- ✅ Runtime provider switching
- ✅ Multi-provider support and comparison
- ✅ Environment-based configuration
- ✅ Cleaner, more maintainable code
- ✅ Easier testing with ad-hoc providers

