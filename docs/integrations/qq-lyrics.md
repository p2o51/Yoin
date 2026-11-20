# QQ Music lyrics

Status (2026-11-19): the `GetPlayLyricInfo` gateway route is the primary source for
both the original lyric and the translation. Measured on this machine with anonymous
requests — no cookie, no login, no `g_tk` beyond the default.

## What the client does

`app/src/main/java/com/gpo/yoin/data/lyrics/QQLyricsProvider.kt` calls
`music.musichallSong.PlayLyricInfo.GetPlayLyricInfo` on the `u.y.qq.com` gateway and
reads two fields from `req_1.data`:

| Field | Shape | Use |
| :--- | :--- | :--- |
| `lyric` | base64 LRC | the original lyric |
| `trans` | base64 LRC | line-aligned translation; `//` marks a line QQ has no translation for |

`trans` is only populated when the request asks for it. Yoin sends
`{"songID": …, "format": "json", "crypt": 0, "qrc": 0, "roma": 0, "trans": 1}`.
Both `u.y.qq.com` and `u6.y.qq.com` answer identically, so the existing host fallback
is sound.

## Why this is not the legacy web route

`c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg` still returns the original lyric,
but its `trans` field has been an empty string for every track tested, with or without
`nobase64` and regardless of referer, user agent or `g_tk`. The web route survives in
`QQLyricsProvider` only as a fallback for songs the gateway returns as encrypted
(verbatim QRC-only tracks).

An independent project hit the same wall and reached the same conclusion:
[NeriPlayer `7f684d1`](https://github.com/cwuom/NeriPlayer/commit/7f684d1194caea557168490ef95f723c37832)
("ask QQ Music for the translation it will not send unprompted") moves the lookup to
`GetPlayLyricInfo`, passes `trans=1`, drops the `//` placeholder lines, and notes that
`songMID` alone is enough.

## Measured behaviour (2026-11-19)

Seven tracks, anonymous, `songID` + `trans:1`:

| Query | Matched title | Translated lines | `crypt` |
| :--- | :--- | ---: | ---: |
| Shape of You Ed Sheeran | Shape of You | 138 | 0 |
| Numb Linkin Park | Numb | 43 | 0 |
| bad guy Billie Eilish | bad guy | 52 | 0 |
| Elizabeth Taylor Taylor Swift | Elizabeth Taylor | 69 | 0 |
| 夜に駆ける YOASOBI | 夜に駆ける | 71 | 0 |
| Lemon 米津玄師 | Lemon | 59 | 0 |
| 晴天 周杰伦 | 晴天 | 0 (expected) | 0 |

Parameter matrix, same track:

| Request | `trans` characters | `lyric` characters |
| :--- | ---: | ---: |
| `songID` + `trans:1` | 2562 | 4037 |
| `songID`, no `trans` | 0 | 4037 |
| `songID` + `trans:0` | 0 | 4037 |
| `songID` only | 0 | 4037 |
| `songMID` + `trans:1` | 2562 | 4037 |
| `songMID` + `songID` + `trans:1` | 2562 | 4037 |

Two consequences worth keeping in mind:

- **`trans: 1` is the switch.** Omitting it is not "fewer fields", it is "no
  translation at all" — the kind of silence that looks like a server-side change.
- **`songMID` alone is sufficient**, so a path that only has a mid (the web search
  fallback) does not need to resolve the numeric id first.

Japanese tracks also carry a `[kana:…]` line inside `trans`; `QQLyricsProvider` already
strips it, and unstripped it would render as a stray line.

## Reproducing

The probe that produced the tables above is a single file with no dependencies beyond
the Python standard library. It prints the whole evidence set as JSON, including raw
decoded translation samples, so a future change in the endpoint shows up as a diff
rather than as a quiet empty field.

## Open questions

- None of the sampled tracks returned encrypted (`crypt: 1`) lyrics, so the
  "gateway says encrypted → fall back to the web route" branch is still reasoned
  rather than observed. A QRC-only track would close that gap.
- `hasMultiTrans` is `true` on every sampled track and `transSource` was `0`
  throughout, so it is unproven whether a track can carry several translations that
  need selecting.
