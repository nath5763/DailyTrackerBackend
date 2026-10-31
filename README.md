# DailyGamesBackend

Spring Boot API for Daily Tracker. It provides magic-link authentication, game-specific private leaderboards, Stripe subscription entitlements, interchangeable score parsers, and PostgreSQL persistence. Supported daily games include Fermi and Krillion.

## Local development

Requirements: Java 21, Maven 3.6.3 or newer, and Docker Desktop.

```powershell
docker compose up -d
mvn spring-boot:run
```

PostgreSQL listens on `localhost:5432`. Flyway applies database migrations automatically when Spring starts. Set `BREVO_API_KEY` and `BREVO_SENDER_EMAIL` before starting the API so magic-link emails can be sent through Brevo's HTTPS API.

For billing, create a recurring Stripe Price for $2 USD per month and configure `STRIPE_SECRET_KEY`, `STRIPE_LEADERBOARD_PRICE_ID`, and `STRIPE_WEBHOOK_SECRET`. The webhook destination is `https://<your-app>/api/v1/billing/stripe/webhook`; subscribe it to Checkout Session completion, customer subscription changes/deletion, and successful/failed invoice events. Enable subscription cancellation and payment-method updates in Stripe Customer Portal settings.

Environment overrides are documented in `.env.example`.

## Verification

```powershell
mvn verify
docker build --tag daily-games-api:test .
```

The integration test uses a disposable PostgreSQL Testcontainer when Docker is available.

## Adding a game

Add the game catalog row in a new Flyway migration and implement `GameAdapter`. The adapter owns parsing, confirmation rules, score formatting, and submission-form metadata; shared services dispatch adapters by slug and contain no game-specific branches. Deploy additive backend adapter/API changes before the frontend so older clients continue to work during rollout.

## Deployment

The multi-stage `Dockerfile` builds and runs the Java 21 service. In DigitalOcean App Platform, configure it as a web service with port `8080` and health check `/api/health`. Configure the Brevo and Stripe keys as encrypted secrets and set `BREVO_SENDER_EMAIL` to a verified Brevo sender.
