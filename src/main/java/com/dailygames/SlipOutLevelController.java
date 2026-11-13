package com.dailygames;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/slipout/levels")
class SlipOutLevelController {
  record FinishRequest(UUID attemptId, List<SlipOutLevels.Move> moves) {}
  private final SlipOutLevelService service;
  SlipOutLevelController(SlipOutLevelService service) { this.service = service; }
  private UUID user(Authentication auth) {
    if (auth == null || !(auth.getPrincipal() instanceof CurrentUser current))
      throw new ApiException(401, "UNAUTHORIZED", "Sign in first.");
    return current.id();
  }
  @GetMapping SlipOutLevelService.Catalog catalog(Authentication auth) {
    return service.catalog(auth != null && auth.getPrincipal() instanceof CurrentUser current ? current.id() : null);
  }
  @PostMapping("/{level}/start") SlipOutLevelService.Started start(Authentication auth, @PathVariable int level) {
    return service.start(user(auth), level);
  }
  @PostMapping("/finish") SlipOutLevelService.Finished finish(Authentication auth, @RequestBody FinishRequest request) {
    return service.finish(user(auth), request.attemptId(), request.moves());
  }
  @PostMapping("/import") List<SlipOutLevelService.Progress> importGuest(Authentication auth,
      @RequestBody List<SlipOutLevelService.ImportResult> results) { return service.importGuest(user(auth), results); }
}
