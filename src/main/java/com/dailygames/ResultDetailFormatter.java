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
