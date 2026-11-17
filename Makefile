# Makefile — convenience wrapper around the Go toolchain and Docker Compose.
# Run `make help` to list targets. Needs Go (the toolchain go.mod pins) and Docker.
# Full first-run walkthrough: RUNNING.md
#
# The Java build was retired at 3.0.0 (ADR-0039): it is in the git history under the tag
# java-final, and on Docker Hub as materainc/x9-qrcode:java-final.

COMPOSE ?= docker compose
IMAGE   ?= x9-qrcode:latest
PLATFORMS ?= linux/amd64,linux/arm64

.DEFAULT_GOAL := help
.PHONY: help build test run-local image image-multiarch image-smoke up down compose-smoke smoke \
	helm-check blackbox blackbox-image blackbox-venv golden-export publish publish-dry-run \
	publish-keep-latest publish-unverified-arch dockerhub-overview dockerhub-overview-dry-run clean

help: ## List available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-26s\033[0m %s\n", $$1, $$2}'

# Provenance. DOCKERHUB.md tells adopters to read org.opencontainers.image.revision to learn which
# commit they are running, so a build that omits these produces an image nobody can attribute —
# which matters most to whoever pins a git-<sha> tag.
OCI_REVISION := $(shell git rev-parse HEAD 2>/dev/null || echo unknown)
OCI_VERSION  := $(shell git rev-parse --short HEAD 2>/dev/null || echo unknown)
LDFLAGS       = -X main.version=$(OCI_VERSION) -X main.commit=$(OCI_REVISION)
IMAGE_ARGS    = --build-arg VERSION=$(OCI_VERSION) --build-arg REVISION=$(OCI_REVISION)

build: ## Build the binary into bin/x9-qrcode (static, cgo-free)
	CGO_ENABLED=0 go build -trimpath -ldflags "$(LDFLAGS)" -o bin/x9-qrcode ./cmd/x9-qrcode

test: ## Run the Go tests (race, shuffled, leak-checked; the database tests need Docker)
	others/scripts/ci-go.sh test

run-local: build ## Run the binary on the host (needs PostgreSQL with db/changelog applied — see RUNNING.md)
	./bin/x9-qrcode

image: ## Build the image for this machine's architecture (x9-qrcode:latest); never pushed
	@if docker buildx version >/dev/null 2>&1; then \
		docker buildx build --load -t $(IMAGE) $(IMAGE_ARGS) . ; \
	else \
		docker build -t $(IMAGE) $(IMAGE_ARGS) \
			--build-arg BUILDPLATFORM=linux/$$(docker version -f '{{.Server.Arch}}') . ; \
	fi
	@docker image inspect $(IMAGE) --format 'built $(IMAGE): {{.Os}}/{{.Architecture}}, {{.Size}} bytes, user {{.Config.User}}'

image-multiarch: ## Build the image for amd64 and arm64 with docker buildx (no push, no load)
	docker buildx build --platform $(PLATFORMS) $(IMAGE_ARGS) .

image-smoke: ## Check the image: non-root, health UP against PostgreSQL, exit 0 on SIGTERM; records size
	others/scripts/image-smoke.sh $(IMAGE)

# docker-compose.yml (Story 14.2). X9_IMAGE runs an image you already have instead of building one.
# Without X9_IMAGE the image is built by `make image` (which also works without buildx), not by Compose.
up: ## Start the service with PostgreSQL 16 and Liquibase (docker-compose.yml); waits for health UP
	$(if $(X9_IMAGE),,$(MAKE) image)
	$(COMPOSE) build bootstrap liquibase
	X9_IMAGE=$(or $(X9_IMAGE),$(IMAGE)) $(COMPOSE) up -d --no-build --wait --wait-timeout 180

down: ## Stop the stack and throw its database away (docker-compose.yml)
	$(COMPOSE) down -v

compose-smoke: ## Bring docker-compose.yml up on its own project, check health and one create, take it down
	others/scripts/compose-smoke.sh

smoke: ## Liveness check against a running service on :8080 (health + public JWKS)
	@curl -fsS http://localhost:8080/actuator/health && echo "  <- health OK"
	@curl -fsS http://localhost:8080/pub/.well-known/jwks >/dev/null && echo "public JWKS OK"

helm-check: ## helm lint and template the chart; the rendering holds no Java-only variable
	others/scripts/helm-check.sh

blackbox: ## Run the black-box suite (X9_BASE_URL, default http://localhost:8080; see others/blackbox/README.md)
	@.blackbox-venv/bin/python -c 'import pytest, cryptography, yaml' 2>/dev/null || $(MAKE) blackbox-venv
	@.blackbox-venv/bin/pytest others/blackbox -q

blackbox-image: ## Run the black-box suite against an image, all in containers, as CI does (X9_IMAGE, default x9-qrcode:latest)
	X9_IMAGE=$(or $(X9_IMAGE),$(IMAGE)) ./others/blackbox/deployment/run-against-image.sh

blackbox-venv: ## Create .blackbox-venv with the suite's pinned dependencies (pytest, cryptography, pyyaml)
	python3 -m venv .blackbox-venv
	.blackbox-venv/bin/pip install -q -r others/blackbox/requirements.txt

golden-export: ## Re-export the golden JWS and EMV sets from the java-final image (see others/blackbox/golden/README.md)
	./others/blackbox/golden/export.sh

publish: ## Build both architectures, verify each, publish git-<sha> (+ VERSION tags) and latest
	./others/scripts/publish-image.sh

publish-dry-run: ## Everything publish does, except the push — works from any branch
	./others/scripts/publish-image.sh --dry-run

publish-keep-latest: ## Publish git-<sha> (+ VERSION tags) but leave `latest` where it is
	./others/scripts/publish-image.sh --no-latest

publish-unverified-arch: ## Publish even if an architecture cannot be run here (say why in the PR)
	./others/scripts/publish-image.sh --allow-unverified-arch

dockerhub-overview: ## Push DOCKERHUB.md to the Docker Hub repository Overview
	./others/scripts/push-dockerhub-overview.sh

dockerhub-overview-dry-run: ## Show what the Overview push would change, without writing
	./others/scripts/push-dockerhub-overview.sh --dry-run

clean: ## Remove build output (bin/, go-ci-out/)
	rm -rf bin go-ci-out

# The 2.x names of the Go targets, kept as thin aliases so scripts written before 3.0.0 still run.
# GO_IMAGE, the 2.x image variable, still works through IMAGE. Not listed by `make help`.
ifdef GO_IMAGE
IMAGE := $(GO_IMAGE)
endif
.PHONY: go-build go-test go-image go-image-multiarch go-image-smoke go-up go-down go-compose-smoke
go-build: build
go-test: test
go-image: image
go-image-multiarch: image-multiarch
go-image-smoke: image-smoke
go-up: up
go-down: down
go-compose-smoke: compose-smoke
