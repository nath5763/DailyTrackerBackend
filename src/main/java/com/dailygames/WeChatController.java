package com.dailygames;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wechat")
class WeChatController {
  record LoginRequest(@NotBlank @Size(max = 128) String code) {}
  record LinkCodeRequest(@NotBlank @Size(max = 32) String code) {}
  record GroupProofRequest(@NotBlank @Size(max = 6000) String encryptedData,
                           @NotBlank @Size(max = 100) String iv) {}
  record GroupLinkRequest(@NotNull UUID leaderboardId, boolean confirmed) {}
  record SessionResponse(String token, boolean onboarded, boolean linkedAccount) {}
  record Capabilities(boolean enabled, boolean groupContextEnabled, boolean chatToolEnabled) {}

  private final WeChatService service;
  WeChatController(WeChatService service) { this.service = service; }

  @GetMapping("/capabilities") Capabilities capabilities() {
    return new Capabilities(service.available(), service.groupAvailable(), service.chatToolAvailable());
  }

  @PostMapping("/session") SessionResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
    WeChatService.SessionResult result = service.login(request.code(), servletRequest.getRemoteAddr());
    return new SessionResponse(result.token(), result.onboarded(), result.linkedAccount());
  }

  @PostMapping("/link-codes") WeChatService.CodeResult issueCode(Authentication authentication) {
    return service.issueCode(user(authentication).id(), token(authentication));
  }

  @GetMapping("/status") WeChatService.Status status(Authentication authentication) {
    return service.status(user(authentication).id());
  }

  @PostMapping("/link-codes/redeem") SessionResponse redeem(Authentication authentication,
      @Valid @RequestBody LinkCodeRequest request) {
    WeChatService.SessionResult result = service.redeemCode(user(authentication).id(), token(authentication), request.code());
    return new SessionResponse(result.token(), result.onboarded(), result.linkedAccount());
  }

  @DeleteMapping("/identity") ResponseEntity<Void> unlink(Authentication authentication) {
    service.unlink(user(authentication).id());
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/shares/{leaderboardId}") WeChatService.ShareResult share(Authentication authentication,
      @PathVariable UUID leaderboardId) {
    return service.createShare(user(authentication).id(), leaderboardId);
  }

  @GetMapping("/shares/{reference}") WeChatService.SharePreview preview(Authentication authentication,
      @PathVariable String reference) {
    return service.previewShare(user(authentication).id(), reference);
  }

  @PostMapping("/shares/{reference}/join") ResponseEntity<ApiModels.IdResponse> join(Authentication authentication,
      @PathVariable String reference) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new ApiModels.IdResponse(service.joinShare(user(authentication).id(), reference)));
  }

  @PostMapping("/group-context/resolve") WeChatService.GroupResult resolve(Authentication authentication,
      @Valid @RequestBody GroupProofRequest request) {
    return service.resolveGroup(user(authentication).id(), token(authentication), request.encryptedData(), request.iv());
  }

  @GetMapping("/group-context/current") WeChatService.GroupResult current(Authentication authentication) {
    return service.currentGroup(user(authentication).id(), token(authentication));
  }

  @PostMapping("/group-links") WeChatService.GroupLinkView link(Authentication authentication,
      @Valid @RequestBody GroupLinkRequest request) {
    return service.linkGroup(user(authentication).id(), token(authentication), request.leaderboardId(), request.confirmed());
  }

  @GetMapping("/leaderboards/{leaderboardId}/group-links") List<WeChatService.GroupLinkView> links(
      Authentication authentication, @PathVariable UUID leaderboardId) {
    return service.boardGroups(user(authentication).id(), leaderboardId);
  }

  @DeleteMapping("/group-links/{id}") ResponseEntity<Void> disconnect(Authentication authentication, @PathVariable UUID id) {
    service.disconnectGroup(user(authentication).id(), id);
    return ResponseEntity.noContent().build();
  }

  private CurrentUser user(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser current))
      throw new ApiException(401, "UNAUTHORIZED", "Sign in first.");
    return current;
  }
  private String token(Authentication authentication) {
    return authentication == null ? null : (String) authentication.getCredentials();
  }
}
