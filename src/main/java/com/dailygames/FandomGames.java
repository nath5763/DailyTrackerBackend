package com.dailygames;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

import static com.dailygames.ApiModels.*;

enum FandomScoreKind { ATTEMPTS, CONNECTION_MISTAKES, GRID_RARITY }

record FandomGameDefinition(String slug, String name, String description, String category, String sourceUrl,
                            FandomScoreKind scoreKind, boolean hasModes) {}

/**
 * Available fandom games promoted from additional_daily_games.md. Clipboard parsing is intentionally disabled until an
 * attributable share fixture is available; these definitions provide verified native manual metrics meanwhile.
 */
final class FandomGames {
  private FandomGames() {}

  static final List<FandomGameDefinition> ALL = List.of(
      game("squirdle", "Squirdle", "Guess a Pokémon using generation, type, height, and weight.", "Pokémon", "https://squirdle.fireblend.com/", FandomScoreKind.ATTEMPTS, false),
      game("pokedoku", "PokeDoku", "Fill a 3×3 grid with Pokémon matching row and column categories.", "Pokémon", "https://pokedoku.com/", FandomScoreKind.GRID_RARITY, false),
      game("pokennection", "Pokennection", "Sort Pokémon-related clues into related groups.", "Pokémon", "https://pokennection.com/", FandomScoreKind.CONNECTION_MISTAKES, true),
      game("pokegrid", "PokeGrid", "Fill a Pokémon trivia grid; uncommon answers score better.", "Pokémon", "https://pokegrid.net/", FandomScoreKind.GRID_RARITY, false),
      game("weakdle", "Weakdle", "Deduce a mystery Pokémon type from matchup clues.", "Pokémon", "https://dentotino.com/", FandomScoreKind.ATTEMPTS, false),
      game("dotadle", "Dotadle", "Guess Dota 2 heroes using attributes, voice lines, and abilities.", "Video Games", "https://dotadle.net/", FandomScoreKind.ATTEMPTS, true),
      game("valodle", "Valodle", "Guess Valorant agents, abilities, maps, and weapon skins.", "Video Games", "https://valodle.eu/", FandomScoreKind.ATTEMPTS, true),
      game("genshindle", "Genshindle", "Identify Genshin Impact characters from attributes, weapons, and quotes.", "Video Games", "https://us.genshindle.com/", FandomScoreKind.ATTEMPTS, true),
      game("smashdle", "Smashdle", "Guess Super Smash Bros. fighters from properties, silhouettes, and moves.", "Video Games", "https://smashdle.net/", FandomScoreKind.ATTEMPTS, true),
      game("mcdle", "MCdle", "Guess Minecraft mobs, blocks, and items.", "Video Games", "https://mcdle.net/", FandomScoreKind.ATTEMPTS, true),
      game("minecraftle", "Minecraftle", "Deduce a Minecraft crafting recipe from a 3×3 ingredient grid.", "Video Games", "https://minecraftle.zachmanson.com/", FandomScoreKind.ATTEMPTS, false),
      game("craftconnections", "CraftConnections", "Group Minecraft items into related sets.", "Video Games", "https://craftconnections.net/", FandomScoreKind.CONNECTION_MISTAKES, false),
      game("balatrodle", "Balatrodle", "Guess the daily Balatro Joker card.", "Video Games", "https://balatrodle.com/", FandomScoreKind.ATTEMPTS, false),
      game("stardewdle", "Stardewdle", "Identify Stardew Valley crops using their statistics.", "Video Games", "https://stardewdle.com/", FandomScoreKind.ATTEMPTS, false),
      game("pufferdle", "Pufferdle", "Guess Stardew Valley fish using properties and clues.", "Video Games", "https://pufferdle.com/", FandomScoreKind.ATTEMPTS, false),
      game("clasherdle", "Clasherdle", "Guess Clash Royale troops, spells, or buildings.", "Video Games", "https://clasherdle.pixelcrux.com/", FandomScoreKind.ATTEMPTS, true),
      game("royaledle", "Royaledle", "Guess Clash Royale cards from statistics, emojis, images, or descriptions.", "Video Games", "https://royaledle.com/", FandomScoreKind.ATTEMPTS, true),
      game("fortnitedle", "Fortnitedle", "Identify Fortnite skins, weapons, emotes, and pickaxes.", "Video Games", "https://fortnitedle.net/", FandomScoreKind.ATTEMPTS, true),
      game("r6dle", "R6dle", "Guess Rainbow Six Siege operators using properties and equipment.", "Video Games", "https://r6dle.net/", FandomScoreKind.ATTEMPTS, true),
      game("tftdle", "TFTdle", "Identify Teamfight Tactics units, traits, abilities, and augments.", "Video Games", "https://tftdle.gg/", FandomScoreKind.ATTEMPTS, true),
      game("tf2dle", "TF2dle", "Guess Team Fortress 2 weapons, cosmetics, and maps.", "Video Games", "https://tf2dle.com/", FandomScoreKind.ATTEMPTS, true),
      game("wowdle", "WoWdle", "Identify World of Warcraft characters, zones, and related content.", "Video Games", "https://wowdle.app/", FandomScoreKind.ATTEMPTS, true),
      game("runedle", "Runedle", "Guess RuneScape NPCs using race, location, and combat attributes.", "Video Games", "https://runedle.com/", FandomScoreKind.ATTEMPTS, false),
      game("spiredle", "Spiredle", "Guess Slay the Spire cards using cost, rarity, color, and effects.", "Video Games", "https://spiredle.com/", FandomScoreKind.ATTEMPTS, false),
      game("terradle", "Terradle", "Guess Terraria weapons using item statistics and progressive hints.", "Video Games", "https://terradle.com/", FandomScoreKind.ATTEMPTS, false),
      game("streetfighterdle", "StreetFighterdle", "Identify Street Fighter characters from debut, country, and fighting style.", "Video Games", "https://streetfighterdle.net/", FandomScoreKind.ATTEMPTS, false),
      game("ace-attorneydle", "Ace Attorneydle", "Identify Ace Attorney characters from attributes and debut information.", "Video Games", "https://aceattorneydle.fr/", FandomScoreKind.ATTEMPTS, false),
      game("silksongdle", "Silksongdle", "Guess Silksong characters, tools, dialogue, and locations.", "Video Games", "https://silksongdle.com/", FandomScoreKind.ATTEMPTS, true),
      game("narutodle", "Narutodle", "Guess Naruto characters from attributes, quotes, and clues.", "Anime and Manga", "https://www.narutodle.com/", FandomScoreKind.ATTEMPTS, true),
      game("onepiecedle", "Onepiecedle", "Guess One Piece characters from properties, Devil Fruits, posters, and laughs.", "Anime and Manga", "https://onepiecedle.net/", FandomScoreKind.ATTEMPTS, true),
      game("grandlinedle", "Grandlinedle", "Identify One Piece characters, locations, quotes, and crews.", "Anime and Manga", "https://www.grandlinedle.com/classic", FandomScoreKind.ATTEMPTS, true),
      game("animedle", "Animedle", "Guess anime series or characters from properties, emoji, openings, and images.", "Anime and Manga", "https://www.animedle.lat/", FandomScoreKind.ATTEMPTS, true),
      game("daily-animedle", "Daily Animedle", "Guess a mystery anime character using attribute clues.", "Anime and Manga", "https://dailyanimedle.com/", FandomScoreKind.ATTEMPTS, false),
      game("guess-the-game", "GuessThe.Game", "Identify a video game from progressively revealed screenshots.", "Gaming and Entertainment", "https://guessthe.game/", FandomScoreKind.ATTEMPTS, false),
      game("gamedle", "Gamedle", "Guess video games from covers, artwork, characters, and metadata.", "Gaming and Entertainment", "https://gamedle.wtf/", FandomScoreKind.ATTEMPTS, true),
      game("steamdle", "Steamdle", "Identify video games from progressively revealed Steam reviews.", "Gaming and Entertainment", "https://steamdle.com/", FandomScoreKind.ATTEMPTS, false),
      game("retro-game-trivia", "Retro Game Trivia", "Identify retro games from letters, clues, screenshots, and box art.", "Gaming and Entertainment", "https://retrotrivia.games/", FandomScoreKind.ATTEMPTS, true),
      game("isaaconnect", "Isaaconnect", "Group Binding of Isaac items into related sets.", "Gaming and Entertainment", "https://isaaconnect.com/", FandomScoreKind.CONNECTION_MISTAKES, false),
      game("actorle", "Actorle", "Guess actors using filmography clues.", "Gaming and Entertainment", "https://actorle.com/", FandomScoreKind.ATTEMPTS, false));

