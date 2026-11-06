package com.dailygames;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.*;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

import static com.dailygames.ApiModels.*;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
  private final AuthService auth;
  private final DiscordSignupService signup;
  private final LeaderboardInviteService invites;
  private final String baseUrl;
  private final boolean secureCookie;
  AuthController(AuthService auth, DiscordSignupService signup, LeaderboardInviteService invites, @Value("${app.public-base-url}") String baseUrl,
                 @Value("${app.secure-cookie}") boolean secureCookie) {
    this.auth = auth; this.signup = signup; this.invites = invites; this.baseUrl = baseUrl.replaceAll("/$", ""); this.secureCookie = secureCookie;
  }

  @PostMapping("/register")
  ResponseEntity<MessageResponse> register(@Valid @RequestBody CredentialsRequest request,
      @RequestParam(required = false) String signupIntent, @RequestParam(required = false) String joinInvite,
      HttpServletRequest servletRequest) {
    if (signupIntent != null && !signup.valid(signupIntent)) throw new ApiException(410, "SIGNUP_EXPIRED", "This Discord signup link has expired.");
    if (joinInvite != null) invites.preview(joinInvite);
    auth.register(request.email(), request.password(), clientIp(servletRequest), signupIntent, joinInvite);
    return ResponseEntity.accepted().body(new MessageResponse("Check your inbox for an email to verify or recover your account."));
  }

  @PostMapping("/login")
  ResponseEntity<AuthSessionResponse> login(@Valid @RequestBody CredentialsRequest request, HttpServletRequest servletRequest) {
    return authenticated(auth.login(request.email(), request.password(), clientIp(servletRequest)));
  }

  @PostMapping("/verification/resend")
  ResponseEntity<MessageResponse> resend(@Valid @RequestBody EmailRequest request,
      @RequestParam(required = false) String signupIntent, @RequestParam(required = false) String joinInvite,
      HttpServletRequest servletRequest) {
    if (signupIntent != null && !signup.valid(signupIntent)) throw new ApiException(410, "SIGNUP_EXPIRED", "This Discord signup link has expired.");
    if (joinInvite != null) invites.preview(joinInvite);
    auth.resendVerification(request.email(), clientIp(servletRequest), signupIntent, joinInvite);
    return ResponseEntity.accepted().body(new MessageResponse("If the account is awaiting verification, a new email is on its way."));
  }

  @GetMapping("/verification/consume")
  ResponseEntity<Void> verify(@RequestParam String token, @RequestParam(required = false) String signupIntent,
      @RequestParam(required = false) String joinInvite) {
    try {
      AuthService.LoginResult login = auth.verifyEmail(token);
      String next = joinInvite != null && joinInvite.matches("[0-9a-fA-F-]{36}\\.[A-Za-z0-9_-]{43}")
          ? "/join?invite=" + joinInvite
          : signupIntent != null && signup.valid(signupIntent) ? "/discord/signup?intent=" + signupIntent : "/dashboard";
      String destination = login.onboarded() ? next : "/onboarding?next=" + java.net.URLEncoder.encode(next, java.nio.charset.StandardCharsets.UTF_8);
      return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(baseUrl + destination))
          .header(HttpHeaders.SET_COOKIE, sessionCookie(login.sessionToken()).toString()).build();
    } catch (ApiException exception) {
      return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(baseUrl + "/auth?error=invalid_verification")).build();
    }
  }

  @PostMapping("/password/forgot")
  ResponseEntity<MessageResponse> forgot(@Valid @RequestBody EmailRequest request, HttpServletRequest servletRequest) {
    auth.forgotPassword(request.email(), clientIp(servletRequest));
    return ResponseEntity.accepted().body(new MessageResponse("If that account exists, a password reset email is on its way."));
  }

  @PostMapping("/password/reset")
  ResponseEntity<AuthSessionResponse> reset(@Valid @RequestBody ResetPasswordRequest request) {
    return authenticated(auth.resetPassword(request.token(), request.password()));
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(HttpServletRequest request) {
    auth.logout(cookie(request));
    ResponseCookie cleared = ResponseCookie.from(AuthService.COOKIE_NAME, "").httpOnly(true).secure(secureCookie)
        .sameSite("Lax").path("/").maxAge(0).build();
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
  }

  private ResponseEntity<AuthSessionResponse> authenticated(AuthService.LoginResult login) {
    return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, sessionCookie(login.sessionToken()).toString())
        .body(new AuthSessionResponse(login.onboarded()));
  }

  private ResponseCookie sessionCookie(String token) {
    return ResponseCookie.from(AuthService.COOKIE_NAME, token).httpOnly(true).secure(secureCookie)
        .sameSite("Lax").path("/").maxAge(auth.sessionSeconds()).build();
  }

  private String cookie(HttpServletRequest request) {
    return request.getCookies() == null ? null : Arrays.stream(request.getCookies())
        .filter(value -> AuthService.COOKIE_NAME.equals(value.getName())).map(Cookie::getValue).findFirst().orElse(null);
  }
  private String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded.split(",", 2)[0].trim();
  }
}

