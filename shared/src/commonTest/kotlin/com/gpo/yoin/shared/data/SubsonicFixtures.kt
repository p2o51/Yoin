package com.gpo.yoin.shared.data

/** JSON bodies adapted from the Android app's SubsonicModelsTest / SubsonicMappersTest. */
internal object SubsonicFixtures {
    fun ok(inner: String = ""): String =
        """{"subsonic-response":{"status":"ok","version":"1.16.1"${if (inner.isEmpty()) "" else ",$inner"}}}"""

    fun failed(code: Int, message: String): String =
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}"""

    val ping = """
        {"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",
          "serverVersion":"0.55.0","openSubsonic":true}}
    """.trimIndent()

    val album = ok(
        """
        "album": {
          "id": "al-1", "name": "Test Album", "artist": "Test Artist", "artistId": "ar-1",
          "coverArt": "al-1", "songCount": 10, "duration": 3600, "year": 2024, "genre": "Rock",
          "starred": "2024-01-01T00:00:00Z", "created": "2023-05-02T10:00:00.000Z", "playCount": 42,
          "releaseTypes": ["Album"], "futureField": true, "anotherFuture": [1, 2, 3],
          "song": [
            {"id": "s-1", "title": "Song One", "album": "Test Album", "artist": "Test Artist",
             "track": 1, "discNumber": 2, "duration": 240, "bitRate": 320, "albumId": "al-1", "artistId": "ar-1"}
          ]
        }
        """.trimIndent(),
    )

    val albumNoCover = ok(""""album": {"id": "al-9", "name": "Bare"}""")

    val albumList2 = ok(
        """
        "albumList2": {"album": [
          {"id": "al-1", "name": "Album A", "artist": "Artist A", "releaseTypes": ["EP"]},
          {"id": "al-2", "name": "Album B", "artist": "Artist B", "releaseTypes": ["Mixtape", "single"]}
        ]}
        """.trimIndent(),
    )

    val artists = ok(
        """
        "artists": {"ignoredArticles": "The", "index": [
          {"name": "A", "artist": [
            {"id": "ar-1", "name": "ABBA", "albumCount": 5},
            {"id": "ar-2", "name": "AC/DC", "albumCount": 12, "starred": "2024-06-01"}
          ]},
          {"name": "B", "artist": [{"id": "ar-3", "name": "Beatles", "albumCount": 20}]}
        ]}
        """.trimIndent(),
    )

    val artist = ok(
        """
        "artist": {"id": "ar-1", "name": "Pink Floyd", "albumCount": 15, "coverArt": "ar-1",
          "album": [
            {"id": "al-1", "name": "The Wall", "year": 1979},
            {"id": "al-2", "name": "Wish You Were Here", "year": 1975}
          ]}
        """.trimIndent(),
    )

    val search3 = ok(
        """
        "searchResult3": {
          "artist": [{"id": "ar-1", "name": "Queen"}],
          "album": [{"id": "al-1", "name": "A Night at the Opera"}],
          "song": [{"id": "s-1", "title": "Bohemian Rhapsody", "duration": 354}]
        }
        """.trimIndent(),
    )

    val starred2 = ok(
        """
        "starred2": {
          "artist": [{"id": "ar-1", "name": "Radiohead", "starred": "2024-06-01"}],
          "album": [{"id": "al-1", "name": "OK Computer", "starred": "2024-06-01"}],
          "song": [{"id": "s-1", "title": "Karma Police", "starred": "2024-06-01"}]
        }
        """.trimIndent(),
    )

    val randomSongs = ok(
        """
        "randomSongs": {"song": [
          {"id": "s-99", "parent": "al-1", "title": "Full Song", "album": "Full Album", "artist": "Full Artist",
           "track": 3, "year": 2023, "genre": "Jazz", "coverArt": "s-99", "size": 8500000,
           "contentType": "audio/flac", "suffix": "flac", "duration": 300, "bitRate": 1411,
           "path": "Full Artist/Full Album/03 - Full Song.flac", "albumId": "al-1", "artistId": "ar-1",
           "starred": "2024-01-15T10:30:00Z", "userRating": 5, "playCount": 7},
          {"id": "s-2", "title": "Random Song 2", "duration": 210}
        ]}
        """.trimIndent(),
    )

    val playlists = ok(
        """
        "playlists": {"playlist": [
          {"id": "pl1", "name": "Owned", "owner": "alice", "songCount": 3, "duration": 600},
          {"id": "pl2", "name": "Shared", "owner": "bob", "public": true, "coverArt": "pl-pl2"},
          {"id": "pl3", "name": "Smart", "owner": "Alice", "readonly": true},
          {"id": "pl4", "name": "Legacy"}
        ]}
        """.trimIndent(),
    )

    val playlist = ok(
        """
        "playlist": {"id": "pl1", "name": "Owned", "owner": "alice", "comment": "for the road",
          "public": false, "created": "2024-01-01T00:00:00Z", "changed": "2024-02-01T00:00:00Z",
          "entry": [{"id": "s-1", "title": "One"}, {"id": "s-2", "title": "Two"}]}
        """.trimIndent(),
    )

    val structuredLyrics = ok(
        """
        "lyricsList": {"structuredLyrics": [
          {"lang": "eng", "synced": true, "offset": 100, "line": [
            {"start": 5000, "value": "Second line"},
            {"start": 0, "value": "First line"}
          ]},
          {"lang": "xxx", "synced": false, "line": [{"value": "ignored"}]}
        ]}
        """.trimIndent(),
    )

    val unsyncedStructuredLyrics = ok(
        """
        "lyricsList": {"structuredLyrics": [
          {"lang": "und", "synced": false, "line": [{"value": "one"}, {"value": "two"}]}
        ]}
        """.trimIndent(),
    )

    val emptyLyricsList = ok(""""lyricsList": {}""")

    val plainLyrics = ok(""""lyrics": {"artist": "Queen", "title": "Bohemian Rhapsody", "value": "Is this the real life?\nIs this just fantasy?"}""")

    val plainLrcLyrics = ok(""""lyrics": {"artist": "A", "title": "B", "value": "[00:01.00]timed\n[00:02.50]lines"}""")
}