  private static final Map<String, FandomGameDefinition> BY_SLUG = ALL.stream()
      .collect(java.util.stream.Collectors.toUnmodifiableMap(FandomGameDefinition::slug, value -> value));

  static Optional<FandomGameDefinition> find(String slug) { return Optional.ofNullable(BY_SLUG.get(slug)); }
  static List<String> slugs() { return ALL.stream().map(FandomGameDefinition::slug).toList(); }

  private static FandomGameDefinition game(String slug, String name, String description, String category,
                                            String sourceUrl, FandomScoreKind kind, boolean modes) {
    return new FandomGameDefinition(slug, name, description, category, sourceUrl, kind, modes);
  }
}

@Component
class FandomGameAdapterProvider {
  private final List<GameAdapter> adapters = FandomGames.ALL.stream().map(FandomGameAdapter::new)
      .map(GameAdapter.class::cast).toList();
  List<GameAdapter> adapters() { return adapters; }
}

final class FandomGameAdapter implements GameAdapter {
  private final FandomGameDefinition definition;
  FandomGameAdapter(FandomGameDefinition definition) { this.definition = definition; }

  @Override public String slug() { return definition.slug(); }
  @Override public String scoreDirection() {
    return definition.scoreKind() == FandomScoreKind.GRID_RARITY ? "HIGHER_IS_BETTER" : "LOWER_IS_BETTER";
  }
  @Override public ScoreFormatView scoreFormat() {
    return switch (definition.scoreKind()) {
      case ATTEMPTS -> new ScoreFormatView("", " guesses", 0, 0, "guesses");
      case CONNECTION_MISTAKES -> new ScoreFormatView("", " mistakes", 0, 0, "mistakes");
      case GRID_RARITY -> new ScoreFormatView("", "/9", 0, 0, "completed-cells");
    };
  }
  @Override public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("", "Puzzle date", switch (definition.scoreKind()) {
      case ATTEMPTS -> "Guesses or clues";
      case CONNECTION_MISTAKES -> "Mistakes";
      case GRID_RARITY -> "Correct cells";
    }, BigDecimal.ZERO, null, BigDecimal.ONE, Set.of());
  }
  @Override public Set<String> supportedFormats() { return Set.of(); }
  @Override public String parserStatus() { return "MANUAL_ONLY"; }
  @Override public ParsedSubmission parse(String rawText) {
    throw new ApiException(422, "UNSUPPORTED_SHARE_FORMAT",
        definition.name() + " clipboard results are not verified yet. Enter this score manually on the website.");
  }
  @Override public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String rawText) { return parse(rawText); }
}
