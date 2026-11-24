package com.gpo.yoin.shared.openin

/*
 * The <head> of open.spotify.com pages saved 2026-11-24 (Accept-Language: en), as served: the long
 * og:restrictions:country:allowed and music:song lists cut to their first entries, scripts and styles left out.
 */
internal object SpotifyPageFixtures {
    val TRACK_MR_BRIGHTSIDE = """
<!DOCTYPE html><html lang="en" dir="ltr"><head><meta charSet="utf-8"/>
<title>Mr. Brightside - Song, Lyrics, and Music Video by The Killers | Spotify</title>
<meta http-equiv="X-UA-Compatible" content="IE=9"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<meta property="og:site_name" content="Spotify"/>
<meta property="fb:app_id" content="174829003346"/>
<meta property="og:title" content="Mr. Brightside"/>
<meta property="og:description" content="The Killers · Hot Fuss · Song · 2004"/>
<meta property="og:url" content="https://open.spotify.com/track/3n3Ppam7vgaVa1iaRUc9Lp"/>
<meta property="og:type" content="music.song"/>
<meta property="og:site_name" content="Spotify"/>
<meta property="og:restrictions:country:allowed" content="AR"/>
<meta property="og:restrictions:country:allowed" content="AU"/>
<meta property="og:audio" content="https://p.scdn.co/mp3-preview/2680d69581c6b0f816db29fa8ef6a612965c9a17"/>
<meta property="og:audio:type" content="audio/mpeg"/>
<meta property="og:image" content="https://i.scdn.co/image/ab67616d0000b2739c284a6855f4945dc5a3cd73"/>
<meta property="og:image:width" content="640"/>
<meta property="og:image:height" content="640"/>
<meta property="spotify:chat_preview" content="true"/>
<meta name="twitter:site" content="@spotify"/>
<meta name="twitter:title" content="Mr. Brightside"/>
<meta name="twitter:description" content="The Killers · Hot Fuss · Song · 2004"/>
<meta name="twitter:image" content="https://i.scdn.co/image/ab67616d0000b2739c284a6855f4945dc5a3cd73"/>
<meta name="twitter:card" content="summary"/>
<meta name="music:duration" content="222"/>
<meta name="music:album" content="https://open.spotify.com/album/4OHNH3sDzIxnmUADXzv2kT"/>
<meta name="music:album:track" content="2"/>
<meta name="music:release_date" content="2004-06-15"/>
<meta name="al:android:app_name" content="Spotify"/>
<meta name="al:android:package" content="com.spotify.music"/>
<meta name="al:android:url" content="spotify://track/3n3Ppam7vgaVa1iaRUc9Lp"/>
<meta name="al:ios:app_name" content="Spotify"/>
<meta name="al:ios:app_store_id" content="324684580"/>
<meta name="al:ios:url" content="spotify://track/3n3Ppam7vgaVa1iaRUc9Lp"/>
<meta name="music:musician" content="https://open.spotify.com/artist/0C0XlULifJtAgn6ZNCW2eu"/>
<meta name="music:musician_description" content="The Killers"/>
<meta name="description" content="Listen to Mr. Brightside on Spotify. Song · The Killers · 2004"/>
<meta name="google" content="notranslate"/></head><body><div id="main"></div></body></html>
""".trimIndent()

