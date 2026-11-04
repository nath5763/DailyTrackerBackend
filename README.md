# DailyGamesBackend

Spring Boot API for Daily Tracker. It provides verified email/password authentication, game-specific private leaderboards, Stripe subscription entitlements, Discord HTTP interactions, interchangeable score parsers, and PostgreSQL persistence. Supported daily games include Fermi and Krillion.

## Local development

Requirements: Java 21, Maven 3.6.3 or newer, and Docker Desktop.

```powershell
docker compose up -d
mvn spring-boot:run
```

PostgreSQL listens on `localhost:5432`. Flyway applies database migrations automatically when Spring starts. Set `BREVO_API_KEY` and `BREVO_SENDER_EMAIL` before starting the API so verification and password-reset emails can be sent through Brevo's HTTPS API.

For billing, create an active recurring Stripe Price for $2 USD per month and a Stripe Customer Portal configuration, then configure `STRIPE_SECRET_KEY`, `STRIPE_LEADERBOARD_PRICE_ID`, `STRIPE_PORTAL_CONFIGURATION_ID`, and `STRIPE_WEBHOOK_SECRET`. All four values must belong to the same Stripe test or live mode. The webhook destination is `https://<your-app>/api/v1/billing/stripe/webhook`; subscribe it to `checkout.session.completed`, `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.paid`, and `invoice.payment_failed`. Enable subscription cancellation, invoice viewing, and payment-method updates in the selected Stripe Customer Portal configuration. Checkout leaves payment-method selection to Stripe Managed Payments and the account's Dashboard configuration.

Stripe requests default to a 5-second connection timeout and a 20-second response timeout so an upstream failure returns a structured 502 before the hosting gateway times out. Override them with `STRIPE_CONNECT_TIMEOUT_MS` and `STRIPE_READ_TIMEOUT_MS` only when the hosting request timeout is longer.

Environment overrides are documented in `.env.example`.

## Discord integration

Discord uses the existing HTTPS service; no Gateway bot or separate runtime is needed. Configure `DISCORD_APPLICATION_ID` and `DISCORD_PUBLIC_KEY` on the backend. The signed public endpoint is `https://<api-host>/api/v1/discord/interactions`. `AUTH_TOKEN_PEPPER` also domain-separates the hashes of ten-minute, single-use account-link codes, so keep it secret and stable.

The running backend must not receive `DISCORD_BOT_TOKEN` or `DISCORD_TEST_GUILD_ID`. Those values are used only from an administrator's shell by the idempotent bulk registrar:

```powershell
$env:DISCORD_APPLICATION_ID = "..."
$env:DISCORD_BOT_TOKEN = "..."
$env:DISCORD_TEST_GUILD_ID = "..."
.\scripts\register-discord-commands.ps1 -Scope Guild
```

The script bulk-replaces this application's commands in the selected scope. After test-guild smoke testing, global promotion requires the explicit `-Scope Global -ConfirmGlobal` arguments. The application should be installed with the `applications.commands` scope. `/link`, `/unlink`, `/setup`, and `/daily` are guild-context commands; `/setup` requires Manage Server and rechecks Daily Tracker leaderboard ownership.

## Verification

```powershell
mvn verify
docker build --tag daily-games-api:test .
```

The integration test uses a disposable PostgreSQL Testcontainer when Docker is available.

## Adding a game

Add the game catalog row in a new Flyway migration and implement `GameAdapter`. The adapter owns parsing, confirmation rules, score formatting, and submission-form metadata; shared services dispatch adapters by slug and contain no game-specific branches. Deploy additive backend adapter/API changes before the frontend so older clients continue to work during rollout.

## Deployment

The multi-stage `Dockerfile` builds and runs the Java 21 service. In DigitalOcean App Platform, configure it as a web service with port `8080` and health check `/api/health`. Set `APP_BASE_URL` to the public website origin (for example, `https://dailytracker.cc`); it is used for email links and request origin checks. Configure the Brevo and Stripe keys as encrypted secrets and set `BREVO_SENDER_EMAIL` to a verified Brevo sender.

For Discord, deploy the Flyway migration and backend configuration first, then the dashboard UI. Create the Discord application only after the signed endpoint is deployed; validate its endpoint, install `applications.commands`, bulk-register into a test guild, and smoke-test linking, setup, both games, duplicates, locked boards, and both unlink paths before global promotion. Never add the registrar bot token to the web-service environment.
