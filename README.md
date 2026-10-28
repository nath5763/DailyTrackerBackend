# DailyGamesBackend

Spring Boot API for Daily Club. It provides magic-link authentication, profiles, private friend leaderboards, Fermi score parsing, and PostgreSQL persistence.

## Local development

Requirements: Java 21, Maven 3.6.3 or newer, and Docker Desktop.

```powershell
docker compose up -d
mvn spring-boot:run
```

PostgreSQL listens on `localhost:5432`. Mailpit captures sign-in messages at `http://localhost:8025`. Flyway applies database migrations automatically when Spring starts.

Environment overrides are documented in `.env.example`.

## Verification

```powershell
mvn verify
docker build --tag daily-games-api:test .
```

The integration test uses a disposable PostgreSQL Testcontainer when Docker is available.

## Deployment

The multi-stage `Dockerfile` builds and runs the Java 21 service. In DigitalOcean App Platform, configure it as a web service with port `8080` and health check `/api/health`. The combined frontend/backend App Platform specification remains in the `DailyTracker` repository at `.do/app.yaml`.