    val TRACK_STAY = """
<!DOCTYPE html><html lang="en" dir="ltr"><head><meta charSet="utf-8"/>
<title>STAY (with Justin Bieber) - Song, Lyrics, and Music Video by The Kid LAROI, Justin Bieber | Spotify</title>
<meta http-equiv="X-UA-Compatible" content="IE=9"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<meta property="og:site_name" content="Spotify"/>
<meta property="fb:app_id" content="174829003346"/>
<meta property="og:title" content="STAY (with Justin Bieber)"/>
<meta property="og:description" content="The Kid LAROI, Justin Bieber · F*CK LOVE 3: OVER YOU · Song · 2021"/>
<meta property="og:url" content="https://open.spotify.com/track/5PjdY0CKGZdEuoNab3yDmX"/>
<meta property="og:type" content="music.song"/>
<meta property="og:site_name" content="Spotify"/>
<meta property="og:audio" content="https://p.scdn.co/mp3-preview/718a2eb032524494a01f73d80af2bcf176c9f3e2"/>
<meta property="og:audio:type" content="audio/mpeg"/>
<meta property="og:image" content="https://i.scdn.co/image/ab67616d0000b2738e6551a2944764bc8e33a960"/>
<meta property="og:image:width" content="640"/>
<meta property="og:image:height" content="640"/>
<meta property="spotify:chat_preview" content="true"/>
<meta name="twitter:site" content="@spotify"/>
<meta name="twitter:title" content="STAY (with Justin Bieber)"/>
<meta name="twitter:description" content="The Kid LAROI, Justin Bieber · F*CK LOVE 3: OVER YOU · Song · 2021"/>
<meta name="twitter:image" content="https://i.scdn.co/image/ab67616d0000b2738e6551a2944764bc8e33a960"/>
<meta name="twitter:card" content="summary"/>
<meta name="music:duration" content="142"/>
<meta name="music:album" content="https://open.spotify.com/album/4bZJWQhHKJckFLJuYdvyX2"/>
<meta name="music:album:track" content="3"/>
<meta name="music:release_date" content="2021-07-23"/>
<meta name="al:android:app_name" content="Spotify"/>
<meta name="al:android:package" content="com.spotify.music"/>
<meta name="al:android:url" content="spotify://track/5PjdY0CKGZdEuoNab3yDmX"/>
<meta name="al:ios:app_name" content="Spotify"/>
<meta name="al:ios:app_store_id" content="324684580"/>
<meta name="al:ios:url" content="spotify://track/5PjdY0CKGZdEuoNab3yDmX"/>
<meta name="music:musician" content="https://open.spotify.com/artist/2tIP7SsRs7vjIcLrU85W8J"/>
<meta name="music:musician" content="https://open.spotify.com/artist/1uNFoZAHBGtllmzznpCI3s"/>
<meta name="music:musician_description" content="The Kid LAROI, Justin Bieber"/>
<meta name="description" content="Listen to STAY (with Justin Bieber) on Spotify. Song · The Kid LAROI, Justin Bieber · 2021"/>
<meta name="google" content="notranslate"/></head><body><div id="main"></div></body></html>
""".trimIndent()

    val TRACK_YORU_NI_KAKERU = """
<!DOCTYPE html><html lang="en" dir="ltr"><head><meta charSet="utf-8"/>
<title>夜に駆ける - Song, Lyrics, and Music Video by YOASOBI | Spotify</title>
<meta http-equiv="X-UA-Compatible" content="IE=9"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<meta property="og:site_name" content="Spotify"/>
<meta property="fb:app_id" content="174829003346"/>
<meta property="og:title" content="夜に駆ける"/>
<meta property="og:description" content="YOASOBI · 夜に駆ける · Song · 2019"/>
<meta property="og:url" content="https://open.spotify.com/track/3dPtXHP0oXQ4HCWHsOA9js"/>
<meta property="og:type" content="music.song"/>
<meta property="og:site_name" content="Spotify"/>
<meta property="og:restrictions:country:allowed" content="AR"/>
<meta property="og:restrictions:country:allowed" content="AU"/>
<meta property="og:audio" content="https://p.scdn.co/mp3-preview/5c0ebbde998ebc47606517eef95ea07cd6bbe921"/>
<meta property="og:audio:type" content="audio/mpeg"/>
<meta property="og:image" content="https://i.scdn.co/image/ab67616d0000b273c5716278abba6a103ad13aa7"/>
<meta property="og:image:width" content="640"/>
<meta property="og:image:height" content="640"/>
<meta property="spotify:chat_preview" content="true"/>
<meta name="twitter:site" content="@spotify"/>
<meta name="twitter:title" content="夜に駆ける"/>
<meta name="twitter:description" content="YOASOBI · 夜に駆ける · Song · 2019"/>
<meta name="twitter:image" content="https://i.scdn.co/image/ab67616d0000b273c5716278abba6a103ad13aa7"/>
<meta name="twitter:card" content="summary"/>
<meta name="music:duration" content="261"/>
<meta name="music:album" content="https://open.spotify.com/album/3GzwPyPZCyrqUTaurTaS23"/>
<meta name="music:album:track" content="1"/>
<meta name="music:release_date" content="2019-12-15"/>
<meta name="al:android:app_name" content="Spotify"/>
<meta name="al:android:package" content="com.spotify.music"/>
<meta name="al:android:url" content="spotify://track/3dPtXHP0oXQ4HCWHsOA9js"/>
<meta name="al:ios:app_name" content="Spotify"/>
<meta name="al:ios:app_store_id" content="324684580"/>
<meta name="al:ios:url" content="spotify://track/3dPtXHP0oXQ4HCWHsOA9js"/>
<meta name="music:musician" content="https://open.spotify.com/artist/64tJ2EAv1R6UaZqc4iOCyj"/>
<meta name="music:musician_description" content="YOASOBI"/>
<meta name="description" content="Listen to 夜に駆ける on Spotify. Song · YOASOBI · 2019"/>
<meta name="google" content="notranslate"/></head><body><div id="main"></div></body></html>
""".trimIndent()

