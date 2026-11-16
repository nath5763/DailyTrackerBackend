package com.dailygames;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wechat/chat-tool")
class WeChatChatToolController {
  record ChatProof(@NotBlank @Size(max = 6000) String encryptedData,
                   @NotBlank @Size(max = 100) String iv) {}

  private final WeChatChatToolService service;
  WeChatChatToolController(WeChatChatToolService service) { this.service = service; }

  @GetMapping("/capabilities") WeChatChatToolService.Capabilities capabilities(Authentication authentication) {
    current(authentication);
    return service.capabilities();
  }

  @PostMapping("/context") WeChatService.GroupResult context(Authentication authentication,
      @Valid @RequestBody ChatProof proof) {
    return service.resolve(current(authentication).id(), token(authentication), proof.encryptedData(), proof.iv());
  }

  @PostMapping("/cards/{leaderboardId}") WeChatChatToolService.ChatShare card(Authentication authentication,
      @PathVariable UUID leaderboardId) {
    return service.prepareCard(current(authentication).id(), token(authentication), leaderboardId);
  }

  @PostMapping("/cards/{cardId}/shared") ResponseEntity<Void> shared(Authentication authentication,
      @PathVariable UUID cardId) {
    service.shared(current(authentication).id(), token(authentication), cardId);
    return ResponseEntity.noContent().build();
  }

  private CurrentUser current(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user))
      throw new ApiException(401, "UNAUTHORIZED", "Sign in first.");
    return user;
  }
  private String token(Authentication authentication) {
    return (String) authentication.getCredentials();
  }
}
