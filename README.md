# DailyGamesBackend

WeChat Mini Program and optional Chat Tool setup and manual developer-console steps are documented in the frontend repository's `wechat-mini-program/README.md`. Both integrations are disabled by default. Migrations `V44__wechat_mini_program.sql` and `V45__wechat_chat_tool.sql` add their isolated storage.

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

## Telegram integration

Create a bot with BotFather, keep **Group Privacy** enabled, and set `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `TELEGRAM_WEBHOOK_URL` (the public HTTPS URL ending in `/api/v1/telegram/webhook`), and a random `TELEGRAM_WEBHOOK_SECRET` of at least 16 letters, digits, underscores, or hyphens. The backend registers commands and the webhook on startup and retries registration every six hours. Telegram command menus use underscores; typed hyphen aliases also work. Flyway V33 adds Telegram account links, group mappings, one-time signup and link intents, pending private flows, and update receipts.

Deploy the backend and migration before the frontend. The website Profile issues one-time codes for `/link`. An owner with a paid tier and Telegram group administrator permission can run `/setup` in a group to choose one of their multi-game leaderboards, or copy `/setup <UUID>` from the leaderboard page. Only the current mapped board's owner can replace or remove a mapping. Linked members can use the group regardless of their own tier. The bot offers game and range buttons; share text and LoLdle dates are collected in a private bot chat. A one-to-one chat asks the player to choose an accessible leaderboard.

In a connected group, `/signup` offers a private bot continuation. Its website link expires in 24 hours and can be used once. The website asks the original Telegram identity to confirm in its private bot chat before linking the account. Joining succeeds only while the group still maps to the original accessible leaderboard. After deployment, test linking, signup with a new and existing account, group setup and replacement, score submission, rankings, unlinking, and revoked owner access with real Telegram accounts.

## Discord integration

Discord uses the existing HTTPS service; no Gateway connection or separate runtime is needed. Configure `DISCORD_APPLICATION_ID`, `DISCORD_PUBLIC_KEY`, `DISCORD_CLIENT_SECRET`, and `DISCORD_BOT_TOKEN` as backend secrets. Set `DISCORD_CALLBACK_URL` to `https://<website-host>/api/v1/discord/oauth/callback` and register that exact redirect URI in the Discord application. The frontend's `/api` rewrite must forward this callback to the backend so the signed-in website session cookie is available. The signed interaction endpoint remains `https://<api-host>/api/v1/discord/interactions`. Keep `AUTH_TOKEN_PEPPER` secret and stable; it also encrypts short-lived OAuth user tokens until channel selection. A bot token is required on the backend only for REST verification and channel listing; no Gateway connection is made.

The backend registers `/help`, `/signup`, `/my-id`, `/add-member`, `/link`, `/unlink`, `/setup`, `/daily`, and `/leaderboard` globally when new or changed and retries transient failures. It also registers the commands immediately in a server when an owner connects a channel. Existing connected servers are reconciled automatically. Registration upserts these commands without replacing unrelated application commands. `/signup` privately sends a single-use link valid for 24 hours. After website registration or sign-in and profile setup, Discord OAuth `identify` confirms the same Discord user and links the accounts. If the channel still points to its original accessible leaderboard, signup adds the user as a member. An unconnected channel only links the account. `/my-id` privately shows the linked profile's shareable UUID. The linked board owner can use `/add-member uuid:<profile UUID>` in a connected channel. `/link` remains available for manual account linking. The PowerShell registrar is available for administrator troubleshooting only. Paid owners can select **Add leaderboard to Discord** from an unlocked leaderboard. Discord authorization requests `bot`, `applications.commands`, `identify`, and `guilds`, with only View Channel permission for the bot. After selecting a server, the owner chooses an existing visible text channel. The backend checks their Discord identity and Manage Server permission, bot access, website ownership, and paid entitlement before updating the exact server/channel mapping. A channel already mapped to another board requires explicit replacement confirmation. OAuth state is single-use, setup expires after ten minutes, and the user token is discarded after saving. Servers previously authorized for commands alone must authorize the bot through this flow. `/setup` requires Manage Server, board ownership, and an active paid owner tier. Existing channel mappings also check the owner's paid entitlement on every `/daily` and `/leaderboard` call. Free members can link and use those commands in a paid owner's channel. `/daily` requires the complete `result` share text and infers the game when exactly one supported format matches. `/leaderboard` without a game posts a compact Today card in the channel. Expand shows game leaders privately to the member who clicks, in pages of up to ten games; the card also links to the full website leaderboard. With a game selected, the command posts detailed rankings and `range` defaults to today (`7d`, `30d`, and `all` are available). The leaderboard command autocompletes active games allowed on the channel's board. The tracked game catalog is loaded into a deployment-scoped backend cache at startup, so newly deployed or activated games appear after the backend restarts.

Global commands support both Discord installation types (`GUILD_INSTALL` `0` and `USER_INSTALL` `1`) and all interaction contexts (`GUILD` `0`, `BOT_DM` `1`, and `PRIVATE_CHANNEL` `2`). `PRIVATE_CHANNEL` includes Group DMs for a user-installed app. In a DM or Group DM, Discord sends the invoker at `interaction.user.id`; no guild or member object is required. `/help`, `/signup`, `/link`, `/unlink`, and `/my-id` work without a server. In a Group DM, a linked leaderboard owner runs `/set-leaderboard leaderboard:<UUID>` once; afterward `/daily`, `/leaderboard`, and `/add-member` use that saved Group DM mapping and can omit the `leaderboard` option. Only the current mapped leaderboard's owner can replace or remove it with `/unset-leaderboard`. One-to-one DMs and unconfigured Group DMs continue to require an explicit leaderboard UUID. Every invoking user must be linked and authorized for the selected board. `/setup` remains server-only, and server mappings, permissions, and paid-owner checks are unchanged.

