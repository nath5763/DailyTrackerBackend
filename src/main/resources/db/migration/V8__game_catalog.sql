create table game_catalog (
    slug varchar(80) primary key,
    name varchar(120) not null,
    description varchar(500) not null,
    category varchar(80) not null,
    source_url varchar(500) not null,
    tracked_game_slug varchar(80) references games(slug) on delete set null,
    display_order integer not null
);

alter table submissions drop constraint if exists submissions_primary_score_check;
alter table submissions add constraint submissions_primary_score_check check (primary_score >= 0);

insert into games (id, slug, name, source_url, release_timezone, ranking_direction, active, created_at) values
('f0000000-0000-4000-8000-000000000003', 'wordle', 'Wordle', 'https://www.nytimes.com/games/wordle/index.html', 'America/New_York', 'lower', true, now()),
('f0000000-0000-4000-8000-000000000004', 'pinpoint', 'Pinpoint', 'https://www.linkedin.com/games/pinpoint/', 'America/Los_Angeles', 'lower', true, now()),
('f0000000-0000-4000-8000-000000000005', 'queens', 'Queens', 'https://www.linkedin.com/games/queens/', 'America/Los_Angeles', 'lower', true, now()),
('f0000000-0000-4000-8000-000000000006', 'crossclimb', 'Crossclimb', 'https://www.linkedin.com/games/crossclimb/', 'America/Los_Angeles', 'lower', true, now()),
('f0000000-0000-4000-8000-000000000007', 'tango', 'Tango', 'https://www.linkedin.com/games/tango/', 'America/Los_Angeles', 'lower', true, now()),
('f0000000-0000-4000-8000-000000000008', 'zip', 'Zip', 'https://www.linkedin.com/games/zip/', 'America/Los_Angeles', 'lower', true, now());

