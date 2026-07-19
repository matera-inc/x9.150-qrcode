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

image: ## Build the container image (x9-qrcode:latest) with buildpacks (no Dockerfile)
	$(MVNW) -Pdocker -DskipTests clean package

image-amd64: ## Build an amd64/intel image locally (x9-qrcode:latest-amd64) — uses Rosetta on Apple Silicon
	$(MVNW) -Pdocker -DskipTests clean package \
		-Dbuild.image.name=x9-qrcode:latest-amd64 \
		-Dspring-boot.build-image.imagePlatform=linux/amd64

publish: ## Publish the image to your registry — bring your own script (no registry is imposed)
	@echo "Publishing is intentionally left to you — this repo imposes no registry or flow."
	@echo ""
	@echo "Build an image first:"
	@echo "   make image        # native arch  -> x9-qrcode:latest"
	@echo "   make image-amd64  # amd64/intel   -> x9-qrcode:latest-amd64 (Rosetta on Apple Silicon)"
	@echo ""
	@echo "Then push it wherever you want, e.g.:"
	@echo "   docker tag x9-qrcode:latest <registry>/<repo>:latest"
	@echo "   docker push <registry>/<repo>:latest"
	@echo ""
	@echo ">>> Place your own publish script here. <<<"

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
