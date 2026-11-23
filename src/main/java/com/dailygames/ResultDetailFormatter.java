package com.dailygames;

import java.util.*;

import static com.dailygames.ApiModels.ResultDetailView;

final class ResultDetailFormatter {
  private static final Set<String> LINKEDIN_GAMES = Set.of("pinpoint", "queens", "crossclimb", "tango", "zip");

  private ResultDetailFormatter() {}

  static List<ResultDetailView> fromMetrics(String gameSlug, Map<String, Object> metrics) {
    if (metrics == null || metrics.isEmpty()) return List.of();
    return switch (gameSlug) {
      case "wordle", "worldle", "nerdle" -> indexed("G", values(metrics.get("rows")), ResultDetailFormatter::text);
      case "connections" -> indexed("R", values(metrics.get("rows")), ResultDetailFormatter::text);
      case "quordle" -> indexed("Q", values(metrics.get("guesses")), value -> value == null ? "X" : value + " guesses");
      case "timeguessr" -> indexed("R", values(metrics.get("roundScores")), value -> number(value) + " pts");
      case "farflung" -> indexed("P", values(metrics.get("rarities")), ResultDetailFormatter::text);
      case "perceptle" -> perceptle(metrics);
      case "gazillion" -> indexed("M", values(metrics.get("resultTiers")), ResultDetailFormatter::text);
      case "daily-set" -> dailySet(metrics);
      case "daily-orbs" -> dailyOrbs(metrics);
      case "minute-cryptic" -> minuteCryptic(metrics);
      case "contexto" -> combine(contexto(metrics), assistance(metrics));
      case "globle" -> single("Path", metrics.get("proximityGrid"));
      case "strands" -> single("Path", metrics.get("resultSequence"));
      case "travle" -> combine(single("Path", metrics.get("resultSequence")), assistance(metrics));
      case "redactle" -> redactle(metrics);
      case "semantle" -> semantle(metrics);
      case "pinpoint" -> combine(indexed("G", values(metrics.get("matchPercentages")), value -> number(value) + "%"),
          assistance(metrics));
      case "queens" -> combine(indexed("C", values(metrics.get("firstCrowns")), ResultDetailFormatter::text),
          assistance(metrics));
      case "crossclimb" -> combine(order(metrics.get("fillOrder")), assistance(metrics));
      case "tango" -> combine(indexed("P", values(metrics.get("placementCells")), ResultDetailFormatter::text),
          assistance(metrics));
      default -> LINKEDIN_GAMES.contains(gameSlug) ? assistance(metrics) : List.of();
    };
  }

  private static List<ResultDetailView> perceptle(Map<String, Object> metrics) {
    List<ResultDetailView> details = new ArrayList<>();
    add(details, "Count", metrics.get("countScore"), "");
    add(details, "Track", metrics.get("trackScore"), "");
    add(details, "Angle", metrics.get("angleScore"), "");
    add(details, "Tempo", metrics.get("tempoScore"), "");
    add(details, "Split", metrics.get("splitScore"), "");
    return List.copyOf(details);
  }

  private static List<ResultDetailView> dailySet(Map<String, Object> metrics) {
    List<ResultDetailView> details = new ArrayList<>();
    String mode = text(metrics.get("gameMode"));
    if (!mode.isEmpty()) details.add(new ResultDetailView("Mode", mode));
    details.addAll(indexed("Set ", values(metrics.get("setTimesSeconds")), value -> number(value) + "s"));
    return List.copyOf(details);
  }

  private static List<ResultDetailView> dailyOrbs(Map<String, Object> metrics) {
    List<ResultDetailView> details = new ArrayList<>();
    for (String color : List.of("orange", "green", "blue", "purple")) {
      Object orbs = metrics.get(color + "Orbs");
      if (orbs == null) continue;
      String value = number(orbs) + " orbs";
      if (Boolean.TRUE.equals(metrics.get("heartsDisclosed"))) {
        Object lost = metrics.get(color + "HeartsLost");
        int count = lost instanceof Number number ? number.intValue() : 0;
        value += " · " + count + (count == 1 ? " heart lost" : " hearts lost");
      }
      details.add(new ResultDetailView(capitalize(color), value));
    }
    return List.copyOf(details);
  }

