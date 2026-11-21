package com.dailygames;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
class LetterBagService {
  record Tile(int id, String letter) {}
  record Word(String word, int points) {}
  record Round(LocalDate date, List<Tile> rack, List<Tile> bag, List<Word> words, int score,
               Long startedAt, Long endedAt) {}
  record View(LocalDate date, String gameNumber, List<Tile> rack, List<Tile> preview, int bagRemaining,
              List<Word> words, int score, Long startedAt, Long endedAt) {}

  static final String VOWEL_POOL = "AAAAAAAAEEEEEEEEEEEIIIIIIOOOOOOOUUU";
  static final String CONSONANT_POOL = "BCDFGHJKLMNNPQRRRRSSSSTTTTTTVWYZ";
  static final List<Integer> RACK_VOWEL_TARGETS = List.of(5, 6, 6, 7);
  private static final UUID GAME_ID = UUID.fromString("b7dbe59a-17c4-4f08-9d2c-756702291ece");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> WORDS = loadWords();
  private final JdbcTemplate db;

  LetterBagService(JdbcTemplate db) { this.db = db; }

  private static Set<String> loadWords() {
    try (var stream = LetterBagService.class.getResourceAsStream("/letter-bag-words.txt")) {
      if (stream == null) throw new IllegalStateException("Letter Bag dictionary missing");
      return Set.copyOf(Arrays.asList(new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim().split("\\R")));
    } catch (Exception error) { throw new IllegalStateException("Cannot load Letter Bag dictionary", error); }
  }

  private static class Randomizer {
    int state;
    Randomizer(String seed) {
      int hash = (int) 2166136261L;
      for (int i = 0; i < seed.length(); i++) hash = (hash ^ seed.charAt(i)) * 16777619;
      state = hash == 0 ? 1 : hash;
    }
    double next() {
      state += 0x6d2b79f5;
      int t = state;
      t = (t ^ (t >>> 15)) * (t | 1);
      t ^= t + (t ^ (t >>> 7)) * (t | 61);
      return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
    }
  }

  static List<Tile> puzzle(LocalDate date) {
    var rng = new Randomizer(date.toString());
    int rackVowels = RACK_VOWEL_TARGETS.get((int) (rng.next() * RACK_VOWEL_TARGETS.size()));
    int bagVowels = 12 - rackVowels;
    var rack = drawHalf(rng, rackVowels);
    var bag = drawHalf(rng, bagVowels);
    var letters = new ArrayList<String>(30);
    letters.addAll(rack);
    letters.addAll(bag);
    var result = new ArrayList<Tile>();
    for (int i = 0; i < 30; i++) result.add(new Tile(i, letters.get(i)));
    return List.copyOf(result);
  }

  private static List<String> drawHalf(Randomizer rng, int vowelCount) {
    var letters = new ArrayList<String>(15);
    for (int i = 0; i < vowelCount; i++) letters.add(draw(rng, VOWEL_POOL));
    for (int i = vowelCount; i < 15; i++) letters.add(draw(rng, CONSONANT_POOL));
    for (int i = letters.size() - 1; i > 0; i--) Collections.swap(letters, i, (int) (rng.next() * (i + 1)));
    return letters;
  }

  private static String draw(Randomizer rng, String pool) {
    return String.valueOf(pool.charAt((int) (rng.next() * pool.length())));
  }

