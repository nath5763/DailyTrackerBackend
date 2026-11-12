package com.dailygames;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/letter-bag/today")
class LetterBagController {
  record WordRequest(List<Integer> ids) {}
  private final LetterBagService service;
  LetterBagController(LetterBagService service) { this.service = service; }
  private UUID user(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current))
      throw new ApiException(401, "UNAUTHORIZED", "Sign in first.");
    return current.id();
  }
  @GetMapping LetterBagService.View state(Authentication auth) { return service.state(user(auth)); }
  @PostMapping("/start") LetterBagService.View start(Authentication auth) { return service.start(user(auth)); }
  @PostMapping("/words") LetterBagService.View word(Authentication auth, @RequestBody WordRequest request) {
    return service.word(user(auth), request.ids());
  }
  @PostMapping("/finish") LetterBagService.View finish(Authentication auth) { return service.finish(user(auth)); }
}
