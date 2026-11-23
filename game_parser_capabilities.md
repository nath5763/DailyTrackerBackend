# Share-result parser capabilities

`VERIFIED` means a real copied result with an identifiable daily puzzle and native performance is covered by a parser test. `PARTIAL` means only documented variants are accepted. `UNSUPPORTED` means no confirmed fixture has been established in this repository; it does not mean the publisher lacks a share feature. Unsupported games remain catalog links and cannot accept submissions.

## Integration points

- `GameAdapterRegistry` owns strict detection, parsing, confirmation validation, ranking-metric access, supported formats, and the 88-game capability list. It adds standard result metadata without replacing native metrics.
- `DailyGamesService` always uses the authenticated account and selected leaderboard/game. The share text cannot supply either identity.
- The existing `submissions` row remains the audit record: canonical puzzle key, native primary score, JSON metrics, original raw text, and parser version. The existing unique player/game/puzzle constraint is retained; an exact retry returns the original submission ID.
- Leaderboards ask the selected game adapter for its configured native metrics and use explicit competition ties. Completion-first is the default; PokeDoku's documented metrics-first exception compares completion only after uniqueness and correct cells. No cross-game conversion formula is introduced.
- `GET /api/v1/catalog/games/parser-capabilities` reports all 88 requested game IDs. Unsupported entries expose no accepted format.
- `GET /api/v1/catalog/games/score-strategies` reports the primary/secondary metrics, directions, compatibility fields, completion-only support, and completion policy for every strategy contract. Strategies default to `COMPLETION_FIRST`; PokeDoku explicitly uses `METRICS_FIRST`.
- The frontend's immediate browser preview mirrors the backend contracts; saving still requires exact backend parsing and confirmed-preview validation.

There are currently **24 PARTIAL** integrations backed by real fixtures, **25 UNSUPPORTED** core entries awaiting genuine copied results, and **39 MANUAL_ONLY** fandom games. None is labeled VERIFIED because the accepted variants are intentionally narrower than every format the publishers may emit. Manual-only games are active leaderboard games but are excluded from Discord `/daily` until a strict clipboard parser is backed by real fixtures. See `fandom_game_capabilities.md` for their metrics and evidence requirements.

For every unsupported parser, implementation requires the unedited clipboard text for at least a win and loss/incomplete result, the game URL, and any mode/difficulty variants. Timed games additionally need a share that actually contains the elapsed time; percentage games need both earned and maximum values; puzzle-number-only games need a publisher-verifiable date mapping if they are to appear in date-range leaderboards. Screenshots without clipboard text are insufficient for a strict text parser.

