package com.dailygames;

import jakarta.servlet.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/v1/bloom-and-boom/today")
class BloomAndBoomController {
  record RevealRequest(int row, int column) {}
  private static final String COOKIE = "bloom_boom_guest";
  private final BloomAndBoomService service;
  BloomAndBoomController(BloomAndBoomService service) { this.service = service; }

  @GetMapping BloomAndBoomService.Today today(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
    return service.today(owner(auth, request, response));
  }
  @PostMapping("/start") BloomAndBoomService.Today start(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
    return service.start(owner(auth, request, response));
  }
  @PostMapping("/reveal") BloomAndBoomService.Today reveal(Authentication auth, HttpServletRequest request,
      HttpServletResponse response, @RequestBody RevealRequest reveal) {
    return service.reveal(owner(auth, request, response), new BloomAndBoomPuzzle.Position(reveal.row(), reveal.column()));
  }

  private BloomAndBoomService.Owner owner(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
    if (auth != null && auth.getPrincipal() instanceof CurrentUser current)
      return new BloomAndBoomService.Owner(current.id(), null);
    String token = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
        .filter(cookie -> COOKIE.equals(cookie.getName())).map(Cookie::getValue).findFirst().orElse(null);
    if (token == null || !token.matches("[A-Za-z0-9_-]{32,80}")) {
      token = Base64.getUrlEncoder().withoutPadding().encodeToString(random());
      Cookie cookie = new Cookie(COOKIE, token); cookie.setHttpOnly(true); cookie.setSecure(request.isSecure());
      cookie.setPath("/"); cookie.setMaxAge((int) Duration.ofDays(400).toSeconds()); cookie.setAttribute("SameSite", "Lax");
      response.addCookie(cookie);
    }
    return new BloomAndBoomService.Owner(null, sha256(token));
  }
  private static byte[] random() { byte[] value = new byte[32]; new java.security.SecureRandom().nextBytes(value); return value; }
  private static String sha256(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception exception) { throw new IllegalStateException(exception); }
  }
}
