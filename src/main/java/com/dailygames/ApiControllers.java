package com.dailygames;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.*;
import com.stripe.net.Webhook;
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
  private final String baseUrl;
  private final boolean secureCookie;
  AuthController(AuthService auth, @Value("${app.public-base-url}") String baseUrl,
                 @Value("${app.secure-cookie}") boolean secureCookie) {
    this.auth = auth; this.baseUrl = baseUrl.replaceAll("/$", ""); this.secureCookie = secureCookie;
  }

  @PostMapping("/register")
  ResponseEntity<MessageResponse> register(@Valid @RequestBody CredentialsRequest request, HttpServletRequest servletRequest) {
    auth.register(request.email(), request.password(), clientIp(servletRequest));
    return ResponseEntity.accepted().body(new MessageResponse("Check your inbox for an email to verify or recover your account."));
  }

  @PostMapping("/login")
  ResponseEntity<AuthSessionResponse> login(@Valid @RequestBody CredentialsRequest request, HttpServletRequest servletRequest) {
    return authenticated(auth.login(request.email(), request.password(), clientIp(servletRequest)));
  }

  @PostMapping("/verification/resend")
  ResponseEntity<MessageResponse> resend(@Valid @RequestBody EmailRequest request, HttpServletRequest servletRequest) {
    auth.resendVerification(request.email(), clientIp(servletRequest));
    return ResponseEntity.accepted().body(new MessageResponse("If the account is awaiting verification, a new email is on its way."));
  }

  @GetMapping("/verification/consume")
  ResponseEntity<Void> verify(@RequestParam String token) {
    try {
      AuthService.LoginResult login = auth.verifyEmail(token);
      String destination = login.onboarded() ? "/dashboard" : "/onboarding";
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
  DailyGamesController(DailyGamesService service) { this.service = service; }

  @GetMapping("/me") MeResponse me(Authentication authentication) { return service.me(current(authentication)); }
  @PutMapping("/me/profile") ProfileView onboard(Authentication authentication, @Valid @RequestBody OnboardingRequest request) { return service.onboard(current(authentication), request); }
  @GetMapping("/dashboard") DashboardResponse dashboard(Authentication authentication) { return service.dashboard(current(authentication)); }
  @GetMapping("/profiles/{shareId}") ProfileResponse profile(Authentication authentication, @PathVariable UUID shareId) { return service.profile(current(authentication), shareId); }
  @GetMapping("/games") List<GameView> games(Authentication authentication) { current(authentication); return service.listGames(); }

  @GetMapping("/leaderboards") List<BoardSummary> boards(Authentication authentication) { return service.listBoards(current(authentication)); }
  @PostMapping("/leaderboards") ResponseEntity<IdResponse> createBoard(Authentication authentication, @Valid @RequestBody CreateLeaderboardRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(service.createBoard(current(authentication), request)));
  }
  @PostMapping("/leaderboards/{id}/copy") ResponseEntity<IdResponse> copyBoard(Authentication authentication, @PathVariable UUID id,
                                                                               @Valid @RequestBody CopyLeaderboardRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(service.copyBoard(current(authentication), id, request)));
  }
  @GetMapping("/leaderboards/{id}") LeaderboardResponse board(Authentication authentication, @PathVariable UUID id,
                                                               @RequestParam(defaultValue = "today") String range) {
    return service.board(current(authentication), id, range);
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

  private CurrentUser current(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current)) {
      throw new ApiException(401, "UNAUTHORIZED", "Sign in first.");
    }
    return current;
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
  @PostMapping("/checkout-session") UrlResponse checkout(Authentication authentication) {
    return billing.createCheckout(current(authentication).id());
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
  private final BillingService billing;
  private final String webhookSecret;
  StripeWebhookController(BillingService billing, @Value("${app.stripe.webhook-secret:}") String webhookSecret) {
    this.billing = billing; this.webhookSecret = webhookSecret;
  }

  @PostMapping(value = "/api/v1/billing/stripe/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<Void> webhook(@RequestBody String payload, @RequestHeader("Stripe-Signature") String signature) {
    if (webhookSecret.isBlank()) throw new ApiException(503, "BILLING_UNAVAILABLE", "Billing has not been configured yet.");
    try {
      Event event = Webhook.constructEvent(payload, signature, webhookSecret);
      StripeObject object = event.getDataObjectDeserializer().getObject()
          .orElseThrow(() -> new ApiException(400, "INVALID_STRIPE_EVENT", "Stripe event could not be decoded."));
      billing.process(event, object);
      return ResponseEntity.noContent().build();
    } catch (SignatureVerificationException exception) {
      throw new ApiException(400, "INVALID_STRIPE_SIGNATURE", "Stripe signature is invalid.");
    }
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