@RestController
@RequestMapping("/api/v1")
class DailyGamesController {
  private final DailyGamesService service;
  private final LeaderboardInviteService invites;
  DailyGamesController(DailyGamesService service, LeaderboardInviteService invites) { this.service = service; this.invites = invites; }

  @GetMapping("/me") MeResponse me(Authentication authentication) { return service.me(current(authentication)); }
  @PutMapping("/me/profile") ProfileView onboard(Authentication authentication, @Valid @RequestBody OnboardingRequest request) { return service.onboard(current(authentication), request); }
  @GetMapping("/dashboard") DashboardResponse dashboard(Authentication authentication) { return service.dashboard(current(authentication)); }
  @GetMapping("/profiles/{shareId}") ProfileResponse profile(Authentication authentication, @PathVariable UUID shareId) { return service.profile(current(authentication), shareId); }
  @GetMapping("/games") List<GameView> games(Authentication authentication) { current(authentication); return service.listGames(); }

  @GetMapping("/leaderboards") List<BoardSummary> boards(Authentication authentication) { return service.listBoards(current(authentication)); }
  @GetMapping("/leaderboards/{id}/invite") LeaderboardInviteService.InviteLink invite(Authentication authentication, @PathVariable UUID id) {
    return invites.get(id, current(authentication).id());
  }
  @PostMapping("/leaderboards/{id}/invite") LeaderboardInviteService.InviteLink createInvite(Authentication authentication, @PathVariable UUID id) {
    return invites.create(id, current(authentication).id());
  }
  @PostMapping("/leaderboards/{id}/invite/regenerate") LeaderboardInviteService.InviteLink regenerateInvite(Authentication authentication, @PathVariable UUID id) {
    return invites.regenerate(id, current(authentication).id());
  }
  @DeleteMapping("/leaderboards/{id}/invite") ResponseEntity<Void> revokeInvite(Authentication authentication, @PathVariable UUID id) {
    invites.revoke(id, current(authentication).id()); return ResponseEntity.noContent().build();
  }
  @PostMapping("/invites/{token}/redeem") IdResponse redeemInvite(Authentication authentication, @PathVariable String token) {
    return new IdResponse(invites.redeem(token, current(authentication).id()));
  }
  @PostMapping("/leaderboards") ResponseEntity<IdResponse> createBoard(Authentication authentication, @Valid @RequestBody CreateLeaderboardRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(service.createBoard(current(authentication), request)));
  }
  @PostMapping("/leaderboards/{id}/copy") ResponseEntity<IdResponse> copyBoard(Authentication authentication, @PathVariable UUID id,
                                                                               @Valid @RequestBody CopyLeaderboardRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(service.copyBoard(current(authentication), id, request)));
  }
  @PostMapping("/leaderboards/{id}/convert-to-multi-game") ResponseEntity<Void> convertBoard(
      Authentication authentication, @PathVariable UUID id) {
    service.convertToMultiGame(current(authentication), id);
    return ResponseEntity.noContent().build();
  }
  @GetMapping("/leaderboards/{id}") LeaderboardResponse board(Authentication authentication, @PathVariable UUID id,
                                                               @RequestParam(defaultValue = "today") String range,
                                                               @RequestParam(required = false) String game) {
    return service.board(current(authentication), id, range, game);
  }
  @GetMapping("/leaderboards/{id}/insights") LeaderboardInsightsResponse insights(Authentication authentication,
      @PathVariable UUID id, @RequestParam(required = false) String game) {
    return service.insights(current(authentication), id, game);
  }
  @PostMapping("/leaderboards/{id}/members") ResponseEntity<Void> addMember(Authentication authentication, @PathVariable UUID id,
                                                                              @Valid @RequestBody AddMemberRequest request) {
    service.addMember(current(authentication), id, request.shareId());
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }
  @DeleteMapping("/leaderboards/{id}/members/{userId}") ResponseEntity<Void> removeMember(Authentication authentication, @PathVariable UUID id, @PathVariable UUID userId) {
    service.removeMember(current(authentication), id, userId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/submissions/preview") PreviewResponse preview(Authentication authentication, @Valid @RequestBody PreviewSubmissionRequest request) {
    return new PreviewResponse(service.preview(current(authentication), request));
  }
  @PostMapping("/submissions") ResponseEntity<IdResponse> submit(Authentication authentication, @Valid @RequestBody CreateSubmissionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(service.submit(current(authentication), request)));
  }
  @PostMapping("/submissions/manual") ResponseEntity<IdResponse> submitManual(Authentication authentication,
      @Valid @RequestBody ManualSubmissionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(service.submitManual(current(authentication), request)));
  }
  @PostMapping("/submissions/manual/preview") PreviewResponse previewManual(Authentication authentication,
      @Valid @RequestBody ManualSubmissionRequest request) {
    return new PreviewResponse(service.previewManual(current(authentication), request));
  }

  private CurrentUser current(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
      throw new ApiException(401, "UNAUTHORIZED", "Sign in first.");
    }
    return current;
  }
}

