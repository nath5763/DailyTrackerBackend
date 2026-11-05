# DailyGamesBackend

Spring Boot API for Daily Club. It provides verified email/password authentication, game-specific private leaderboards, Stripe subscription entitlements, Discord HTTP interactions, interchangeable score parsers, and PostgreSQL persistence. Website score adapters cover Fermi, Krillion, Wordle, Pinpoint, Queens, Crossclimb, Tango, and Zip. The public `GET /api/v1/catalog/games` directory is separate from the authenticated tracked-game API. See [catalog_audit.md](catalog_audit.md) for launch link decisions and share fixture sources.

## Local development

Requirements: Java 21, Maven 3.6.3 or newer, and Docker Desktop.

```powershell
docker compose up -d
mvn spring-boot:run
```

PostgreSQL listens on `localhost:5432`. Flyway applies database migrations automatically when Spring starts. Set `BREVO_API_KEY` and `BREVO_SENDER_EMAIL` before starting the API so verification and password-reset emails can be sent through Brevo's HTTPS API.

For billing, create recurring Stripe Prices for $2 and $5 USD per month. Configure `STRIPE_SECRET_KEY`, `STRIPE_LEADERBOARD_PRICE_ID` ($2), `STRIPE_PREMIUM_PRICE_ID` ($5), `STRIPE_PORTAL_CONFIGURATION_ID`, and `STRIPE_WEBHOOK_SECRET` in the same Stripe test or live mode. Free members can own five single-game boards; $2 members can also own one all-game board and connect Discord channels; $5 members can own five all-game boards and connect channels. Multi-game boards share one roster across every active game and add future games automatically. Existing boards remain stored when limits shrink. Single-game boards stay readable; all-game boards lock when the owner's paid entitlement and grace period end. The webhook destination is `https://<your-app>/api/v1/billing/stripe/webhook`; subscribe it to `checkout.session.completed`, `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.paid`, and `invoice.payment_failed`. Enable subscription cancellation, invoice viewing, payment-method updates, and subscription Price switching between both Prices in the selected Stripe Customer Portal configuration. Checkout leaves payment-method selection to Stripe Managed Payments and the account's Dashboard configuration.

Stripe requests default to a 5-second connection timeout and a 20-second response timeout so an upstream failure returns a structured 502 before the hosting gateway times out. Override them with `STRIPE_CONNECT_TIMEOUT_MS` and `STRIPE_READ_TIMEOUT_MS` only when the hosting request timeout is longer.

Environment overrides are documented in `.env.example`.

## Discord integration

Discord uses the existing HTTPS service; no Gateway connection or separate runtime is needed. Configure `DISCORD_APPLICATION_ID`, `DISCORD_PUBLIC_KEY`, `DISCORD_CLIENT_SECRET`, and `DISCORD_BOT_TOKEN` as backend secrets. Set `DISCORD_CALLBACK_URL` to `https://<website-host>/api/v1/discord/oauth/callback` and register that exact redirect URI in the Discord application. The frontend's `/api` rewrite must forward this callback to the backend so the signed-in website session cookie is available. The signed interaction endpoint remains `https://<api-host>/api/v1/discord/interactions`. Keep `AUTH_TOKEN_PEPPER` secret and stable; it also encrypts short-lived OAuth user tokens until channel selection. A bot token is required on the backend only for REST verification and channel listing; no Gateway connection is made.

The command registrar uses the same bot token from an administrator's shell. `DISCORD_TEST_GUILD_ID` is only needed by that script:

```powershell
$env:DISCORD_APPLICATION_ID = "..."
$env:DISCORD_BOT_TOKEN = "..."
$env:DISCORD_TEST_GUILD_ID = "..."
.\scripts\register-discord-commands.ps1 -Scope Guild
```

The script bulk-replaces this application's commands in the selected scope. After test-guild smoke testing, global promotion requires the explicit `-Scope Global -ConfirmGlobal` arguments. Paid owners can select **Add leaderboard to Discord** from an unlocked leaderboard. Discord authorization requests `bot`, `applications.commands`, `identify`, and `guilds`, with only View Channel permission for the bot. After selecting a server, the owner chooses an existing visible text channel. The backend checks their Discord identity and Manage Server permission, bot access, website ownership, and paid entitlement before updating the exact server/channel mapping. A channel already mapped to another board requires explicit replacement confirmation. OAuth state is single-use, setup expires after ten minutes, and the user token is discarded after saving. Servers previously authorized for commands alone must authorize the bot through this flow. `/link`, `/unlink`, `/setup`, `/daily`, and `/leaderboard` remain available; `/setup` requires Manage Server, board ownership, and an active paid owner tier. Existing channel mappings also check the owner's paid entitlement on every `/daily` and `/leaderboard` call. Free members can link and use those commands in a paid owner's channel. `/daily` requires both `game` and `result`. `/leaderboard` posts up to ten rankings in the channel; `game` is required and `range` defaults to today (`7d`, `30d`, and `all` are available). Both game choices are fixed to Fermi and Krillion. Adding another game therefore also requires updating this registrar and rerunning it in guild scope before global promotion.

## Leaderboard types

`POST /api/v1/leaderboards` accepts an optional `boardType` of `SINGLE_GAME` (the default) or `MULTI_GAME`. Single-game requests require `gameSlug` and count toward the five-board limit. Multi-game requests must omit it and count toward the owner's tier limit. `GET /api/v1/leaderboards/{id}` accepts an optional `game` slug. Multi-game boards default to the first active game by name, while rankings and ranges remain independent for the selected game. Owners can irreversibly convert a single-game board with `POST /api/v1/leaderboards/{id}/convert-to-multi-game` if their all-game limit has room; its roster is preserved. `POST /api/v1/billing/checkout-session` accepts `{ "tier": "STANDARD" }` or `{ "tier": "PREMIUM" }`. `GET /api/v1/billing/status` returns tier, owned board counts, limits, and subscription state.

Deploy the backend with Flyway V8 before the frontend game directory and score options. Configure the $5 Price and portal switching, then verify both checkout choices and webhook-driven upgrades, downgrades, cancellations, and payment grace in Stripe test mode before enabling live mode.

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

For Discord, deploy Flyway V9 and the backend secrets first, register the OAuth redirect URI, then deploy the frontend. The leaderboard setup button remains hidden until the backend reports that OAuth setup is configured. Validate the signed interaction endpoint, register commands in a test guild, and smoke-test a new server and a commands-only server, owner/member/free/locked permissions, identity linking, channel replacement, `/daily`, and `/leaderboard` before global command promotion.
