package com.dailygames;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
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

  @PostMapping("/magic-link")
  ResponseEntity<MessageResponse> request(@Valid @RequestBody MagicLinkRequest request, HttpServletRequest servletRequest) {
    auth.requestMagicLink(request.email(), clientIp(servletRequest));
    return ResponseEntity.accepted().body(new MessageResponse("Check your inbox. Your sign-in link is on its way."));
  }

  @GetMapping("/magic-link/consume")
  ResponseEntity<Void> consume(@RequestParam String token) {
    try {
      AuthService.LoginResult login = auth.consume(token);
      ResponseCookie cookie = ResponseCookie.from(AuthService.COOKIE_NAME, login.sessionToken()).httpOnly(true)
          .secure(secureCookie).sameSite("Lax").path("/").maxAge(auth.sessionSeconds()).build();
      String destination = login.onboarded() ? "/dashboard" : "/onboarding";
      return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(baseUrl + destination))
          .header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    } catch (ApiException exception) {
      return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(baseUrl + "/auth?error=invalid_or_expired")).build();
    }
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(HttpServletRequest request) {
    auth.logout(cookie(request));
    ResponseCookie cleared = ResponseCookie.from(AuthService.COOKIE_NAME, "").httpOnly(true).secure(secureCookie)
        .sameSite("Lax").path("/").maxAge(0).build();
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
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

  @GetMapping("/leaderboards") List<BoardSummary> boards(Authentication authentication) { return service.listBoards(current(authentication)); }
  @PostMapping("/leaderboards") ResponseEntity<IdResponse> createBoard(Authentication authentication, @Valid @RequestBody CreateLeaderboardRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(new IdResponse(service.createBoard(current(authentication), request)));
  }
  @GetMapping("/leaderboards/{id}") LeaderboardResponse board(Authentication authentication, @PathVariable UUID id,
                                                               @RequestParam(defaultValue = "today") String range,
                                                               @RequestParam(defaultValue = "fermi") String game) {
    return service.board(current(authentication), id, range, game);
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
class HealthController {
  private final JdbcTemplate jdbc;
  HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  @GetMapping("/api/health") Map<String, String> health() {
    Integer value = jdbc.queryForObject("select 1", Integer.class);
    if (!Objects.equals(value, 1)) throw new ApiException(503, "DATABASE_UNAVAILABLE", "Database is unavailable.");
    return Map.of("status", "ok");
  }
}
