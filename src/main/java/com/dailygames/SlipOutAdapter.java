package com.dailygames;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Set;
import static com.dailygames.ApiModels.*;

@Component
class SlipOutAdapter implements GameAdapter {
  public String slug() { return "slipout"; }
  public ScoreFormatView scoreFormat() { return new ScoreFormatView("", " moves", 0, 0); }
  public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("", "Date", "Moves", BigDecimal.ONE, null, BigDecimal.ONE, Set.of());
  }
  public ParsedSubmission parse(String text) { throw new ApiException(422, "IN_APP_ONLY", "Play SlipOut here to record a score."); }
  public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String text) { return parse(text); }
  public Set<String> supportedFormats() { return Set.of("in-app"); }
  public String parserStatus() { return "UNSUPPORTED"; }
}