    val ALBUM_AFTER_HOURS = """
<!DOCTYPE html><html lang="en" dir="ltr"><head><meta charSet="utf-8"/>
<title>Listen to After Hours - Album by The Weeknd | Spotify</title>
<meta http-equiv="X-UA-Compatible" content="IE=9"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<meta property="og:site_name" content="Spotify"/>
<meta property="fb:app_id" content="174829003346"/>
<meta property="og:title" content="Listen to After Hours - Album by The Weeknd | Spotify"/>
<meta property="og:description" content="The Weeknd · album · 2020 · 14 songs"/>
<meta property="og:url" content="https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj"/>
<meta property="og:type" content="music.album"/>
<meta property="og:site_name" content="Spotify"/>
<meta property="og:restrictions:country:allowed" content="AR"/>
<meta property="og:restrictions:country:allowed" content="AU"/>
<meta property="og:image" content="https://i.scdn.co/image/ab67616d0000b2738863bc11d2aa12b54f5aeb36"/>
<meta property="og:image:width" content="640"/>
<meta property="og:image:height" content="640"/>
<meta name="description" content="Listen to After Hours on Spotify · album · The Weeknd · 2020 · 14 songs"/>
<meta name="google" content="notranslate"/>
<meta name="music:musician" content="https://open.spotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ"/>
<meta name="music:release_date" content="2020-03-20"/>
<meta name="music:song" content="https://open.spotify.com/track/6b5P51m8xx2XA6U7sdNZ5E"/>
<meta name="music:song:disc" content="1"/>
<meta name="music:song:track" content="1"/>
<meta name="music:song" content="https://open.spotify.com/track/2K9Ovn1o2bTGqbsABGC6m3"/>
<meta name="music:song:disc" content="1"/>
<meta name="music:song:track" content="2"/>
<meta name="al:android:app_name" content="Spotify"/>
<meta name="al:android:package" content="com.spotify.music"/>
<meta name="al:android:url" content="spotify://album/4yP0hdKOZPNshxUOjY0cZj"/>
<meta name="al:ios:app_name" content="Spotify"/>
<meta name="al:ios:app_store_id" content="324684580"/>
<meta name="al:ios:url" content="spotify://album/4yP0hdKOZPNshxUOjY0cZj"/>
<meta name="twitter:site" content="@spotify"/>
<meta name="twitter:title" content="Listen to After Hours - Album by The Weeknd | Spotify"/>
<meta name="twitter:description" content="The Weeknd · album · 2020 · 14 songs"/>
<meta name="twitter:image" content="https://i.scdn.co/image/ab67616d0000b2738863bc11d2aa12b54f5aeb36"/>
<meta name="twitter:card" content="summary"/></head><body><div id="main"></div></body></html>
""".trimIndent()

