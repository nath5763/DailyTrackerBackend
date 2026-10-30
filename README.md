# DailyGamesBackend

Spring Boot API for Daily Club. It provides magic-link authentication, profiles, private friend leaderboards, Fermi score parsing, and PostgreSQL persistence.

## Local development

Requirements: Java 21, Maven 3.6.3 or newer, and Docker Desktop.

```powershell
docker compose up -d
mvn spring-boot:run
```

PostgreSQL listens on `localhost:5432`. Flyway applies database migrations automatically when Spring starts. Set `BREVO_API_KEY` and `BREVO_SENDER_EMAIL` before starting the API so magic-link emails can be sent through Brevo's HTTPS API.

Environment overrides are documented in `.env.example`.

## Verification

```powershell
mvn verify
docker build --tag daily-games-api:test .
```

The integration test uses a disposable PostgreSQL Testcontainer when Docker is available.

## Deployment

The multi-stage `Dockerfile` builds and runs the Java 21 service. In DigitalOcean App Platform, configure it as a web service with port `8080` and health check `/api/health`. Configure `BREVO_API_KEY` as an encrypted secret and set `BREVO_SENDER_EMAIL` to a verified Brevo sender. The combined frontend/backend App Platform specification remains in the `DailyTracker` repository at `.do/app.yaml`.
