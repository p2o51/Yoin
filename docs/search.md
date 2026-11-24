# Search, and what it may return

Status (2026-11-19): specification for adding Memories and Notes to library search and for
making the "All" results page answer the query people actually typed. Visual design,
motion and per-platform layout are **not** here — this file fixes what is searched, how
it matches, how results are ordered and capped, and what the client must read.
Companion prototype (interaction, tab A/B): `#Yoin:#14`.

## Current state, as code has it

Three structural facts explain why search feels unintuitive today:

1. **Memories and notes are not searchable at all.** `SearchResultType` is
   `{ Tracks, Albums, Artists, Playlists }` (`data/model/SearchResults.kt`). They are not
   ranked last; they are absent.
2. **The All page is ordered by a fixed sequence, not by intent** (`LibrarySearchFilter.kt`),
   with songs last and artists first.
3. **Each type keeps up to `SEARCH_ALL_ROWS_PER_TYPE = 40` rows** (`LibrarySearchFilter.kt:19`),
   rendered as full-width rows for artists. A common query therefore fills one to two screens
   with artist names before the first song appears.

## Scope: which rows a search may read

Memories and notes are read through **`MemoryLedgerDao`**, which is deliberately the only
read path in the app that is *not* scoped to a profile:

> every account's writings and scores at once … every account's memories count as the same
> listener's (owner 2026-11-08, as in `#178`)
> — `data/local/MemoryLedgerDao.kt`

Consequences, all of which the client must implement:

- **Read the whole ledger.** Searching while on a Subsonic profile must still find a note
  written yesterday on Apple Music. A "no results" answer that depends on the active
  account is a bug.
- **Writes stay per-profile.** `album_memory_titles`, `album_ratings`, `song_notes` keep
  their `(profileId, provider, albumId)` keys, and edits keep going through each table's own
  DAO. The ledger is read-only.
- **Do not implement the older product rule.** The Yoin project page still says memories are
  scoped to the current account; that predates `#178` and is stale. Where the two disagree,
  the ledger and `#178` win.

## What matches

| Result kind | Matched against |
| :--- | :--- |
| Song | track title, artist, album title |
| Album | album title, artist |
| Artist | artist name |
| Playlist | playlist title, owner |
| **Memory** | memory title (including an AI-written title), album review text, and the album's notes |
| **Note** | note body; the owning track's title/artist as a weaker match |

Matching is case- and width-insensitive for CJK; a title hit outranks a body hit.

### Memories: two distinct hits, handled differently

1. **The album hits on its own** (title/artist) *and* that album already has a memory →
   attach the memory to the album row: score, memory title, date. One row, not two.
2. **The memory's own words hit** (title, review, diary) even though the album title did not
   → emit a standalone Memory result with the matched fragment highlighted.

Both can happen for one query; that is fine and should not be deduplicated away.

**Phase 1 attachment rule:** attach a memory to an album row only on an exact
`(profileId, provider, albumId)` match against the current source. No fuzzy
title+artist matching — a wrong attachment is worse than a missing one, and cross-account
memories still surface in the Memories section below.

### Notes

A note's value is inseparable from the second it was pinned to, so:

- the result row shows the note text **and** its timestamp (and the track it belongs to);
- tapping it opens Now Playing **at that position** (`SongNote.positionMs`), not the album
  page.

## Ordering and caps

- **Per-type caps on the All page**: songs 5, albums 3, memories 2, notes 2, playlists 3 —
  each followed by "show all". `SEARCH_ALL_ROWS_PER_TYPE = 40` stays as the per-type page
  size behind "show all", not as the All page's size.
- **Artists: the cap follows the form, and the form is not settled.** Three rows if artists
  stay a vertical list; up to eight in a single-row horizontal strip if the prototype's
  compact form is chosen. Decide the form first, then take its number — a cap written before
  the form would only have to change again.
- **Sections are ordered by hit strength, not by a fixed list.** An exact or prefix title
  hit floats its section up; a query that looks like a name floats artists/albums; otherwise
  songs lead. "Songs first" is the default, not a rule.
- **The active account gets a secondary boost.** Same-score entries from the currently
  active profile sort ahead of other accounts' entries. The boost never moves a weak hit
  above a strong one.
- **A badge only on other accounts.** Entries from a non-active profile carry a light source
  badge; entries from the active profile carry none. Tapping a badged entry reuses the
  existing cross-account switch/confirm flow.

## Where the rules live

Since parity p3 (2026-11-24) the matching, ranking, the All page's layout, the chips and the
note open target are `:shared` code (`shared/src/commonMain/kotlin/com/gpo/yoin/shared/search/`):
Android wraps it in `data/search/`, iOS calls it through `YoinMemorySearch`
(`iosApp/docs/shared-kmp.md`), and other ports are checked against
`shared/src/commonTest/resources/golden/memory-search.json`. Change a rule there, then
regenerate the golden (`YOIN_WRITE_SEARCH_GOLDEN=1`) and, if Android's answer changes on
purpose, the pin (`YOIN_WRITE_SEARCH_PIN=1`).

## Explicitly out of scope here

- Card shape, visual hierarchy, highlight styling, empty states, motion, transitions.
- Tab structure (one merged Memories tab vs two) — pending the prototype decision.
- Cross-platform layout parity.

## Open items

- Fuzzy attachment of a memory to an album row by normalised title+artist — deferred; revisit
  only if phase 1 produces visible misses.
- Whether a Memory result should scroll into the diary entry that matched, or only highlight
  the fragment. Both are defensible; the prototype's answer wins.
- Ranking tunables (weights between title and body hits) — start simple, keep them in one
  place so they can be tuned without touching the matching code.
