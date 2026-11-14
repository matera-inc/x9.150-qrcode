# Makefile — convenience wrapper around Maven (./mvnw) and Docker Compose.
# Run `make help` to list targets. Requires a Java 25 JDK. Maven itself is
# bundled via the wrapper (./mvnw), so you do not need Maven pre-installed.
# Full first-run walkthrough: RUNNING.md

MVNW    ?= ./mvnw
COMPOSE ?= docker compose
IMAGE   ?= x9-qrcode:latest

.DEFAULT_GOAL := help
.PHONY: help build test run-local image image-amd64 publish up up-prod down clean smoke \
	go-build go-test go-image go-image-multiarch go-image-smoke

help: ## List available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

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

dockerhub-overview: ## Push DOCKERHUB.md to the Docker Hub repository Overview
	./others/scripts/push-dockerhub-overview.sh

dockerhub-overview-dry-run: ## Show what the Overview push would change, without writing
	./others/scripts/push-dockerhub-overview.sh --dry-run

blackbox: ## Run the black-box suite (X9_BASE_URL, default http://localhost:8080; see others/blackbox/README.md)
	@.blackbox-venv/bin/python -c 'import pytest, cryptography' 2>/dev/null || $(MAKE) blackbox-venv
	@.blackbox-venv/bin/pytest others/blackbox -q

blackbox-venv: ## Create .blackbox-venv with the suite's pinned dependencies (pytest, cryptography)
	python3 -m venv .blackbox-venv
	.blackbox-venv/bin/pip install -q -r others/blackbox/requirements.txt

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

# ── The Go build (ADR-0022), alongside Java until the cutover renames these targets ──────────────
GO_IMAGE      ?= x9-qrcode:go
GO_PLATFORMS  ?= linux/amd64,linux/arm64
GO_LDFLAGS     = -X main.version=$(OCI_VERSION) -X main.commit=$(OCI_REVISION)
GO_IMAGE_ARGS  = --build-arg VERSION=$(OCI_VERSION) --build-arg REVISION=$(OCI_REVISION)

go-build: ## Build the Go binary into bin/x9-qrcode (static, cgo-free)
	CGO_ENABLED=0 go build -trimpath -ldflags "$(GO_LDFLAGS)" -o bin/x9-qrcode ./cmd/x9-qrcode

go-test: ## Run the Go tests (race, shuffled, leak-checked; the database tests need Docker)
	others/scripts/ci-go.sh test

go-image: ## Build the Go image for this machine's architecture (x9-qrcode:go); never pushed
	@if docker buildx version >/dev/null 2>&1; then \
		docker buildx build --load -t $(GO_IMAGE) $(GO_IMAGE_ARGS) . ; \
	else \
		docker build -t $(GO_IMAGE) $(GO_IMAGE_ARGS) \
			--build-arg BUILDPLATFORM=linux/$$(docker version -f '{{.Server.Arch}}') . ; \
	fi
	@docker image inspect $(GO_IMAGE) --format 'built $(GO_IMAGE): {{.Os}}/{{.Architecture}}, {{.Size}} bytes, user {{.Config.User}}'

go-image-multiarch: ## Build the Go image for amd64 and arm64 with docker buildx (no push, no load)
	docker buildx build --platform $(GO_PLATFORMS) $(GO_IMAGE_ARGS) .

go-image-smoke: ## Check the Go image: non-root, health UP against PostgreSQL, exit 0 on SIGTERM; records size
	others/scripts/go-image-smoke.sh $(GO_IMAGE)

clean: ## Remove build output (target/)
	$(MVNW) clean
