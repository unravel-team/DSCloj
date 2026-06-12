.PHONY: help repl nrepl test test-ci test-integration coverage lint clean build install compile deploy check-clean-worktree .release-commit release-major release-minor

# The library version is maj.min.x: maj.min lives in the VERSION file,
# x is the git commit count at build time (see build.clj).
VERSION_FILE := VERSION

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
	@echo "  release-major    - Bump major version and commit the bump"
	@echo "  release-minor    - Bump minor version and commit the bump"

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

check-clean-worktree:
	@if [ -d .jj ]; then \
		if [ -n "$$(jj diff --summary)" ]; then \
			echo "Error: working copy is not clean. Commit or abandon changes first."; \
			exit 1; \
		fi; \
	else \
		git diff --quiet && git diff --cached --quiet || { \
			echo "Error: working tree is not clean. Commit or stash changes first."; \
			exit 1; }; \
	fi

.release-commit:
	@if [ -d .jj ]; then \
		jj commit $(VERSION_FILE) -m "chore(release): bump version to $$(cat $(VERSION_FILE))"; \
	else \
		git add $(VERSION_FILE) && \
		git commit -m "chore(release): bump version to $$(cat $(VERSION_FILE))"; \
	fi
	@echo "Released version: $$(cat $(VERSION_FILE)).$$(git rev-list --count HEAD 2>/dev/null || echo '?')"

release-major: check-clean-worktree
	@maj=$$(cut -d. -f1 $(VERSION_FILE)); \
	echo "$$((maj+1)).0" > $(VERSION_FILE)
	@$(MAKE) .release-commit

release-minor: check-clean-worktree
	@maj=$$(cut -d. -f1 $(VERSION_FILE)); min=$$(cut -d. -f2 $(VERSION_FILE)); \
	echo "$$maj.$$((min+1))" > $(VERSION_FILE)
	@$(MAKE) .release-commit

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
	@echo "🔨 Building JAR and deploying current VERSION + git commit count..."
	@echo "📦 Deploying version $$(cat $(VERSION_FILE)).$$(git rev-list --count HEAD 2>/dev/null || echo '?') to Clojars..."
	@clojure -T:build deploy
	@echo "✅ Deployment complete!"
	@echo "Verify at: https://clojars.org/tech.unravel/dscloj"