  private static List<ResultDetailView> minuteCryptic(Map<String, Object> metrics) {
    List<ResultDetailView> details = new ArrayList<>();
    String clue = text(metrics.get("clue"));
    if (!clue.isEmpty()) details.add(new ResultDetailView("Clue", clue));
    String symbols = text(metrics.get("symbolRow"));
    if (!symbols.isEmpty()) details.add(new ResultDetailView("Symbols", symbols));
    Object delta = metrics.get("communityParDelta");
    if (delta instanceof Number number) {
      int value = number.intValue();
      details.add(new ResultDetailView("Community par", value < 0 ? -value + " under" : value > 0 ? value + " over" : "matched"));
    }
    add(details, "Solvers", metrics.get("solverCount"), "");
    Object seconds = metrics.get("completionTimeSeconds");
    if (seconds instanceof Number number) details.add(new ResultDetailView("Time", duration(number.intValue())));
    return List.copyOf(details);
  }

  private static String duration(int seconds) {
    if (seconds >= 3600) return String.format(Locale.ROOT, "%dh %dm %ds", seconds / 3600, seconds / 60 % 60, seconds % 60);
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
  }

  private static String capitalize(String value) {
    return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
  }

  private static List<ResultDetailView> contexto(Map<String, Object> metrics) {
    List<ResultDetailView> details = new ArrayList<>();
    add(details, "Green", metrics.get("greenGuesses"), " guesses");
    add(details, "Yellow", metrics.get("yellowGuesses"), " guesses");
    add(details, "Red", metrics.get("redGuesses"), " guesses");
    return List.copyOf(details);
  }

  private static List<ResultDetailView> redactle(Map<String, Object> metrics) {
    List<ResultDetailView> details = new ArrayList<>();
    Object seconds = metrics.get("completionTimeSeconds");
    if (seconds instanceof Number value) {
      int total = value.intValue();
      details.add(new ResultDetailView("Time", String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)));
    }
    addAssistance(details, metrics);
    return List.copyOf(details);
  }

  private static List<ResultDetailView> semantle(Map<String, Object> metrics) {
    List<ResultDetailView> details = new ArrayList<>();
    add(details, "Best guess", metrics.get("bestPriorGuess"), "");
    Object closest = metrics.get("closestPriorRank");
    if (closest != null) details.add(new ResultDetailView("Closest", number(closest) + "/1000"));
    addAssistance(details, metrics);
    return List.copyOf(details);
  }

  private static List<ResultDetailView> assistance(Map<String, Object> metrics) {
    List<ResultDetailView> details = new ArrayList<>();
    addAssistance(details, metrics);
    add(details, "Starter", positive(metrics.get("beginnerAssists")), "");
    add(details, "Backtracks", metrics.get("backtracks"), "");
    return List.copyOf(details);
  }

  private static List<ResultDetailView> order(Object rawValues) {
    List<?> values = values(rawValues);
    if (values.isEmpty()) return List.of();
    return List.of(new ResultDetailView("Order", values.stream().map(ResultDetailFormatter::number)
        .collect(java.util.stream.Collectors.joining(" → "))));
  }

  private static List<ResultDetailView> combine(List<ResultDetailView> first, List<ResultDetailView> second) {
    if (first.isEmpty()) return second;
    if (second.isEmpty()) return first;
    List<ResultDetailView> combined = new ArrayList<>(first);
    combined.addAll(second);
    return List.copyOf(combined);
  }

  private static void addAssistance(List<ResultDetailView> details, Map<String, Object> metrics) {
    boolean known = !"unknown".equals(metrics.get("assistanceStatus"));
    Object hints = metrics.get("hints");
    if (known || positive(hints) != null) add(details, "Hints", hints, "");
    Object mistakes = metrics.get("mistakes");
    if (known || positive(mistakes) != null) add(details, "Mistakes", mistakes, "");
  }

  private static List<ResultDetailView> single(String label, Object value) {
    String text = text(value);
    return text.isEmpty() ? List.of() : List.of(new ResultDetailView(label, text));
  }

  private static List<ResultDetailView> indexed(String prefix, List<?> values,
                                                 java.util.function.Function<Object, String> display) {
    List<ResultDetailView> details = new ArrayList<>();
    for (int index = 0; index < values.size() && index < 7; index++) {
      String value = display.apply(values.get(index));
      if (!value.isEmpty()) details.add(new ResultDetailView(prefix + (index + 1), value));
    }
    return List.copyOf(details);
  }

  private static List<?> values(Object value) {
    return value instanceof List<?> list ? list : value instanceof Collection<?> collection
        ? List.copyOf(collection) : List.of();
  }

  private static void add(List<ResultDetailView> details, String label, Object value, String suffix) {
    if (value != null) details.add(new ResultDetailView(label, number(value) + suffix));
  }

  private static Object positive(Object value) {
    return value instanceof Number number && number.longValue() > 0 ? value : null;
  }

  private static String number(Object value) {
    if (value instanceof Number number) return number.toString();
    return text(value);
  }

  private static String text(Object value) { return value == null ? "" : value.toString(); }
}
