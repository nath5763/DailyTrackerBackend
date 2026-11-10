package com.dailygames;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class LeaderboardDeletionService {
  private final LeaderboardRepository boards;
  private final DiscordSignupIntentRepository signupIntents;
  private final DiscordCommandRegistrationStore registrations;

  LeaderboardDeletionService(LeaderboardRepository boards, DiscordSignupIntentRepository signupIntents,
                             DiscordCommandRegistrationStore registrations) {
    this.boards = boards;
    this.signupIntents = signupIntents;
    this.registrations = registrations;
  }

  @Transactional
  void delete(UUID boardId, UUID userId) {
    Leaderboard board = boards.findByIdForUpdate(boardId)
        .orElseThrow(() -> new ApiException(404, "LEADERBOARD_NOT_FOUND", "Leaderboard not found."));
    if (!board.ownerId.equals(userId)) {
      throw new ApiException(403, "NOT_OWNER", "Only the leaderboard owner can do that.");
    }

    signupIntents.deleteByLeaderboardId(boardId);
    boards.delete(board);
    boards.flush();
    registrations.pruneUnmappedGuilds();
  }
}