insert into game_catalog (slug, name, description, category, source_url, tracked_game_slug, display_order) values
('wordle', 'Wordle', 'Guess a five-letter word in six tries.', 'Word and Vocabulary', 'https://www.nytimes.com/games/wordle/index.html', 'wordle', 1),
('connections', 'Connections', 'Sort 16 words into four related groups.', 'Word and Vocabulary', 'https://www.nytimes.com/games/connections', null, 2),
('strands', 'Strands', 'Find themed words in a letter grid.', 'Word and Vocabulary', 'https://www.nytimes.com/games/strands', null, 3),
('spelling-bee', 'Spelling Bee', 'Make words from seven letters, including a center letter.', 'Word and Vocabulary', 'https://www.nytimes.com/puzzles/spelling-bee', null, 4),
('nyt-mini-crossword', 'NYT Mini Crossword', 'Small daily crossword.', 'Word and Vocabulary', 'https://www.nytimes.com/crosswords/game/mini', null, 5),
('quordle', 'Quordle', 'Solve four Wordle-style grids simultaneously.', 'Word and Vocabulary', 'https://www.merriam-webster.com/games/quordle/', null, 6),
('octordle', 'Octordle', 'Solve eight Wordle-style grids simultaneously.', 'Word and Vocabulary', 'https://www.britannica.com/games/octordle/daily', null, 7),
('dordle', 'Dordle', 'Solve two Wordle-style grids simultaneously.', 'Word and Vocabulary', 'https://zaratustra.itch.io/dordle', null, 8),
('waffle', 'Waffle', 'Swap letters to complete intersecting words.', 'Word and Vocabulary', 'https://wafflegame.net/', null, 9),
('squaredle', 'Squaredle', 'Find words by connecting letters in a grid.', 'Word and Vocabulary', 'https://squaredle.app/', null, 10),
('weaver', 'Weaver', 'Transform one word into another one letter at a time.', 'Word and Vocabulary', 'https://wordwormdormdork.com/', null, 11),
('semantle', 'Semantle', 'Find a secret word through semantic similarity clues.', 'Word and Vocabulary', 'https://semantle.com/', null, 12),
('contexto', 'Contexto', 'Guess a word using semantic ranking feedback.', 'Word and Vocabulary', 'https://contexto.me/', null, 13),
('redactle', 'Redactle', 'Reveal a hidden article by guessing its words.', 'Word and Vocabulary', 'https://redactle.net/', null, 14),
('letter-boxed', 'Letter Boxed', 'Connect letters around a square to form words.', 'Word and Vocabulary', 'https://www.nytimes.com/puzzles/letter-boxed', null, 15),
('guardian-quick-crossword', 'The Guardian Quick Crossword', 'Daily classic crossword clues.', 'Word and Vocabulary', 'https://www.theguardian.com/crosswords/series/quick', null, 16),
('queens', 'LinkedIn Queens', 'Place queens so each row, column, and region has one, with no touching.', 'Logic, Grid, and Number Puzzles', 'https://www.linkedin.com/games/queens/', 'queens', 17),
('pinpoint', 'LinkedIn Pinpoint', 'Identify the shared category from sequential clues.', 'Logic, Grid, and Number Puzzles', 'https://www.linkedin.com/games/pinpoint/', 'pinpoint', 18),
('crossclimb', 'LinkedIn Crossclimb', 'Solve clues and arrange words in a letter-change ladder.', 'Logic, Grid, and Number Puzzles', 'https://www.linkedin.com/games/crossclimb/', 'crossclimb', 19),
('tango', 'LinkedIn Tango', 'Complete a binary logic grid using symbol constraints.', 'Logic, Grid, and Number Puzzles', 'https://www.linkedin.com/games/tango/', 'tango', 20),
('zip', 'LinkedIn Zip', 'Connect numbers in order with a path across the grid.', 'Logic, Grid, and Number Puzzles', 'https://www.linkedin.com/games/zip/', 'zip', 21),
('nyt-sudoku', 'NYT Sudoku', 'Fill the 9×9 grid with no repeated digits in rows, columns, or boxes.', 'Logic, Grid, and Number Puzzles', 'https://www.nytimes.com/puzzles/sudoku', null, 22),
('nyt-tiles', 'NYT Tiles', 'Match layered visual patterns.', 'Logic, Grid, and Number Puzzles', 'https://www.nytimes.com/puzzles/tiles', null, 23),
('nerdle', 'Nerdle', 'Guess a mathematically valid equation.', 'Logic, Grid, and Number Puzzles', 'https://nerdlegame.com/', null, 24),
('mathler', 'Mathler', 'Guess an equation matching a target result.', 'Logic, Grid, and Number Puzzles', 'https://www.mathler.com/', null, 25),
('kenken', 'KenKen', 'Arithmetic cage puzzle with Sudoku-like constraints.', 'Logic, Grid, and Number Puzzles', 'https://www.kenkenpuzzle.com/', null, 26),
('kakuro', 'Kakuro', 'Fill number runs to match sum clues.', 'Logic, Grid, and Number Puzzles', 'https://www.kakuro.com/', null, 27),
('nonograms-picross', 'Nonograms / Picross', 'Reveal pixel art using row/column clues.', 'Logic, Grid, and Number Puzzles', 'https://www.nonograms.org/', null, 28),
('worldle', 'Worldle', 'Guess a country from its silhouette.', 'Geography and Maps', 'https://worldle.teuteuf.fr/', null, 30),
('globle', 'Globle', 'Find the mystery country using geographic proximity.', 'Geography and Maps', 'https://globle-game.com/', null, 31),
('geoguessr-daily-challenge', 'GeoGuessr Daily Challenge', 'Identify locations from Street View clues.', 'Geography and Maps', 'https://www.geoguessr.com/', null, 32),
('travle', 'Travle', 'Find a path through countries between two destinations.', 'Geography and Maps', 'https://travle.earth/', null, 33),
('flagle', 'Flagle', 'Guess the country from parts of its flag.', 'Geography and Maps', 'https://www.flagle.io/', null, 34),
('statele', 'Statele', 'Guess a US state from outline and distance clues.', 'Geography and Maps', 'https://statele.teuteuf.fr/', null, 35),
('timeguessr', 'TimeGuessr', 'Guess when and where historical photographs were taken.', 'Geography and Maps', 'https://timeguessr.com/', null, 36),
('murdle', 'Murdle', 'Solve a short murder mystery using logic clues.', 'Trivia and Knowledge', 'https://murdle.com/', null, 39),
('framed', 'Framed', 'Guess a movie from still frames.', 'Trivia and Knowledge', 'https://framed.wtf/', null, 40),
('moviedle', 'Moviedle', 'Guess a movie from a fast sequence of clips.', 'Trivia and Knowledge', 'https://moviedle.xyz/', null, 41),
('costcodle', 'Costcodle', 'Guess the price of a Costco item.', 'Trivia and Knowledge', 'https://costcodle.com/', null, 42),
('heardle-decades', 'Heardle Decades', 'Identify a song from short audio clips, organized by decade.', 'Music and Audio', 'https://heardledecades.com/', null, 43),
('bandle', 'Bandle', 'Guess songs from instrument-layer clues.', 'Music and Audio', 'https://bandle.app/', null, 44),
('immaculate-grid', 'Immaculate Grid', 'Fill a sports trivia grid with athletes matching row and column criteria.', 'Sports', 'https://new.immaculategrid.com/immaculate-grid/', null, 45),
('weddle', 'Weddle', 'Guess an NFL player using player attributes.', 'Sports', 'https://www.weddlegame.com/', null, 46),
('poeltl', 'Poeltl', 'Guess an NBA player from clues.', 'Sports', 'https://poeltl.nbpa.com/', null, 47),
('fermi', 'Fermi.gg', 'Estimate surprising quantities in three questions.', 'Logic, Grid, and Number Puzzles', 'https://fermi.gg', 'fermi', 49),
('krillion', 'Krillion', 'Dive for seven daily word discoveries.', 'Word and Vocabulary', 'https://krillion.io', 'krillion', 50);

