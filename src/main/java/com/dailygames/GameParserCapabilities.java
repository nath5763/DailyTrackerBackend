package com.dailygames;

import java.util.List;

/**
 * The exact requested share-parser scope. An entry remains unsupported until a real,
 * attributable fixture and its native scoring semantics are covered by a parser test.
 */
final class GameParserCapabilities {
  private GameParserCapabilities() {}

  private static final List<String> CORE_GAME_IDS = List.of(
      "strands", "spelling-bee", "nyt-mini-crossword", "quordle", "octordle", "dordle", "waffle",
      "squaredle", "weaver", "semantle", "contexto", "redactle", "letter-boxed",
      "guardian-quick-crossword", "nyt-sudoku", "nyt-tiles", "nerdle", "mathler", "kenken", "kakuro",
      "nonograms-picross", "worldle", "globle", "geoguessr-daily-challenge", "travle", "flagle",
      "statele", "timeguessr", "farflung", "perceptle", "foximax", "murdle", "framed", "moviedle", "costcodle", "heardle-decades",
      "bandle", "immaculate-grid", "weddle", "poeltl");

  static final List<String> REQUESTED_GAME_IDS;
  static {
    java.util.ArrayList<String> games = new java.util.ArrayList<>(CORE_GAME_IDS);
    games.addAll(FandomGames.slugs());
    LoldleParser.MODES.forEach(mode -> games.add("loldle-" + mode));
    REQUESTED_GAME_IDS = List.copyOf(games);
  }
}
