package com.gpo.yoin.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaIdTest {
    @Test fun should_keepAppleLibraryPrefix_when_storedIdIsBareLibraryRawId() {
        assertEquals(
            "library:l.s4VMDc6",
            MediaId.storedRawId(MediaId.PROVIDER_APPLE_MUSIC, "library:l.s4VMDc6")
        )
    }

    @Test fun should_stripOwnProviderPrefix_when_storedIdIsLegacyMediaIdString() {
        assertEquals("5z3r0mH5", MediaId.storedRawId(MediaId.PROVIDER_SPOTIFY, "spotify:5z3r0mH5"))
        assertEquals(
            "library:l.abc",
            MediaId.storedRawId(MediaId.PROVIDER_APPLE_MUSIC, "applemusic:library:l.abc")
        )
    }

    @Test fun should_returnInput_when_storedIdHasNoPrefix() {
        assertEquals("al-42", MediaId.storedRawId(MediaId.PROVIDER_SUBSONIC, "al-42"))
    }
}