| Game | Status | Confirmed example and source | Extracted fields | Ranking metric | Unsupported variants | Tests |
|---|---|---|---|---|---|---|
| Strands | PARTIAL | [Numbered emoji result, including hint variant](https://www.reddit.com/r/NYTStrands/comments/1xl2jj5/monday_nov_2_2026_strands_daily_thread/) | Puzzle, derived date, theme, hints, solve order | Hints, lower; completion required | Non-English headings and incomplete games | `NytWordGameParsersTest` |
| Spelling Bee | UNSUPPORTED | — | — | — | All | — |
| NYT Mini Crossword | PARTIAL | [Explicitly dated timed solve](https://www.pi.fyi/u/donnadean) | Date, elapsed seconds, win | Seconds, lower | Revealed/assisted solves are not identified by the share | `NytWordGameParsersTest` |
| Quordle | PARTIAL | User-provided Daily #1746 loss; [documented Daily and Sequence clipboard formats](https://www.wordlebot.gg/docs); [daily-number/date corroboration](https://www.techradar.com/computing/websites-apps/quordle-today-answers-clues-4-november-2026) | Puzzle, verified derived date, mode, four nullable board guesses, solved/failed counts, penalized total | Failed boards ascending, then penalized total ascending; modes separated | Extreme (no verified date mapping), Chill, non-Merriam-Webster legacy formats, ambiguous score layouts | `QuordleParserTest`, `ScoreQuantizerRegistryTest` |
| Octordle | UNSUPPORTED | — | — | — | All | — |
| Dordle | UNSUPPORTED | — | — | — | All | — |
| Waffle | UNSUPPORTED | — | — | — | All | — |
| Squaredle | UNSUPPORTED | — | — | — | All | — |
| Weaver | UNSUPPORTED | — | — | — | All | — |
| Semantle | PARTIAL | [Numbered result with guesses and hints](https://www.reddit.com/r/Semantlegameplayers/comments/1xlkxag/daily_semantle_1738/) | Puzzle, derived date, guesses, hints, closest prior rank | Guesses, lower; unassisted ahead | Loss shares | `SemanticWordParsersTest` |
| Contexto | PARTIAL | [Explicitly dated result](https://www.reddit.com/r/contexto/comments/1xg63rm/my_first_time_playing_contexto_and_this_is_the/) | Date, guesses, color buckets | Guesses, lower | Number-only shares without a verified date mapping | `SemanticWordParsersTest` |
| Redactle | PARTIAL | [Numbered rich and basic results](https://www.reddit.com/r/Redactle/comments/1xomtfq/redactle_1674_discussion_thread/) | Puzzle, derived date, guesses, hints, time | Guesses, lower; unassisted ahead | Failed/incomplete puzzles | `SemanticWordParsersTest` |
| Letter Boxed | UNSUPPORTED | — | — | — | All | — |
| The Guardian Quick Crossword | UNSUPPORTED | — | — | — | All | — |
| NYT Sudoku | UNSUPPORTED | — | — | — | All | — |
| NYT Tiles | UNSUPPORTED | — | — | — | All | — |
| Nerdle | PARTIAL | [Classic numbered 8-cell result and dated anchor](https://www.reddit.com/r/wordgames/comments/1uk3bp0/nerdle_review/) | Puzzle, derived date, attempts, win/loss, rows | Attempts, lower; wins ahead | Mini, instant, speed, and other modes | `NerdleParserTest` |
| Mathler | UNSUPPORTED | — | — | — | All | — |
| KenKen | UNSUPPORTED | — | — | — | All | — |
| Kakuro | UNSUPPORTED | — | — | — | All | — |
| Nonograms / Picross | UNSUPPORTED | — | — | — | All | — |
| Worldle | PARTIAL | [Dated share with grid and bonus symbols](https://www.reddit.com/r/Worldle/comments/1iacl1a/) | Puzzle number, explicit date, attempts, win, grid | Attempts, lower; loss after wins | Undated shares, bonus scores | `WorldleParserTest` |
| Globle | PARTIAL | [Dated share with guess heatmap](https://www.reddit.com/r/Reformed/comments/1vu7uc9/free_for_all_friday_post_on_any_topic_in_this/) | Explicit date, guesses, win, proximity grid | Guesses, lower | Localized dates and shares without `#globle` | `GlobleParserTest` |
| GeoGuessr Daily Challenge | UNSUPPORTED | — | — | — | All | — |
| Travle | PARTIAL | [Daily and Weekly Challenge shares; official scoring rules](https://travle.earth/extra_info) | Mode, puzzle, derived date, extra guesses, hints, result path | Extra guesses ascending, then hints ascending; modes separated | Practice mode, localized shares, and incomplete games | `TravleParserTest`, `ScoreQuantizerRegistryTest` |
| Flagle | UNSUPPORTED | — | — | — | All | — |
| Statele | UNSUPPORTED | — | — | — | All | — |
| TimeGuessr | PARTIAL | [Detailed and emoji-grid daily shares](https://www.reddit.com/r/timeguessr/comments/1msu06e); [official final-score page](https://timeguessr.com/finalscoredaily) | Puzzle, derived date, native total, optional five round scores | Native points descending | Image-only app shares and non-daily modes | `TimeGuessrParserTest`, `ScoreQuantizerRegistryTest` |
| Farflung | PARTIAL | User-provided Daily #50 result and official result link | Puzzle, derived date, five rarity labels, total distance | Distance descending | Non-daily, malformed, and nonofficial result links | `FarflungParserTest`, `ScoreQuantizerRegistryTest` |
| Perceptle | PARTIAL | User-provided Daily #129 result; [official scoring and grade rules](https://perceptle.io/scoring/) | Puzzle, derived date, five event scores, rounded overall score, grade | Overall score descending | Practice, Unlimited, Friday-marker, and nonstandard shares | `PerceptleParserTest`, `ScoreQuantizerRegistryTest` |
| The Daily Set | PARTIAL | Official Regular and Diablo clipboard formats with authoritative dated result URLs | Date, mode, elapsed seconds, optional six-set split times | Seconds ascending within mode | Shares without the official URL and malformed split summaries | `DailySetParserTest`, `ScoreQuantizerRegistryTest` |
| Daily Orbs | PARTIAL | Official dated clipboard format, with heart-inclusive and orbs-only narratives | Date, earned orbs by color, disclosed heart losses, event sequence | Earned orbs descending | Loss/incomplete shares and narratives without the sixth-orb trophy | `DailyOrbsParserTest`, `ScoreQuantizerRegistryTest` |
| Minute Cryptic | PARTIAL | Current community-par share and legacy under/even/over-par format | Date, clue, hints, symbol row, community-par delta, optional time and solver count | Hints ascending | Undated, unofficial-link, and nonstandard symbol formats | `MinuteCrypticParserTest`, `ScoreQuantizerRegistryTest` |
| FoxiMax | PARTIAL | User-provided Daily #1549 result; [official rules](https://foximax.com/about.html) | Puzzle, derived date, words, letters, result rows | Words ascending; letters are display-only | Game-over results, FoxiMax 6/7, training, and nonstandard color schemes | `FoxiMaxParserTest` |
| Murdle | PARTIAL | [Dated timed solve](https://rx-sns.jp/bsky/did%3Aplc%3Aoqiphqj7uiyb5nqeotsjvtsl) | Explicit date, win, elapsed seconds | Seconds, lower | Losses and results without a keycap time | `MurdleParserTest` |
| Framed | UNSUPPORTED | — | — | — | All | — |
| Moviedle | UNSUPPORTED | — | — | — | All | — |
| Costcodle | UNSUPPORTED | — | — | — | All | — |
| Heardle Decades | UNSUPPORTED | — | — | — | All | — |
| Bandle | UNSUPPORTED | — | — | — | All | — |
| Immaculate Grid | UNSUPPORTED | — | — | — | All | — |
| Weddle | UNSUPPORTED | — | — | — | All | — |
| Poeltl | UNSUPPORTED | — | — | — | All | — |

Connections was added before this 38-game request. Its [real shared result](https://www.reddit.com/r/NYTConnections/comments/1xkzbnv/monday_november_2_2026/) is covered by `ConnectionsParserTest`; it ranks fewer incorrect groups ahead of more, with losses after wins.
