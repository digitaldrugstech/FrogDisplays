.DEFAULT_GOAL := help

## ── Build ───────────────────────────────────────────────────────────

.PHONY: build
build: ## Build all modules (Paper + Fabric)
	./gradlew build

.PHONY: shadowjar
shadowjar: ## Build fat JARs for all modules
	./gradlew shadowJar

.PHONY: build-paper
build-paper: ## Build Paper module only
	./gradlew :paper:shadowJar

.PHONY: build-fabric
build-fabric: ## Build Fabric module only
	./gradlew :fabric:remapJar

## ── Development ─────────────────────────────────────────────────────

.PHONY: clean
clean: ## Clean build artifacts
	./gradlew clean

## ── Code Quality ────────────────────────────────────────────────────

.PHONY: test
test: ## Run tests
	./gradlew test

## ── Help ────────────────────────────────────────────────────────────

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'