@RestController
@RequestMapping("/api/v1/invites")
class LeaderboardInviteController {
  private final LeaderboardInviteService invites;
  LeaderboardInviteController(LeaderboardInviteService invites) { this.invites = invites; }
  @GetMapping("/{token}") LeaderboardInviteService.InvitePreview preview(@PathVariable String token) {
    return invites.preview(token);
  }
}

@RestController
@RequestMapping("/api/v1/catalog/games")
class CatalogController {
  private final CatalogGameRepository catalog;
  private final GameRepository games;
  private final GameAdapterRegistry adapters;
  CatalogController(CatalogGameRepository catalog, GameRepository games, GameAdapterRegistry adapters) {
    this.catalog = catalog; this.games = games; this.adapters = adapters;
  }
  @GetMapping List<CatalogGameView> games() {
    Set<String> tracked = games.findByActiveTrueOrderByNameAsc().stream().map(game -> game.slug).collect(java.util.stream.Collectors.toSet());
    return catalog.findAllByOrderByDisplayOrderAsc().stream().map(game -> new CatalogGameView(
        game.slug, game.name, game.description, game.category, game.sourceUrl,
        game.trackedGameSlug != null && tracked.contains(game.trackedGameSlug))).toList();
  }
  @GetMapping("/parser-capabilities") List<ParserCapabilityView> parserCapabilities() {
    return adapters.capabilities();
  }
  @GetMapping("/score-strategies") List<ScoreStrategyView> scoreStrategies() {
    return adapters.scoreStrategies().stream().map(strategy -> new ScoreStrategyView(strategy.gameId(),
        strategy.rankingRule(), strategy.primaryMetric(), strategy.primaryDirection().name(),
        strategy.secondaryMetric(), strategy.secondaryDirection() == null ? null : strategy.secondaryDirection().name(),
        strategy.compatibilityFields(), strategy.completionOnlyAllowed(), strategy.completionPolicy().name())).toList();
  }
}

@RestController
@RequestMapping("/api/v1/billing")
class BillingController {
  private final BillingService billing;
  BillingController(BillingService billing) { this.billing = billing; }

  @GetMapping("/status") BillingStatusResponse status(Authentication authentication) {
    return billing.status(current(authentication).id());
  }
  @PostMapping("/checkout-session") UrlResponse checkout(Authentication authentication, @Valid @RequestBody CheckoutRequest request) {
    return billing.createCheckout(current(authentication).id(), request.tier());
  }
  @PostMapping("/portal-session") UrlResponse portal(Authentication authentication) {
    return billing.createPortal(current(authentication).id());
  }

  private CurrentUser current(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
      throw new ApiException(401, "UNAUTHORIZED", "Sign in first.");
    }
    return current;
  }
}

@RestController
class StripeWebhookController {
  private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);
  private final BillingService billing;
  private final String webhookSecret;
  StripeWebhookController(BillingService billing, @Value("${app.stripe.webhook-secret:}") String webhookSecret) {
    this.billing = billing; this.webhookSecret = webhookSecret;
  }

  @PostMapping(value = "/api/v1/billing/stripe/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> webhook(@RequestBody String payload, @RequestHeader("Stripe-Signature") String signature) {
    if (webhookSecret.isBlank()) throw new ApiException(503, "BILLING_UNAVAILABLE", "Billing has not been configured yet.");
    Event event;
    try {
      event = Webhook.constructEvent(payload, signature, webhookSecret);
    } catch (SignatureVerificationException exception) {
      log.warn("Rejected Stripe webhook with an invalid signature");
      throw new ApiException(400, "INVALID_STRIPE_SIGNATURE", "Stripe signature is invalid.");
    } catch (RuntimeException exception) {
      log.warn("Rejected malformed Stripe webhook", exception);
      throw new ApiException(400, "INVALID_STRIPE_EVENT", "Stripe event is malformed.");
    }
    if (!billing.supports(event.getType())) {
      billing.process(event, null);
      return ResponseEntity.noContent().build();
    }
    StripeObject object = event.getDataObjectDeserializer().getObject()
        .orElseThrow(() -> new ApiException(500, "STRIPE_EVENT_DECODING_FAILED",
            "Stripe event could not be decoded and will be retried."));
    billing.process(event, object);
    return ResponseEntity.noContent().build();
  }
}

@RestController
class HealthController {
  private final JdbcTemplate jdbc;
  HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  @GetMapping("/api/health") Map<String, String> health() {
    Integer value = jdbc.queryForObject("select 1", Integer.class);
    if (!Objects.equals(value, 1)) throw new ApiException(503, "DATABASE_UNAVAILABLE", "Database is unavailable.");
    return Map.of("status", "ok");
  }
}
