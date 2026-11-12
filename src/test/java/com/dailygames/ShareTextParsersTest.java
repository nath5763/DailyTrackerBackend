package com.dailygames;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShareTextParsersTest {
  // Publicly shared result examples collected November 4, 2026; source links are in catalog_audit.md.
  private static final String WORDLE = "Wordle 1,961 4/6*\n\n⬜🟨⬜🟨🟨\n🟨⬜⬜🟩🟩\n⬜⬜🟩🟩🟩\n🟩🟩🟩🟩🟩";
  private static final String PINPOINT = "Pinpoint #747 | 1 guess with no mistakes\n1️⃣ | 100% match 📌\nlnkd.in/pinpoint.";
  private static final String QUEENS = "Queens #918 | 3:11 with no hints\nFirst 👑s: 🟦 🟪 🟧\nlnkd.in/queens.";
  private static final String CROSSCLIMB = "Crossclimb #918 | 1:38 with no hints\nFill order: 1️⃣ 2️⃣ 3️⃣ 5️⃣ 4️⃣ ⬇️ ⬆️ 🪜\nlnkd.in/crossclimb.";
  private static final String TANGO = "Tango #758 | 1:00 with no mistakes & no hints\nFirst 5 placements: 🟨🟨🟨🟨🟨🟨 🟨🟨🟨🟨🟨🟨 2️⃣🟨1️⃣🟨🟨🟨 3️⃣🟨🟨🟨🟨🟨 4️⃣🟨🟨🟨🟨🟨 5️⃣🟨🟨🟨🟨🟨\nlnkd.in/tango.";
  private static final String ZIP = "Zip #373 | 0:08 and flawless 🏁 With no backtracks 🟢\nlnkd.in/zip.";
  @Test void parsesCurrentSuccessFixtures() {
    List<GameAdapter> adapters = List.of(new WordleParser(), new PinpointParser(), new QueensParser(),
        new CrossclimbParser(), new TangoParser(), new ZipParser());
    List<String> shares = List.of(WORDLE, PINPOINT, QUEENS, CROSSCLIMB, TANGO, ZIP);
    List<Integer> scores = List.of(4, 1, 191, 98, 60, 8);
    for (int i = 0; i < adapters.size(); i++) {
      ParsedSubmission parsed = adapters.get(i).parse(shares.get(i));
      assertEquals(BigDecimal.valueOf(scores.get(i)), parsed.primaryScore(), adapters.get(i).slug());
      assertEquals("WIN", parsed.metrics().get("completionStatus"), adapters.get(i).slug());
      assertNotNull(parsed.playedOn());
      assertEquals(1, parsed.parserVersion());
      assertEquals(parsed, adapters.get(i).validateConfirmed(new ApiModels.ConfirmedSubmission(parsed.puzzleKey(),
          parsed.puzzleNumber(), parsed.playedOn(), parsed.primaryScore(), parsed.metrics(), parsed.parserVersion()), shares.get(i)));
    }
    assertEquals(LocalDate.of(2026, 11, 4), new QueensParser().parse(QUEENS).playedOn());
    WordleParser wordle = new WordleParser();
    ParsedSubmission parsedWordle = wordle.parse(WORDLE);
    assertEquals("4/6*", parsedWordle.metrics().get("displayScore"));
    assertEquals(List.of(new ApiModels.ResultDetailView("G1", "⬜🟨⬜🟨🟨"),
        new ApiModels.ResultDetailView("G2", "🟨⬜⬜🟩🟩"),
        new ApiModels.ResultDetailView("G3", "⬜⬜🟩🟩🟩"),
        new ApiModels.ResultDetailView("G4", "🟩🟩🟩🟩🟩")), wordle.resultDetails(parsedWordle.metrics()));
    ParsedSubmission pinpoint = new PinpointParser().parse(PINPOINT);
    assertEquals(List.of(new ApiModels.ResultDetailView("G1", "100%")),
        new PinpointParser().resultDetails(pinpoint.metrics()));
    ParsedSubmission queens = new QueensParser().parse(QUEENS);
    assertEquals(List.of(new ApiModels.ResultDetailView("C1", "🟦"),
        new ApiModels.ResultDetailView("C2", "🟪"), new ApiModels.ResultDetailView("C3", "🟧")),
        new QueensParser().resultDetails(queens.metrics()));
    ParsedSubmission crossclimb = new CrossclimbParser().parse(CROSSCLIMB);
    assertEquals(List.of(new ApiModels.ResultDetailView("Order", "1 → 2 → 3 → 5 → 4")),
        new CrossclimbParser().resultDetails(crossclimb.metrics()));
    ParsedSubmission tango = new TangoParser().parse(TANGO);
    assertEquals(List.of(new ApiModels.ResultDetailView("P1", "R3C3"),
        new ApiModels.ResultDetailView("P2", "R3C1"), new ApiModels.ResultDetailView("P3", "R4C1"),
        new ApiModels.ResultDetailView("P4", "R5C1"), new ApiModels.ResultDetailView("P5", "R6C1"),
        new ApiModels.ResultDetailView("Hints", "0"), new ApiModels.ResultDetailView("Mistakes", "0")),
        new TangoParser().resultDetails(tango.metrics()));
    ParsedSubmission zip = new ZipParser().parse(ZIP);
    assertEquals(List.of(new ApiModels.ResultDetailView("Backtracks", "0")),
        new ZipParser().resultDetails(zip.metrics()));
  }
  @Test void handlesLossAssistanceAndDeviceWhitespace() {
    String loss = "Wordle 1,962 X/6\n⬛⬛⬛⬛⬛\n⬛⬛⬛⬛⬛\n⬛⬛⬛⬛⬛\n⬛⬛⬛⬛⬛\n⬛⬛⬛⬛⬛\n⬛⬛⬛⬛⬛";
    assertEquals(BigDecimal.valueOf(7), new WordleParser().parse(loss).primaryScore());
    assertEquals("X/6", new WordleParser().parse(loss).metrics().get("displayScore"));
    assertEquals("LOSS", new WordleParser().parse(loss).metrics().get("completionStatus"));
    assertEquals(BigDecimal.valueOf(4), new WordleParser().parse(WORDLE + "\nhttps://www.nytimes.com/games/wordle/index.html").primaryScore());
    assertEquals(2, new QueensParser().parse(QUEENS.replace("no hints", "2 hints")).metrics().get("hints"));
    assertEquals("unknown", new QueensParser().parse(QUEENS).metrics().get("assistanceStatus"));
    assertEquals("unassisted", new QueensParser().parse(QUEENS.replace("no hints", "no mistakes & no hints")).metrics().get("assistanceStatus"));
    assertEquals(40, new ZipParser().parse("Zip #16 | 0:51 🏁 With 40 backtracks 🛑 lnkd.in/zip.").metrics().get("backtracks"));
    assertEquals(BigDecimal.valueOf(98), new CrossclimbParser().parse(CROSSCLIMB.replace("\n", " ")).primaryScore());
    assertEquals(BigDecimal.valueOf(98), new CrossclimbParser().parse(CROSSCLIMB.replace("lnkd.in/crossclimb", "https://www.linkedin.com/games/crossclimb/")).primaryScore());
  }
  @Test void acceptsTelegramVariationSelectorsInWordleGrid() {
    String telegram = ("Wordle 1,972 6/6\n\n"
        + "🟨⬛⬛⬛⬛\n"
        + "⬛⬛🟨🟨⬛\n"
        + "⬛🟩🟩⬛⬛\n"
        + "⬛🟩🟩🟨⬛\n"
        + "⬛🟨🟨⬛🟩\n"
        + "🟩🟩🟩🟩🟩").replace("⬛", "⬛\uFE0F");
    ParsedSubmission parsed = new WordleParser().parse(telegram);
    assertEquals(BigDecimal.valueOf(6), parsed.primaryScore());
    assertEquals("6/6", parsed.metrics().get("displayScore"));
    assertEquals("🟨⬛⬛⬛⬛", ((List<?>) parsed.metrics().get("rows")).get(0));
  }

  @Test void rejectsMalformedAndAmbiguousResults() {
    assertThrows(ApiException.class, () -> new WordleParser().parse("Wordle 1961 2/6\n⬛⬛⬛⬛⬛"));
    assertThrows(ApiException.class, () -> new WordleParser().parse(WORDLE.replace("4/6", "X/6")));
    assertThrows(ApiException.class, () -> new QueensParser().parse("Queens #918 | 3:11"));
    assertThrows(ApiException.class, () -> new PinpointParser().parse(PINPOINT.replace("100% match", "90% match")));
    assertThrows(ApiException.class, () -> new TangoParser().parse(TANGO.replace("1:00", "1:00 | 2:00")));
    assertFalse(new ZipParser().parse(ZIP.replace("With no backtracks", "")).metrics().containsKey("backtracks"));
  }
}
