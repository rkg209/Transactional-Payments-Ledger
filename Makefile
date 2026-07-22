.PHONY: help run test verify concurrency-test bench bench-quick format check up down clean

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

run: ## Run the service locally (needs Postgres: `make up`)
	mvn spring-boot:run

test: ## Unit + integration tests (JUnit 5 + Testcontainers, REAL Postgres)
	mvn -B verify

verify: test ## Alias for test

concurrency-test: ## THE HEADLINE: 10k concurrent transfers, ~30% duplicates, saga crashes
	mvn -B verify -Dit.test='*ConcurrencyChaosHarness' \
		-Dtest=none -Dsurefire.failIfNoSpecifiedTests=false

concurrency-test-x10: ## Repeatability gate (NFR-1): 10 consecutive runs, 10 passes
	@for i in $$(seq 1 10); do echo "── run $$i/10 ──"; $(MAKE) concurrency-test || exit 1; done

bench: ## JMH benchmarks: full 2x2 matrix x 7 contention levels (many minutes)
	mvn -B -P bench test-compile exec:exec

bench-quick: ## Fast smoke: 3 contention levels, short bursts -- for iterating on the harness
	mvn -B -P bench -Dbench.profile=quick test-compile exec:exec

format: ## Apply Spotless formatting
	mvn -B spotless:apply

check: ## Verify formatting without changing files
	mvn -B spotless:check

jar: ## Build the runnable jar on the host (needs Docker for jOOQ codegen)
	mvn -B -q clean package -DskipTests

up: jar ## Build the jar, then start the stack (app + Postgres)
	docker compose up -d --build

down: ## Stop the stack
	docker compose down

clean: ## Clean build artifacts
	mvn -B clean
