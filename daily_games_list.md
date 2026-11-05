# Daily Browser Games — Reference List

A categorized collection of daily puzzle and casual browser games for research and inspiration. This is a broad reference list, **not** an exhaustive catalog. Some games require subscriptions, accounts, or have both daily and unlimited modes. Links and availability can change.

## Word and Vocabulary

| Game | Description | Link |
|---|---|---|
| Wordle | Guess a five-letter word in six tries. | https://www.nytimes.com/games/wordle/index.html |
| Connections | Sort 16 words into four related groups. | https://www.nytimes.com/games/connections |
| Strands | Find themed words in a letter grid. | https://www.nytimes.com/games/strands |
| Spelling Bee | Make words from seven letters, including a center letter. | https://www.nytimes.com/puzzles/spelling-bee |
| NYT Mini Crossword | Small daily crossword. | https://www.nytimes.com/crosswords/game/mini |
| Quordle | Solve four Wordle-style grids simultaneously. | https://www.merriam-webster.com/games/quordle/ |
| Octordle | Solve eight Wordle-style grids simultaneously. | https://www.britannica.com/games/octordle/ |
| Dordle | Solve two Wordle-style grids simultaneously. | https://zaratustra.itch.io/dordle |
| Waffle | Swap letters to complete intersecting words. | https://wafflegame.net/ |
| Squaredle | Find words by connecting letters in a grid. | https://squaredle.app/ |
| Weaver | Transform one word into another one letter at a time. | https://wordwormdormdork.com/ |
| Semantle | Find a secret word through semantic similarity clues. | https://semantle.com/ |
| Contexto | Guess a word using semantic ranking feedback. | https://contexto.me/ |
| Redactle | Reveal a hidden article by guessing its words. | https://redactle.net/ |
| Letter Boxed | Connect letters around a square to form words. | https://www.nytimes.com/puzzles/letter-boxed |
| The Guardian Quick Crossword | Daily classic crossword clues. | https://www.theguardian.com/crosswords/series/quick |

## Logic, Grid, and Number Puzzles

| Game | Description | Link |
|---|---|---|
| LinkedIn Queens | Place queens so each row, column, and region has one, with no touching. | https://www.linkedin.com/games/queens/ |
| LinkedIn Pinpoint | Identify the shared category from sequential clues. | https://www.linkedin.com/games/pinpoint/ |
| LinkedIn Crossclimb | Solve clues and arrange words in a letter-change ladder. | https://www.linkedin.com/games/crossclimb/ |
| LinkedIn Tango | Complete a binary logic grid using symbol constraints. | https://www.linkedin.com/games/tango/ |
| LinkedIn Zip | Connect numbers in order with a path across the grid. | https://www.linkedin.com/games/zip/ |
| NYT Sudoku | Fill the 9×9 grid with no repeated digits in rows, columns, or boxes. | https://www.nytimes.com/puzzles/sudoku |
| NYT Tiles | Match layered visual patterns. | https://www.nytimes.com/puzzles/tiles |
| NYT Vertex | Connect points to uncover an illustration. | https://www.nytimes.com/puzzles/vertex |
| Nerdle | Guess a mathematically valid equation. | https://nerdlegame.com/ |
| Mathler | Guess an equation matching a target result. | https://www.mathler.com/ |
| KenKen | Arithmetic cage puzzle with Sudoku-like constraints. | https://www.kenkenpuzzle.com/ |
| Kakuro | Fill number runs to match sum clues. | https://www.kakuro.com/ |
| Nonograms / Picross | Reveal pixel art using row/column clues. | https://www.nonograms.org/ |
| Set Daily Puzzle | Identify sets of three cards whose attributes match or differ. | https://www.setgame.com/set/puzzle |

## Geography and Maps

