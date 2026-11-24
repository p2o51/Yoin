package com.gpo.yoin.shared.portability

/** The fixed export Android's BackupExportPinTest pins (app/.../PinFixture.kt), as the golden's input. */
internal object ExportFixture {
    const val SNAPSHOT = """{"format":"yoin-sync","schema":1,"epoch":0,"deviceId":"dev-pin","deviceName":"Pixel 9","fileClass":"backup","shard":0,"writtenAt":1795400000000,"appVersionCode":28,"records":[
{"k":"account","kv":1,"s":"","id":"s-alice","ts":1700000000000,"d":"dev-pin","v":{"provider":"subsonic","displayName":"Alice @ Home","createdAt":1,"hint":"alice @ music.example.com","storefront":null,"deviceName":"Pixel 9"}},
{"k":"account","kv":1,"s":"","id":"s-cloud","ts":1700000000000,"d":"peer","v":{"provider":"spotify","displayName":"Carol","createdAt":2,"deviceName":"Tablet"}},
{"k":"album_note","kv":1,"s":"local-p2","id":"an9","ts":1700000500000,"d":"dev-pin","v":{"albumId":"1440","provider":"applemusic","content":"apple album note","createdAt":1700000500000,"updatedAt":1700000500000,"albumName":"Apple Album","artist":"Apple Artist"}},
{"k":"album_note","kv":1,"s":"s-alice","id":"an1","ts":1700000400000,"d":"dev-pin","v":{"albumId":"al2","provider":"subsonic","content":"  album note\nline two  ","createdAt":1700000400000,"updatedAt":1700000400000,"albumName":"Second Album","artist":"Band"}},
{"k":"album_note","kv":1,"s":"s-alice","id":"an2","ts":1700000300000,"d":"dev-pin","v":{"albumId":"al2","provider":"subsonic","content":"earlier album note","createdAt":1689000000000,"updatedAt":1700000300000,"albumName":"Ignored Name","artist":"Ignored"}},
{"k":"album_rating","kv":1,"s":"s-alice","id":"subsonic\u001fal1","ts":1700000200000,"d":"dev-pin","v":{"rating":8.0}},
{"k":"album_rating","kv":1,"s":"s-alice","id":"subsonic\u001fal2","ts":1700000200000,"d":"dev-pin","v":{"rating":6.5}},
{"k":"album_rating","kv":1,"s":"s-alice","id":"subsonic\u001fal3","ts":1700000200000,"d":"dev-pin","v":{"rating":0}},
{"k":"album_review","kv":1,"s":"s-alice","id":"subsonic\u001fal1","ts":1700003600000,"d":"dev-pin","v":{"review":"A long review.\nSecond line.\n"}},
{"k":"album_title","kv":1,"s":"s-alice","id":"subsonic\u001fal1","ts":1700000250000,"d":"dev-pin","v":{"title":"My Rainy Album"}},
{"k":"future_kind","kv":3,"s":"s-alice","id":"f1","ts":1700000000000,"d":"peer","v":{"x":1,"nested":{"keep":true}}},
{"k":"memory_title","kv":1,"s":"s-alice","id":"subsonic\u001falbum\u001fal1","ts":1700000260000,"d":"dev-pin","v":{"title":"AI Title","titlePromptHash":"th"}},
{"k":"memory_title","kv":1,"s":"s-alice","id":"subsonic\u001ftrack\u001ft1","ts":1700000260000,"d":"dev-pin","v":{"title":"Track AI Title"}},
{"k":"playlist_backup","kv":1,"s":"","id":"pb1","ts":1700000000000,"d":"dev-pin","v":{"name":"Road Trip","sourceProvider":"subsonic","sourceProfileLabel":"Alice @ Home","lastBackedUpAt":1700007200000,"content":"{\"description\":\"  For the drive.  \",\"tracks\":[{\"title\":\"Song One\",\"artists\":[\"Artist A\",\"Guest\"],\"sourceId\":\"t1\"},{\"title\":\"Instrumental\",\"sourceId\":\"t7\"}]}"}},
{"k":"playlist_backup","kv":1,"s":"","id":"pb2","ts":1700000000000,"d":"dev-pin","v":{"name":"broken","sourceProvider":"spotify","sourceProfileLabel":"Carol","content":"not json"}},
{"k":"setting","kv":1,"s":"","id":"translation_language","ts":1700000000000,"d":"dev-pin","v":{"value":"Japanese"}},
{"k":"song_note","kv":1,"s":"s-alice","id":"n1","ts":1700000000000,"d":"dev-pin","v":{"trackId":"t1","provider":"subsonic","content":"first listen\nrain outside","createdAt":1700000000000,"updatedAt":1700000000000,"title":"Song One","artist":"Artist A","positionMs":61000}},
{"k":"song_note","kv":1,"s":"s-alice","id":"n2","ts":1700000100000,"d":"dev-pin","v":{"trackId":"t2","provider":"subsonic","content":"  second  ","createdAt":1700000100000,"updatedAt":1700000100000,"title":"","artist":"","positionMs":null}},
{"k":"song_note","kv":1,"s":"s-alice","id":"n3","ts":1700000150000,"d":"dev-pin","v":{"trackId":"t9","provider":"subsonic","content":"where is this from","createdAt":1700000150000,"updatedAt":1700000150000,"title":"","artist":"","positionMs":3723000}},
{"k":"song_note","kv":1,"s":"s-alice","id":"n4","ts":1700000900000,"d":"dev-pin","pts":1700000000000,"pd":"dev-pin","del":true},
{"k":"song_note","kv":1,"s":"s-cloud","id":"c1","ts":1700000000000,"d":"peer","v":{"trackId":"sp1","provider":"spotify","content":"cloud note","createdAt":1700000000000,"updatedAt":1700000000000,"title":"Cloud Song","artist":"Cloud Artist","positionMs":null}},
{"k":"track_album_link","kv":1,"s":"s-alice","id":"subsonic\u001ft1","ts":1700000000000,"d":"dev-pin","v":{"provider":"subsonic","trackId":"t1","albumId":"al1","source":"detail","updatedAt":1700000000000}},
{"k":"track_rating","kv":1,"s":"local-p2","id":"applemusic\u001f999","ts":1700000000000,"d":"dev-pin","v":{"provider":"applemusic","songId":"999","rating":10.0}},
{"k":"track_rating","kv":1,"s":"s-alice","id":"subsonic\u001ft1","ts":1700000000000,"d":"dev-pin","v":{"provider":"subsonic","songId":"t1","rating":9.0}},
{"k":"track_rating","kv":1,"s":"s-alice","id":"subsonic\u001ft2","ts":1700000000000,"d":"dev-pin","v":{"provider":"subsonic","songId":"t2","rating":7.5}},
{"k":"track_rating","kv":1,"s":"s-alice","id":"subsonic\u001ft3","ts":1700000000000,"d":"dev-pin","v":{"provider":"subsonic","songId":"t3","rating":0,"zero":true}},
{"k":"track_rating","kv":1,"s":"s-alice","id":"subsonic\u001ft4","ts":1700000000000,"d":"dev-pin","v":{"provider":"subsonic","songId":"t4","rating":0}},
{"k":"track_rating","kv":1,"s":"s-alice","id":"subsonic\u001ft5","ts":1700000000000,"d":"dev-pin","v":{"provider":"subsonic","songId":"t5","rating":7.25}}
],"purges":{"s-gone":1700000000000}}"""

