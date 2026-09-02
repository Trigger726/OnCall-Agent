.PHONY: help web build test run docker-up docker-down clean

help:
	@echo "OpsPilot commands"
	@echo "  make web         Install and build the Vue frontend"
	@echo "  make build       Build the production jar"
	@echo "  make test        Run backend tests and frontend type checks"
	@echo "  make run         Run the application on port 9900"
	@echo "  make docker-up   Start MySQL, OpsPilot and Prometheus"
	@echo "  make docker-down Stop the Docker stack"

web:
	cd web && npm ci && npm run build

build: web
	./mvnw clean package

test:
	cd web && npm run build
	./mvnw test

run:
	./mvnw spring-boot:run

docker-up:
	docker compose up --build -d

docker-down:
	docker compose down

clean:
	./mvnw clean
	cd web && rm -rf dist
