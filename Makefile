# Makefile — convenience wrapper around Maven (./mvnw) and Docker Compose.
# Run `make help` to list targets. Requires a Java 25 JDK. Maven itself is
# bundled via the wrapper (./mvnw), so you do not need Maven pre-installed.
# Full first-run walkthrough: RUNNING.md

MVNW    ?= ./mvnw
COMPOSE ?= docker compose
IMAGE   ?= x9-qrcode:latest

.DEFAULT_GOAL := help
.PHONY: help build test run-local image image-amd64 publish up up-prod down clean smoke

help: ## List available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-11s\033[0m %s\n", $$1, $$2}'

build: ## Compile, test and install all modules (./mvnw clean install)
	$(MVNW) clean install

test: ## Run the full test suite
	$(MVNW) test

run-local: ## Run the app on the host JVM (needs a MongoDB replica set — see RUNNING.md)
	$(MVNW) -pl x9-qrcode-infrastructure spring-boot:run

# Provenance, passed to the buildpack as BP_OCI_*. DOCKERHUB.md tells adopters to read
# org.opencontainers.image.revision to learn which commit they are running, so a build that omits
# these produces an image nobody can attribute — which matters most to whoever pins a git-<sha> tag.
OCI_REVISION := $(shell git rev-parse HEAD 2>/dev/null || echo unknown)
OCI_VERSION  := $(shell git rev-parse --short HEAD 2>/dev/null || echo unknown)
OCI_FLAGS     = -Doci.revision=$(OCI_REVISION) -Doci.version=$(OCI_VERSION)

image: ## Build the container image (x9-qrcode:latest) with buildpacks (no Dockerfile)
	$(MVNW) -Pdocker -DskipTests clean package $(OCI_FLAGS)

image-amd64: ## Build an amd64/intel image locally (x9-qrcode:latest-amd64) — uses Rosetta on Apple Silicon
	$(MVNW) -Pdocker -DskipTests clean package $(OCI_FLAGS) \
		-Dbuild.image.name=x9-qrcode:latest-amd64 \
		-Dspring-boot.build-image.imagePlatform=linux/amd64

acceptance: ## Black-box acceptance tests against a RUNNING deployment (URL=http://host:port)
	./others/acceptance/acceptance.py $(or $(URL),http://localhost:8080)

publish: ## Build, verify BOTH architectures, and publish (git-<sha> + latest)
	./others/scripts/publish-image.sh

publish-dry-run: ## Everything publish does, except the push — works from any branch
	./others/scripts/publish-image.sh --dry-run

publish-keep-latest: ## Publish git-<sha> but leave `latest` where it is
	./others/scripts/publish-image.sh --no-latest

publish-unverified-arch: ## Publish even if an architecture cannot be run here (say why in the PR)
	./others/scripts/publish-image.sh --allow-unverified-arch

up: ## Start app + single-node MongoDB replica set locally (docker-compose.yml)
	$(COMPOSE) up -d

up-prod: ## Start via docker-compose.prod.yml (env-driven config; see .env.sample)
	$(COMPOSE) -f docker-compose.prod.yml up -d

down: ## Stop and remove containers from both compose files
	-$(COMPOSE) down
	-$(COMPOSE) -f docker-compose.prod.yml down

smoke: ## Liveness check against a running app on :8080 (health + public JWKS)
	@curl -fsS http://localhost:8080/actuator/health && echo "  <- health OK"
	@curl -fsS http://localhost:8080/pub/.well-known/jwks >/dev/null && echo "public JWKS OK"

clean: ## Remove build output (target/)
	$(MVNW) clean
