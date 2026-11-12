package com.dailygames;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Set;
import static com.dailygames.ApiModels.*;

@Component
class LetterBagAdapter implements GameAdapter {
  public String slug() { return "letter-bag"; }
  public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " pts", 0, 0); }
  public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("", "Date", "Points", BigDecimal.ZERO, null, BigDecimal.ONE, Set.of());
  }
  public ParsedSubmission parse(String text) { throw new ApiException(422, "IN_APP_ONLY", "Play Letter Bag here to record a score."); }
  public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String text) { return parse(text); }
  public String scoreDirection() { return "HIGHER_IS_BETTER"; }
  public Set<String> supportedFormats() { return Set.of("in-app"); }
  public String parserStatus() { return "UNSUPPORTED"; }
}