  private static Round fresh(LocalDate date, Long startedAt) {
    var tiles = puzzle(date);
    return new Round(date, tiles.subList(0, 15), tiles.subList(15, 30), List.of(), 0, startedAt, null);
  }
  private static long now() { return System.currentTimeMillis(); }
  private static LocalDate today() { return LocalDate.now(ZoneOffset.UTC); }
  private static Round finish(Round round, long now) {
    return round.endedAt() != null ? round : new Round(round.date(), round.rack(), round.bag(), round.words(),
        round.score(), round.startedAt(), now);
  }
  private static View view(Round round) {
    boolean hidden = round.startedAt() == null;
    var rack = hidden ? round.rack().stream().map(t -> new Tile(t.id(), "?")).toList() : round.rack();
    var preview = round.bag().stream().limit(5).map(t -> hidden ? new Tile(t.id(), "?") : t).toList();
    return new View(round.date(), round.date().toString().replace("-", ""), rack, preview, round.bag().size(),
        round.words(), round.score(), round.startedAt(), round.endedAt());
  }
  private static String json(Round round) { return JSON.writeValueAsString(round); }
  private Round load(UUID user, LocalDate date, boolean lock) {
    var rows = db.queryForList("select state::text from letter_bag_attempts where user_id = ? and puzzle_date = ?" +
        (lock ? " for update" : ""), String.class, user, date);
    return rows.isEmpty() ? null : JSON.readValue(rows.getFirst(), Round.class);
  }
  private void save(UUID user, Round round) {
    db.update("update letter_bag_attempts set state = ?::jsonb, ended_at = ? where user_id = ? and puzzle_date = ?",
        json(round), round.endedAt() == null ? null : Timestamp.from(Instant.ofEpochMilli(round.endedAt())), user, round.date());
  }
  private void record(UUID user, Round round) {
    if (round.endedAt() == null) return;
    db.update("""
        insert into submissions (id, user_id, game_id, puzzle_key, played_on, primary_score, metrics,
          raw_text, parser_version, submission_method, created_at)
        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 1, 'IN_APP', now())
        on conflict (user_id, game_id, puzzle_key) do nothing
        """, UUID.randomUUID(), user, GAME_ID, round.date().toString(), round.date(),
        BigDecimal.valueOf(round.score()), JSON.writeValueAsString(Map.of("wordCount", round.words().size(),
            "longestWord", round.words().stream().mapToInt(w -> w.word().length()).max().orElse(0))),
        "Letter Bag in-app result " + round.date());
  }
  @Transactional
  View state(UUID user) {
    LocalDate date = today();
    Round round = load(user, date, true);
    return view(round == null ? fresh(date, null) : round);
  }
  @Transactional
  Round replay(UUID user) {
    LocalDate date = today();
    Round scored = load(user, date, true);
    if (scored == null) throw new ApiException(409, "ROUND_NOT_FINISHED", "Finish today's scored round before replaying.");
    if (scored.endedAt() == null)
      throw new ApiException(409, "ROUND_NOT_FINISHED", "Finish today's scored round before replaying.");
    return fresh(date, null);
  }

  @Transactional
  View start(UUID user) {
    long now = now(); LocalDate date = today();
    Round initial = fresh(date, now);
    db.update("insert into letter_bag_attempts (user_id, puzzle_date, started_at, state) values (?, ?, ?, ?::jsonb) on conflict do nothing",
        user, date, Timestamp.from(Instant.ofEpochMilli(now)), json(initial));
    return view(load(user, date, true));
  }
  @Transactional
  View word(UUID user, List<Integer> ids) {
    long now = now(); LocalDate date = today(); Round round = load(user, date, true);
    if (round == null) throw new ApiException(400, "NOT_STARTED", "Start the round first.");
    if (round.endedAt() != null) throw new ApiException(400, "ROUND_ENDED", "Round has ended.");
    if (ids == null || ids.size() < 3 || new HashSet<>(ids).size() != ids.size())
      throw new ApiException(400, "INVALID_TILES", "Select at least three different tiles.");
    var chosen = new ArrayList<Tile>();
    for (Integer id : ids) {
      var tile = round.rack().stream().filter(t -> Objects.equals(t.id(), id)).findFirst().orElse(null);
      if (tile == null) throw new ApiException(400, "INVALID_TILES", "Those tiles are no longer in the rack.");
      chosen.add(tile);
    }
    String word = String.join("", chosen.stream().map(Tile::letter).toList()).toLowerCase(Locale.ROOT);
    if (round.words().stream().anyMatch(w -> w.word().equals(word))) throw new ApiException(400, "REPEATED_WORD", "Already played.");
    if (!WORDS.contains(word)) throw new ApiException(400, "INVALID_WORD", "Not in the word list.");
    int points = LetterBagScoring.score(word).total();
    var rack = new ArrayList<>(round.rack().stream().filter(t -> !ids.contains(t.id())).toList());
    int drawn = Math.min(ids.size(), round.bag().size()); rack.addAll(round.bag().subList(0, drawn));
    rack.sort(Comparator.comparingInt(Tile::id));
    var words = new ArrayList<>(round.words()); words.add(new Word(word, points));
    round = new Round(date, List.copyOf(rack), List.copyOf(round.bag().subList(drawn, round.bag().size())),
        List.copyOf(words), round.score() + points, round.startedAt(), rack.size() < 3 ? now : null);
    save(user, round); record(user, round); return view(round);
  }
  @Transactional
  View finish(UUID user) {
    long now = now(); LocalDate date = today(); Round round = load(user, date, true);
    if (round == null) throw new ApiException(400, "NOT_STARTED", "Start the round first.");
    round = finish(round, now); save(user, round); record(user, round); return view(round);
  }
}
