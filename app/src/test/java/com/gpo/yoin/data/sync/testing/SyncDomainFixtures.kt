package com.gpo.yoin.data.sync.testing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.YoinDatabase
import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.SyncDatabase
import com.gpo.yoin.data.sync.adapters.SeamStyleGateway
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** In-memory domain + sync databases for Robolectric tests. */
class SyncDomainFixtures {
    val db: YoinDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        YoinDatabase::class.java,
    ).allowMainThreadQueries().build()

    val syncDb: SyncDatabase = SyncDatabase.inMemory(ApplicationProvider.getApplicationContext())

    fun close() {
        db.close()
        syncDb.close()
    }
}

/** Parses a JSON object literal, the way a payload arrives from another device. */
fun payload(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

fun hashOf(obj: JsonObject?): String? = obj?.let(CanonicalJson::hash)

class FakeSeamStyleGateway(var stored: String? = null) : SeamStyleGateway {
    val applied = mutableListOf<String>()

    override fun storedKey(): String? = stored

    override fun apply(key: String): Boolean {
        applied += key
        stored = key
        return true
    }
}
