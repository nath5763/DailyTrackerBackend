package com.dailygames;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Deployment-scoped snapshot of the tracked game catalog.
 *
 * Games are provisioned by Flyway, so a backend restart is the intentional
 * refresh boundary. Leaderboards, memberships, and Discord channel mappings
 * remain live database state and are not cached here.
 */
@Component
class GameCatalog implements GameLookup {
  private static final Comparator<Game> BY_NAME = Comparator
      .comparing((Game game) -> game.name, String.CASE_INSENSITIVE_ORDER)
      .thenComparing(game -> game.slug);

  private final List<Game> all;
  private final List<Game> active;
  private final Map<UUID, Game> byId;
  private final Map<String, Game> activeBySlug;

  @Autowired
  GameCatalog(GameRepository repository) {
    this(repository.findAll());
  }

  GameCatalog(Collection<Game> games) {
    ArrayList<Game> sorted = new ArrayList<>(games);
    sorted.sort(BY_NAME);
    all = List.copyOf(sorted);
    active = all.stream().filter(game -> game.active).toList();

    Map<UUID, Game> ids = new LinkedHashMap<>();
    Map<String, Game> slugs = new LinkedHashMap<>();
    for (Game game : all) {
      ids.put(game.id, game);
      if (game.active) slugs.put(game.slug, game);
    }
    byId = Map.copyOf(ids);
    activeBySlug = Map.copyOf(slugs);
  }

  List<Game> allGames() {
    return all;
  }

  @Override public List<Game> activeGames() {
    return active;
  }

  @Override public Optional<Game> findById(UUID id) {
    return Optional.ofNullable(byId.get(id));
  }

  @Override public Optional<Game> findActiveBySlug(String slug) {
    return Optional.ofNullable(activeBySlug.get(slug));
  }
}

interface GameLookup {
  List<Game> activeGames();
  Optional<Game> findById(UUID id);
  Optional<Game> findActiveBySlug(String slug);
}

/** Keeps existing unit tests focused on service behavior rather than cache construction. */
final class RepositoryGameLookup implements GameLookup {
  private final GameRepository repository;

  RepositoryGameLookup(GameRepository repository) {
    this.repository = repository;
  }

  @Override public List<Game> activeGames() {
    return repository.findByActiveTrueOrderByNameAsc();
  }

  @Override public Optional<Game> findById(UUID id) {
    return repository.findById(id);
  }

  @Override public Optional<Game> findActiveBySlug(String slug) {
    return repository.findBySlugAndActiveTrue(slug);
  }
}
