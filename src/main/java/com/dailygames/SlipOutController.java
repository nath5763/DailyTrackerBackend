package com.dailygames;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/slipout/today")
class SlipOutController {
  record FinishRequest(UUID attemptId, List<SlipOutPuzzle.Move> moves) {}
  private final SlipOutService service;
  SlipOutController(SlipOutService service) { this.service = service; }
  private UUID user(Authentication auth) {
    if (auth == null || !(auth.getPrincipal() instanceof CurrentUser current))
      throw new ApiException(401, "UNAUTHORIZED", "Sign in first.");
    return current.id();
  }
  @GetMapping SlipOutService.Today today(Authentication auth) {
    UUID user = auth != null && auth.getPrincipal() instanceof CurrentUser current ? current.id() : null;
    return service.today(user);
  }
  @PostMapping("/start") SlipOutService.Started start(Authentication auth) { return service.start(user(auth)); }
  @PostMapping("/finish") SlipOutService.Finished finish(Authentication auth, @RequestBody FinishRequest request) {
    return service.finish(user(auth), request.attemptId(), request.moves());
  }
}
