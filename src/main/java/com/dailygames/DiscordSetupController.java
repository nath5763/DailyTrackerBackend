package com.dailygames;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/discord")
class DiscordSetupController {
  private static final Logger log = LoggerFactory.getLogger(DiscordSetupController.class);
  private final DiscordSetupService setup;
  DiscordSetupController(DiscordSetupService setup) { this.setup = setup; }

  @GetMapping("/setup-status")
  ResponseEntity<Map<String, Boolean>> status() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("configured", setup.configured()));
  }

  @PostMapping("/leaderboards/{boardId}/authorization")
  ResponseEntity<Map<String, String>> start(Authentication authentication, @PathVariable UUID boardId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(setup.start(current(authentication), boardId));
  }

  @GetMapping("/oauth/callback")
  ResponseEntity<Void> callback(Authentication authentication, @RequestParam(required = false) String state,
      @RequestParam(required = false) String code, @RequestParam(required = false) String error,
      @RequestParam(name = "guild_id", required = false) String guildId) {
    UUID userId = authentication != null && authentication.getPrincipal() instanceof CurrentUser user ? user.id() : null;
    String location;
    try { location = setup.callback(userId, state, code, error, guildId); }
    catch (ApiException exception) { location = setup.failureRedirect(state, exception); }
    catch (RuntimeException exception) {
      log.error("Discord OAuth callback failed", exception);
      location = "/leaderboards?discord=failed";
    }
    return ResponseEntity.status(303).location(URI.create(location)).cacheControl(CacheControl.noStore()).build();
  }

  @GetMapping("/leaderboards/{boardId}/channels")
  ResponseEntity<List<DiscordChannelView>> connected(Authentication authentication, @PathVariable UUID boardId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(setup.connected(current(authentication), boardId));
  }

  @GetMapping("/leaderboards/{boardId}/setup/channels")
  ResponseEntity<List<DiscordChannelView>> available(Authentication authentication, @PathVariable UUID boardId,
      @RequestParam String setupToken) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .body(setup.available(current(authentication), boardId, setupToken));
  }

  @PostMapping("/leaderboards/{boardId}/setup/channel")
  ResponseEntity<DiscordChannelView> save(Authentication authentication, @PathVariable UUID boardId,
      @RequestBody SaveDiscordChannelRequest request) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(setup.save(current(authentication), boardId,
        request.setupToken(), request.channelId(), request.replace()));
  }

  private UUID current(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user))
      throw new ApiException(401, "UNAUTHORIZED", "Sign in first.");
    return user.id();
  }
}

record SaveDiscordChannelRequest(String setupToken, String channelId, boolean replace) {}
