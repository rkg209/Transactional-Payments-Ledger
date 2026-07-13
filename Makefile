.PHONY: help run test verify concurrency-test bench format check up down clean

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

run: ## Run the service locally (needs Postgres: `make up`)
	mvn spring-boot:run

test: ## Unit + integration tests (JUnit 5 + Testcontainers, REAL Postgres)
	mvn -B verify

verify: test ## Alias for test

concurrency-test: ## THE HEADLINE: 10k concurrent transfers, ~30% duplicates
	@echo ""
	@echo "  ┌──────────────────────────────────────────────────────────────┐"
	@echo "  │  concurrency-test is not implemented yet.                    │"
	@echo "  │                                                              │"
	@echo "  │  It lands with SPEC 0007, and it will assert:                │"
	@echo "  │    • 10,000 concurrent transfers, ~30% duplicates            │"
	@echo "  │    • 0 double-charges                                        │"
	@echo "  │    • 0 money created or destroyed                            │"
	@echo "  │    • Σ(ledger_entries) = 0                                   │"
	@echo "  │                                                              │"
	@echo "  │  Specs 0001–0006 must land first. See specs/README.md.       │"
	@echo "  └──────────────────────────────────────────────────────────────┘"
	@echo ""
	@exit 1

bench: ## JMH benchmarks: optimistic vs pessimistic, RC vs SERIALIZABLE
	@echo ""
	@echo "  bench is not implemented yet — it lands with SPEC 0008."
	@echo "  It will measure transfers/sec and p50/p99 across the 2x2 matrix"
	@echo "  and identify the optimistic/pessimistic contention crossover."
	@echo ""
	@exit 1

format: ## Apply Spotless formatting
	mvn -B spotless:apply

check: ## Verify formatting without changing files
	mvn -B spotless:check

up: ## Start the stack (app + Postgres)
	docker compose up -d --build

down: ## Stop the stack
	docker compose down

clean: ## Clean build artifacts
	mvn -B clean
