# QQ Music Lyrics Endpoint Research Handoff

## Background

Yoin currently fetches QQ Music lyrics through the same path ported from
Spotoolfy:

- Search: `POST https://u.y.qq.com/cgi-bin/musicu.fcg`
- Lyric: `GET https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg`
- Parameters: `songmid=<MID>&format=json&nobase64=1`
- Current Yoin parser only uses the `lyric` field.
- Spotoolfy's mobile provider also knows about `trans`, but this field appears
  empty in current live tests.

Relevant local files:

- Yoin: `app/src/main/java/com/gpo/yoin/data/lyrics/QQLyricsProvider.kt`
- Yoin tests: `app/src/test/java/com/gpo/yoin/data/lyrics/QQLyricsProviderTest.kt`
- Spotoolfy: `/Users/gpo/Developer/spotoolfy_flutter/lib/services/lyrics/qq_provider_mobile.dart`

The research task is not to implement Yoin code yet. The task is to determine
the correct QQ Music endpoint and request shape for reliable synced lyrics and
translated lyrics.

## What Has Already Been Tested

The current Web lyric endpoint returns original lyrics, but translation is
empty across a small matrix of popular tracks.

Endpoint shape tested:

```text
https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=<MID>&format=json&nobase64=1
https://i.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=<MID>&format=json&nobase64=1
```

Headers/params also tested:

- `referer: https://y.qq.com/`
- `referer: https://y.qq.com/portal/player.html`
- desktop Chrome user agent
- `g_tk=5381`
- `loginUin=0`
- `hostUin=0`
- `inCharset=utf8`
- `outCharset=utf-8`
- `platform=yqq.json`
- `needNewCode=0`

Observed behavior:

| Query | Matched QQ MID | Original lyric | `trans` |
| --- | --- | --- | --- |
| 晴天 周杰伦 | `0039MnYb0qxYhV` | non-empty | empty |
| 七里香 周杰伦 | `004Z8Ihr0JIu5s` | non-empty | empty |
| 夜に駆ける YOASOBI | `003WFMXk4O5ywc` | non-empty | empty |
| Lemon 米津玄師 | `000akynZ2Rbro5` | non-empty | empty |
| Shape of You Ed Sheeran | `0041gObR2QG98x` | non-empty | empty |
| Numb Linkin Park | `004Ba7Yf4S1glA` | non-empty | empty |
| Counting Stars OneRepublic | `003F1P942q4lEs` | non-empty | empty |
| Let It Go Idina Menzel | `003UXSW23V55Qq` | non-empty | empty |
| bad guy Billie Eilish | `003eKeNV0t8IVi` | non-empty | empty |
| Elizabeth Taylor Taylor Swift | `000UrL7I1nDJLi` | non-empty | empty |

This makes the current Web endpoint unsuitable as the primary source for QQ
translated lyrics.

## External Leads

### 1. Web `fcg_query_lyric_new` route

`copws/qq-music-api` documents the older Web lyric route and parses a `trans`
field:

- Repository: https://github.com/copws/qq-music-api
- It lists the original API as
  `https://i.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg`.
- Its parser model includes `haveTrans` and per-line `trans`.

Research question:

- Is `trans` empty because anonymous Web requests no longer receive
  translations?
- Does it require cookies, login UIN, a non-default `g_tk`, another Referer,
  base64 mode, or another domain?
- Does `nobase64=0`/omitting `nobase64` change translated lyric availability?

### 2. `musicu.fcg` player detail route

Several QQ Music reverse-engineering notes point to:

```json
{
  "module": "music.musichallSong.PlayLyricInfo",
  "method": "GetPlayLyricInfo",
  "param": {
    "songMID": "<MID>",
    "songID": 123456
  }
}
```

This route usually appears inside a larger `POST https://u.y.qq.com/cgi-bin/musicu.fcg`
payload with `comm` metadata and sometimes a `sign` query parameter.

Research questions:

- Can `GetPlayLyricInfo` be called without a signed request?
- Is `songID` required, or is `songMID` enough?
- If `songID` is required, can it be obtained from Yoin's existing QQ search
  result without a second request?
- What fields does `GetPlayLyricInfo` return for original lyrics,
  translated lyrics, romanized lyrics, and karaoke/QRC lyrics?
- Are returned lyric fields plain text, base64, compressed, encrypted, or QRC?
- Does this route work anonymously, or does it need QQ cookies/login?