User-installed apps respond only to explicit interactions. This integration does not add the bot as a Group DM participant, subscribe to Group DM messages, or parse arbitrary messages. Successful `/daily` submissions and `/leaderboard` results use mention-safe responses visible in the current conversation; command errors and account-management responses remain private. No deprecated `dm_permission` field is registered.

### Enable and install the Discord app for Group DMs

In the [Discord Developer Portal](https://discord.com/developers/applications), open the application and configure **Installation**:

1. Under **Installation Contexts**, enable both **User Install** and **Guild Install**.
2. Select **Discord Provided Link** as the install-link type.
3. Under **Default Install Settings**, give **User Install** the `applications.commands` scope.
4. Keep **Guild Install** configured with `applications.commands` and `bot`; keep the existing minimal bot permission (**View Channels**) used by server setup.
5. Keep the Interactions Endpoint URL set to `https://<api-host>/api/v1/discord/interactions`.

Copy the Discord Provided Link, open it while signed in to Discord, choose **Add to my apps**, and approve the installation. Open a Group DM, type `/` and select this app, then run `/help`. Link the invoking account with `/link code:<dashboard code>`. The leaderboard owner runs `/set-leaderboard leaderboard:<UUID>`; members can then test `/leaderboard game:<slug>` and `/daily result:<complete share text>` on the multi-game board without repeating the UUID. The bot identifies the game from the share text. Each linked user must already be a member of the selected leaderboard; `/add-member uuid:<profile UUID>` remains owner-only. Use `/unset-leaderboard` to remove the Group DM mapping.

Discord documents the installation-context setup in [Building your first Discord bot](https://docs.discord.com/developers/quick-start/getting-started#choosing-installation-contexts) and the command fields in [Application Commands](https://docs.discord.com/developers/docs/interactions/slash-commands).

### Register and deploy updated commands

The backend registrar runs shortly after startup when `DISCORD_COMMAND_REGISTRATION_ENABLED=true` and POST-upserts each changed global command by name. It does not bulk-overwrite or delete unrelated commands. To register manually from PowerShell with the same safe upsert behavior:

```powershell
$env:DISCORD_APPLICATION_ID = '<application-id>'
$env:DISCORD_BOT_TOKEN = '<bot-token>'
./scripts/register-discord-commands.ps1 -Scope Global -ConfirmGlobal
```

For an optional server-only smoke test before global registration:

```powershell
$env:DISCORD_TEST_GUILD_ID = '<test-server-id>'
./scripts/register-discord-commands.ps1 -Scope Guild
```

Build and validate the deployment artifact with `mvn test` and `mvn clean package`, deploy `target/daily-games-api-0.1.0.jar` with the existing Discord secrets, and restart the service. Global command changes can take time to propagate, while Discord may use read-repair when an older command definition is invoked.

Discord command registrations are tracked per application, command, and scope in PostgreSQL by Flyway V12. The scheduled check skips unchanged definitions across backend restarts and retries only commands that were never recorded or whose definitions changed. The first deployment of V12 will register the current commands once because earlier successful registrations were not recorded.

Set `DISCORD_COMMAND_REGISTRATION_ENABLED=false` to pause only automatic Discord command registration and retries. Restart the backend after changing it. Existing Discord commands and interaction handling continue to work; new or changed command definitions will not be published until the setting is restored to `true`.

Unlinking an owner's Discord account from the website or with `/unlink` disconnects server channels and Group DMs attached to leaderboards they own. Removing the last saved channel in a server stops guild command retries and clears that server's registration fingerprints; reconnecting can register commands again. Other owners' channels in the same server remain connected. Direct changes made only in Discord are not detected without reconnecting or removing the saved channel in Daily Tracker.

## Leaderboard types

Owners can share a reusable signup link from their leaderboard's player management area. `GET /api/v1/leaderboards/{id}/invite` shows the active link, `POST` to the same path creates it if needed, `POST /api/v1/leaderboards/{id}/invite/regenerate` replaces it, and `DELETE /api/v1/leaderboards/{id}/invite` revokes it. These operations require ownership. Public `GET /api/v1/invites/{token}` previews the leaderboard; authenticated `POST /api/v1/invites/{token}/redeem` adds the current profile idempotently. Creating and redeeming require an unlocked board. Links are signed with the stable `AUTH_TOKEN_PEPPER`, so keep that secret unchanged while links are in use. Deploy Flyway V11 and the backend endpoints before deploying the website `/join` page.

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

For Discord, deploy Flyway V10 and the backend secrets first, register the OAuth redirect URI, then deploy the frontend. The leaderboard setup button remains hidden until the backend reports that OAuth setup is configured. Set the signed interaction endpoint in the Discord Developer Portal. The backend registers global commands and reconciles connected servers after deployment. Smoke-test signup in connected and unconnected channels, verification in another browser, identity mismatch, channel replacement, owner/member/free/locked permissions, `/my-id`, `/add-member`, autocomplete, `/daily`, and `/leaderboard`.
