.PHONY: help repl nrepl test test-ci test-integration coverage lint clean build install compile deploy

help:
	@echo "Available targets:"
	@echo "  repl             - Start a Clojure REPL"
	@echo "  nrepl            - Start an nREPL server on port 7888"
	@echo "  test             - Run unit tests"
	@echo "  test-ci          - Run unit tests in CI"
	@echo "  test-integration - Run integration tests (requires OPENROUTER_API_KEY)"
	@echo "  coverage         - Run tests with coverage report"
	@echo "  lint             - Run clj-kondo linter"
	@echo "  compile          - Compile and check syntax"
	@echo "  clean            - Remove target directory"
	@echo "  build            - Build the project"
	@echo "  install          - Install to local Maven repository"
	@echo "  deploy           - Deploy to Clojars (requires CLOJARS_USERNAME and CLOJARS_PASSWORD)"

repl:
	clojure -M:repl

nrepl:
	@echo "Starting nREPL server on port 7888..."
	clojure -M:repl -m nrepl.cmdline --middleware '["cider.nrepl/cider-middleware"]' --port 7888

test:
	clojure -M:test -m kaocha.runner unit

test-ci:
	clojure -M:test -m kaocha.runner unit

test-integration:
	@echo "Running integration tests (requires OPENROUTER_API_KEY)..."
	clojure -M:test -m kaocha.runner integration

coverage:
	clojure -M:test:coverage

lint:
	clojure -M:kondo --lint src test

compile:
	@echo "Compiling and checking syntax..."
	clojure -M -e "(require 'dscloj.core) (println \"✓ Code compiles successfully\")"

clean:
	rm -rf target .cpcache

build:
	clojure -T:build jar

install:
	clojure -T:build install

deploy:
	@echo "🚀 Deploying DSCloj to Clojars..."
	@if [ -z "$$CLOJARS_USERNAME" ]; then \
		echo "❌ Error: CLOJARS_USERNAME environment variable is not set"; \
		exit 1; \
	fi
	@if [ -z "$$CLOJARS_PASSWORD" ]; then \
		echo "❌ Error: CLOJARS_PASSWORD environment variable is not set"; \
		exit 1; \
	fi
	@echo "✅ Environment variables are set"
	@echo "🧪 Running tests..."
	@$(MAKE) test
	@echo "🔨 Building JAR..."
	@clojure -T:build jar
	@echo "📦 Deploying version 0.1.0-alpha.1 to Clojars..."
	@clojure -X:deploy :artifact '"target/dscloj-0.1.0-alpha.1.jar"'
	@echo "✅ Deployment complete!"
	@echo "Verify at: https://clojars.org/tech.unravel/DSClj"