    val accounts = listOf(
        BackupAccount(scope = "s-alice", provider = "subsonic", displayName = "Alice @ Home", hint = "alice @ music.example.com", onDevice = true),
        BackupAccount(scope = "local-p2", provider = "applemusic", displayName = "Alice @ Home", storefront = "cn", onDevice = true),
        BackupAccount(scope = "local-p3", provider = "subsonic", displayName = "  .Bad/Name: *?\"<>| ", onDevice = true),
        BackupAccount(scope = "s-cloud", provider = "spotify", displayName = "Carol", onDevice = false),
    )

    val localProfiles = mapOf("s-alice" to "p1", "local-p2" to "p2", "local-p3" to "p3")

    val source = BackupSource(BackupFormat.LOCATION_GOOGLE_DRIVE, "dev-pin", "Pixel 9", "0.6.3", 28)
}

/** Records an import golden backup carries besides a phone's export ("SCOPE" = alice's scope). */
internal object ImportExtras {
    const val RECORDS = """{"deviceId":"dev-phone","deviceName":"Pixel 9","fileClass":"backup","writtenAt":1,"appVersionCode":28,"records":[
{"k":"account","kv":1,"s":"","id":"s-cloud","ts":1000,"d":"peer","v":{"provider":"spotify","displayName":"Carol","createdAt":2,"deviceName":"Tablet"}},
{"k":"future_kind","kv":1,"s":"SCOPE","id":"f1","ts":2000,"d":"peer","v":{"x":1}},
{"k":"setting","kv":1,"s":"","id":"translation_language","ts":2000,"d":"dev-phone","v":{"value":"Japanese"}},
{"k":"song_note","kv":1,"s":"SCOPE","id":"n3","ts":2900,"d":"dev-phone","pts":2000,"pd":"dev-phone","del":true},
{"k":"song_note","kv":9,"s":"SCOPE","id":"n4","ts":2000,"d":"peer","v":{"trackId":"t4","provider":"subsonic","content":"from the future","createdAt":2000,"updatedAt":2000,"title":"F","artist":"A","positionMs":null}},
{"k":"song_note","kv":1,"s":"s-cloud","id":"c1","ts":1500,"d":"peer","v":{"trackId":"sp1","provider":"spotify","content":"cloud note","createdAt":1500,"updatedAt":1500,"title":"Cloud Song","artist":"Cloud Artist","positionMs":null}},
{"k":"song_note","kv":1,"s":"s-gone","id":"g1","ts":1500,"d":"peer","v":{"trackId":"sp9","provider":"spotify","content":"gone note","createdAt":1500,"updatedAt":1500,"title":"Gone","artist":"Gone","positionMs":null}}
]}"""
}
