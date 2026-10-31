package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dailygames.ApiModels.*;

record ParsedSubmission(String puzzleKey, Integer puzzleNumber, LocalDate playedOn, BigDecimal primaryScore,
                        Map<String, Object> metrics, int parserVersion) {}

interface GameAdapter {
  String slug();
  ScoreFormatView scoreFormat();
  SubmissionConfigView submissionConfig();
  ParsedSubmission parse(String rawText);
  ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText);
}

@Component
class GameAdapterRegistry {
  private final Map<String, GameAdapter> adapters;

  GameAdapterRegistry(List<GameAdapter> adapters) {
    this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(GameAdapter::slug, Function.identity(),
        (left, right) -> { throw new IllegalStateException("Duplicate game adapter for " + left.slug()); }));
  }

  GameAdapter require(String slug) {
    GameAdapter adapter = adapters.get(slug);
    if (adapter == null) throw new ApiException(503, "GAME_UNAVAILABLE", "Game configuration is missing.");
    return adapter;
  }
}
