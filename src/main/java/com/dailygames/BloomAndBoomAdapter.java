package com.dailygames;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Set;
import static com.dailygames.ApiModels.*;

@Component
class BloomAndBoomAdapter implements GameAdapter {
  public String slug() { return "bloom-and-boom"; }
  public ScoreFormatView scoreFormat() { return new ScoreFormatView("", "", 0, 2, "duration"); }
  public SubmissionConfigView submissionConfig() {
    return new SubmissionConfigView("", "Date", "Time", BigDecimal.ZERO, null, new BigDecimal("0.01"), Set.of());
  }
  public ParsedSubmission parse(String text) { throw new ApiException(422, "IN_APP_ONLY", "Play Bloom & Boom here to record a time."); }
  public ParsedSubmission validateConfirmed(ConfirmedSubmission confirmed, String text) { return parse(text); }
  public Set<String> supportedFormats() { return Set.of("in-app"); }
  public String parserStatus() { return "UNSUPPORTED"; }
}
