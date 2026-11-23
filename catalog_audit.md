# Launch game link and share-text audit

Checked 2026-11-04 against `daily_games_list.md` (48 entries) and the two existing tracked games. The public catalog contains **46 entries**: 44 from the reference list and Fermi and Krillion. Automated fetches cannot render games that require JavaScript or bypass publisher bot rules; for those, the publisher's current game/help pages and recent share links were used as corroboration. No catalog entry permits manual score entry.

## Excluded from the public catalog

| Reference entry | Reason |
|---|---|
| NYT Vertex | [Retired in August 2024](https://nyt.fandom.com/wiki/Vertex); its listed play URL no longer opens a live puzzle. |
| Set Daily Puzzle | The listed [publisher URL](https://www.setgame.com/set/puzzle) returned 502 during this audit; retry before publishing. |
| The New York Times Flashback | The reference points to a [2023 edition](https://www.nytimes.com/interactive/2023/upshot/flashback.html), while [2026 editions](https://bsky.app/profile/upshot.nytimes.com/post/3mpqyeyy3nk2t) use dated URLs. A stable current publisher play URL was not confirmed. |
| Britannica Daily Crossword | The [listed URL](https://www.britannica.com/games/crossword) could not be opened or corroborated on Britannica's current games pages. |

## Updated play links

| Game | Catalog link | Evidence |
|---|---|---|
| Octordle | https://www.britannica.com/games/octordle/daily | Current daily route used by players; Britannica blocks automated page inspection. |
| Immaculate Grid | https://new.immaculategrid.com/immaculate-grid/ | [Current publisher grid](https://new.immaculategrid.com/immaculate-grid/). |
| Poeltl | https://poeltl.nbpa.com/ | [Old publisher page](https://poeltl.dunk.town/) redirects to the NBPA game. |

All other published entries retain the reference URL. The NYT Games [current product list](https://thenewyorktimeshelpcenter.helpjuice.com/360011158491-New-York-Times-Games) corroborates its listed games despite robots restrictions; [LinkedIn Games Help](https://www.linkedin.com/help/linkedin/answer/a6863543) corroborates its five listed game routes. Other publisher URLs were opened directly: Merriam-Webster, itch.io, Waffle, Squaredle, Weaver, Semantle, Contexto, Redactle, Guardian, Nerdle, Mathler, KenKen, Kakuro, Nonograms, Worldle, Globle, GeoGuessr, Travle, Flagle, Statele, TimeGuessr, Perceptle, Gazillion, Murdle, Framed, Moviedle, Costcodle, Heardle Decades, Bandle, Weddle, Fermi, and Krillion. Some returned JavaScript shells or bot blocks rather than playable HTML; the catalog links directly to those sites.

## Score fixture sources and limits

The parser fixtures in `ShareTextParsersTest` and `extended-preview-score.test.ts` are copied from public share examples. [Wordle hard mode](https://teapaw.org/dampedspring), [Pinpoint, Queens, Crossclimb, Tango, and Zip](https://www.boards.ie/discussion/2058350094/linkedin-games/p27), plus [same-day LinkedIn examples](https://nitter.cf/contentnow). [LinkedIn's score help](https://www.linkedin.com/help/linkedin/answer/a6889085) says content differs by game and device. The parsers check the structure and do not verify that a player earned the score. Missing hint or mistake details are marked unknown and rank between explicit unassisted and assisted results. Beginner prefilled cells cannot always be inferred from copied text; `beginnerAssists` is recorded when the share explicitly reports them.