Suggested minimal probe:

```json
{
  "comm": {
    "cv": 4747474,
    "ct": 24,
    "format": "json",
    "inCharset": "utf-8",
    "outCharset": "utf-8",
    "notice": 0,
    "platform": "yqq.json",
    "needNewCode": 1,
    "uin": 0,
    "g_tk_new_20200303": 5381,
    "g_tk": 5381
  },
  "req_1": {
    "module": "music.musichallSong.PlayLyricInfo",
    "method": "GetPlayLyricInfo",
    "param": {
      "songMID": "000UrL7I1nDJLi"
    }
  }
}
```

Then repeat with both `songMID` and numeric `songID` if needed.

### 3. PC/QRC route

`xmcp/QRCD` uses the QQ Music PC client route and claims support for
romanized lyrics and real-time/KTV lyrics:

- Repository: https://github.com/xmcp/QRCD
- Search endpoint shown in the project:
  `https://c.y.qq.com/lyric/fcgi-bin/fcg_search_pc_lrc.fcg`
- Download endpoint shown in the project:
  `https://c.y.qq.com/qqmusic/fcgi-bin/lyric_download.fcg`
- Parameters include `version=15`, `miniversion=82`, `lrctype=4`,
  `musicid=<numeric song id>`.
- Response fields include original, translated, and romanized QRC payloads,
  but decoding requires a QRC decrypt/decompress step.

Research questions:

- Does this route still work in 2026?
- Can it return translated lyrics reliably for the same test songs?
- Can the QRC decoder be implemented safely in Kotlin/Android without bundled
  native Windows DLLs?
- Is the returned translation line-level aligned enough for Yoin's current LRC
  renderer?
- Is the route too complex or brittle for MVP compared with using NetEase for
  provider translations?

### 4. Third-party API wrappers

Some third-party API services expose `lrc` and `trans` by QQ MID, for example:

- https://api.istero.com/service/doc/qqmusic-lyrics

This is useful only as a clue. It should not be treated as a Yoin dependency
unless the product explicitly chooses to rely on a third-party service.

Research questions:

- Which upstream QQ endpoint does the wrapper likely use?
- Does it return translations for the same test matrix?
- Does it require auth, quota, or a paid token?

## Required Test Matrix

Use at least these songs so results are comparable with the current Yoin
probe:

| Query | Expected reason |
| --- | --- |
| `Elizabeth Taylor Taylor Swift` | English song, current QQ `trans` empty, NetEase has translation |
| `Shape of You Ed Sheeran` | common English track |
| `Numb Linkin Park` | common English track |
| `bad guy Billie Eilish` | common English track |
| `夜に駆ける YOASOBI` | Japanese track |
| `Lemon 米津玄師` | Japanese track |
| `晴天 周杰伦` | Chinese control case; translation may reasonably be empty |
| `七里香 周杰伦` | Chinese control case; translation may reasonably be empty |

For each candidate endpoint, record:

- request URL
- method
- headers
- query parameters
- POST body
- whether cookies/login/sign are required
- HTTP status
- response keys
- original lyric length
- translated lyric length
- romanized lyric length if available
- whether timestamps are line-level compatible with LRC
- whether result is reproducible from a clean anonymous environment

Do not include full copyrighted lyrics in the report. Lengths, field names,
and one or two timestamp examples are enough.

## Desired Research Output

Please produce a short technical report with:

1. A recommended QQ lyric endpoint for Yoin, if one exists.
2. A recommended QQ translated lyric endpoint, if one exists.
3. Exact reproducible request examples.
4. A table of the test matrix results.
5. Whether the endpoint works anonymously.
6. Whether it needs sign/cookie/login.
7. Parsing and decoding requirements.
8. Risks: rate limits, regional restrictions, fragility, legal/product
   concerns.
9. A final recommendation:
   - "Use this in Yoin now"
   - "Implement as opportunistic fallback only"
   - "Do not use; prefer NetEase/provider fallback + AI"

## Current Product Implication

Unless research finds a better QQ endpoint, Yoin should treat QQ translation as
opportunistic only. For Chinese target-language provider translation, NetEase
currently appears much more reliable because its `/lyric/new` response often
contains both `lrc` and `tlyric`.

If Yoin applies a provider translation from NetEase, it should also switch the
active applied lyric source to NetEase to avoid mixing QQ original lyrics with
NetEase translated lines.