    val ARTIST_THE_WEEKND = """
<!DOCTYPE html><html lang="en" dir="ltr"><head><meta charSet="utf-8"/>
<title>Listen to The Weeknd Songs | Spotify</title>
<meta http-equiv="X-UA-Compatible" content="IE=9"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<meta property="og:site_name" content="Spotify"/>
<meta property="fb:app_id" content="174829003346"/>
<meta property="og:title" content="The Weeknd"/>
<meta property="og:description" content="Artist · 117.4M monthly listeners."/>
<meta property="og:url" content="https://open.spotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ"/>
<meta property="og:type" content="website"/>
<meta property="og:site_name" content="Spotify"/>
<meta property="og:image" content="https://i.scdn.co/image/ab6761610000e5ebc1719ac9e6a75c1c25835018"/>
<meta property="og:image:width" content="640"/>
<meta property="og:image:height" content="640"/>
<meta name="description" content="Listen to The Weeknd on Spotify. Artist · 117.4M monthly listeners."/>
<meta name="google" content="notranslate"/>
<meta name="al:android:app_name" content="Spotify"/>
<meta name="al:android:package" content="com.spotify.music"/>
<meta name="al:android:url" content="spotify://artist/1Xyo4u8uXC1ZmMpatF05PJ"/>
<meta name="al:ios:app_name" content="Spotify"/>
<meta name="al:ios:app_store_id" content="324684580"/>
<meta name="al:ios:url" content="spotify://artist/1Xyo4u8uXC1ZmMpatF05PJ"/>
<meta name="twitter:site" content="@spotify"/>
<meta name="twitter:title" content="The Weeknd"/>
<meta name="twitter:description" content="Artist · 117.4M monthly listeners."/>
<meta name="twitter:image" content="https://i.scdn.co/image/ab6761610000e5ebc1719ac9e6a75c1c25835018"/>
<meta name="twitter:card" content="summary"/></head><body><div id="main"></div></body></html>
""".trimIndent()

    val PLAYLIST_TODAYS_TOP_HITS = """
<!DOCTYPE html><html lang="en" dir="ltr"><head><meta charSet="utf-8"/>
<title>Today’s Top Hits | Spotify Playlist</title>
<meta http-equiv="X-UA-Compatible" content="IE=9"/>
<meta name="viewport" content="width=device-width, initial-scale=1"/>
<meta property="og:site_name" content="Spotify"/>
<meta property="fb:app_id" content="174829003346"/>
<meta property="og:title" content="Today’s Top Hits"/>
<meta property="og:description" content="Playlist · Spotify · 50 items · 33.5M saves"/>
<meta property="og:url" content="https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"/>
<meta property="og:type" content="music.playlist"/>
<meta property="og:site_name" content="Spotify"/>
<meta property="og:restrictions:country:allowed" content="AR"/>
<meta property="og:restrictions:country:allowed" content="AU"/>
<meta property="og:image" content="https://i.scdn.co/image/ab67706f00000002ffe80c69d2e795ea01e63447"/>
<meta name="description" content="Listen to Playlist · Today’s Top Hits · 50 items · 33.5M saves"/>
<meta name="google" content="notranslate"/>
<meta name="music:creator" content="https://open.spotify.com/user/spotify"/>
<meta name="music:song" content="https://open.spotify.com/track/4HCOHRCSQelz2GPX8iTamO"/>
<meta name="music:song:disc" content="1"/>
<meta name="music:song:track" content="1"/>
<meta name="music:song" content="https://open.spotify.com/track/2sFepQNyl1WjpROruP8bJ0"/>
<meta name="music:song:disc" content="1"/>
<meta name="music:song:track" content="2"/>
<meta name="music:song_count" content="50"/>
<meta name="al:android:app_name" content="Spotify"/>
<meta name="al:android:package" content="com.spotify.music"/>
<meta name="al:android:url" content="spotify://playlist/37i9dQZF1DXcBWIGoYBM5M"/>
<meta name="al:ios:app_name" content="Spotify"/>
<meta name="al:ios:app_store_id" content="324684580"/>
<meta name="al:ios:url" content="spotify://playlist/37i9dQZF1DXcBWIGoYBM5M"/>
<meta name="twitter:site" content="@spotify"/>
<meta name="twitter:title" content="Today’s Top Hits"/>
<meta name="twitter:description" content="Playlist · Spotify · 50 items · 33.5M saves"/>
<meta name="twitter:image" content="https://i.scdn.co/image/ab67706f00000002ffe80c69d2e795ea01e63447"/>
<meta name="twitter:card" content="summary"/></head><body><div id="main"></div></body></html>
""".trimIndent()
}
