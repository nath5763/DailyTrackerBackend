# Fandom leaderboard capabilities

Thirty-nine direct-link fandom candidates from `additional_daily_games.md` are active leaderboard games. They support
native manual score entry. They are deliberately `MANUAL_ONLY`: no clipboard format is accepted until an unedited,
attributable share result establishes the signature, puzzle identity, completion semantics, and score fields.

Manual submissions are labeled unverified, retain the original native metric, and use the explicit puzzle date entered
by the player. Games with multiple modes require a mode, which becomes part of both the canonical puzzle key and ranking
compatibility key.

| Games | Manual metric | Ranking | Clipboard status |
|---|---|---|---|
| Squirdle, Weakdle, Dotadle, Valodle, Genshindle, Smashdle, MCdle, Minecraftle, Balatrodle, Stardewdle, Pufferdle, Clasherdle, Royaledle, Fortnitedle, R6dle, TFTdle, TF2dle, WoWdle, Runedle, Spiredle, Terradle, StreetFighterdle, Ace Attorneydle, Silksongdle, Narutodle, Onepiecedle, Grandlinedle, Animedle, Daily Animedle, GuessThe.Game, Gamedle, Steamdle, Retro Game Trivia, Actorle | Native guesses or progressive clues used | Wins before losses, then lower count | MANUAL_ONLY |
| Pokennection, CraftConnections, Isaaconnect | Incorrect group submissions | Wins before losses, then fewer mistakes | MANUAL_ONLY |
| PokeDoku, PokeGrid | Correct cells and optional displayed rarity | Wins before losses, then more correct cells, then lower known rarity | MANUAL_ONLY |

Missing rarity remains null and sorts after a known rarity at the same completion count; it is never converted to zero.
A completed grid must have nine correct cells, while a failed grid must have fewer than nine.

## Evidence still required for clipboard parsing

For each game, capture the unedited clipboard text for a win and a loss or incomplete result, plus every supported mode.
The fixture must expose a publisher-recognizable name or URL and an explicit puzzle number or date. A screenshot or emoji
grid without a signature is insufficient. Once fixtures are available, replace that game's manual-only adapter with a
strict parser and add it to Discord `/daily` autocomplete.

Itemdle remains candidate-only because the source document provides a directory listing rather than a direct game URL.
CineNerdle also remains candidate-only because its documented domain redirected to a domain-sale page during the
2026-11-05 availability audit.