| Game | Description | Link |
|---|---|---|
| Worldle | Guess a country from its silhouette. | https://worldle.teuteuf.fr/ |
| Globle | Find the mystery country using geographic proximity. | https://globle-game.com/ |
| GeoGuessr Daily Challenge | Identify locations from Street View clues. | https://www.geoguessr.com/ |
| Travle | Find a path through countries between two destinations. | https://travle.earth/ |
| Flagle | Guess the country from parts of its flag. | https://www.flagle.io/ |
| Statele | Guess a US state from outline and distance clues. | https://statele.teuteuf.fr/ |
| TimeGuessr | Guess when and where historical photographs were taken. | https://timeguessr.com/ |

## Trivia and Knowledge

| Game | Description | Link |
|---|---|---|
| The New York Times Flashback | Arrange historical events in chronological order. | https://www.nytimes.com/interactive/2023/upshot/flashback.html |
| Britannica Daily Crossword | Daily crossword from Britannica. | https://www.britannica.com/games/crossword |
| Murdle | Solve a short murder mystery using logic clues. | https://murdle.com/ |
| Framed | Guess a movie from still frames. | https://framed.wtf/ |
| Moviedle | Guess a movie from a fast sequence of clips. | https://moviedle.xyz/ |
| Costcodle | Guess the price of a Costco item. | https://costcodle.com/ |

## Music and Audio

| Game | Description | Link |
|---|---|---|
| Heardle Decades | Identify a song from short audio clips, organized by decade. | https://heardledecades.com/ |
| Bandle | Guess songs from instrument-layer clues. | https://bandle.app/ |

## Sports

| Game | Description | Link |
|---|---|---|
| Immaculate Grid | Fill a sports trivia grid with athletes matching row and column criteria. | https://www.immaculategrid.com/ |
| Weddle | Guess an NFL player using player attributes. | https://www.weddlegame.com/ |
| Poeltl | Guess an NBA player from clues. | https://poeltl.dunk.town/ |

## Design Ideas for a Daily-Games Platform

If building your own collection of games, potential mechanics include:

| Mechanic | Examples | Implementation complexity |
|---|---|---|
| Word guessing | Wordle, Quordle | Low–Medium |
| Category sorting | Connections, Pinpoint | Low–Medium |
| Grid placement | Queens, Sudoku | Medium |
| Path finding | Zip, Travle | Medium |
| Visual identification | Worldle, Flagle, Framed | Medium |
| Semantic guessing | Contexto, Semantle | Medium–High (embeddings / model data) |
| Audio recognition | Heardle-style games | Medium–High (rights and assets) |
| Crosswords | Mini, quick crossword | Medium–High (puzzle generation) |
| Trivia matrices | Immaculate Grid | Medium (knowledge base) |

### Features Worth Considering

- One puzzle per calendar day, with a deterministic puzzle ID.
- Persistent streaks and completion history.
- Shareable results without spoilers.
- Countdown to the next daily puzzle.
- Difficulty ratings and statistics.
- Archive of past puzzles (optional).
- Mobile-friendly layouts and keyboard accessibility.
- Guest play with optional account synchronization.
- An ad-supported tier plus an ad-free premium option.

> **Note:** A game's mechanics may be reusable in general, but avoid copying its branding, artwork, word lists, puzzle content, or distinctive presentation. Review applicable intellectual-property and licensing considerations before publishing a close adaptation.

## Supported Fandom Leaderboard Games

The platform also tracks 39 available direct-link fandom games promoted from `additional_daily_games.md`: Squirdle, PokeDoku,
Pokennection, PokeGrid, Weakdle, Dotadle, Valodle, Genshindle, Smashdle, MCdle, Minecraftle, CraftConnections,
Balatrodle, Stardewdle, Pufferdle, Clasherdle, Royaledle, Fortnitedle, R6dle, TFTdle, TF2dle, WoWdle, Runedle,
Spiredle, Terradle, StreetFighterdle, Ace Attorneydle, Silksongdle, Narutodle, Onepiecedle, Grandlinedle, Animedle,
Daily Animedle, GuessThe.Game, Gamedle, Steamdle, Retro Game Trivia, Isaaconnect, and Actorle.

These games currently use native manual score entry. Their clipboard parsers remain disabled until real share-result
fixtures are available; detailed status and ranking rules are in `fandom_game_capabilities.md`.
