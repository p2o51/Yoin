package com.gpo.yoin.shared.home

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One persisted section choice: its stable [id] and whether it renders. Position
 * in the persisted list is the render order. A plain id string, not a
 * [HomeSection], so ids this build doesn't know ride along ([HomeLayout.retained]).
 *
 * [config] is the section's settings bag (`{"rows":"xl","span":"half","units":2}`):
 * absent for a section left at its defaults — never written as `"config":null` —
 * and opaque to the codec, so keys a newer build adds ride along verbatim.
 * [extras] are the entry's other keys (a newer build's), kept verbatim too.
 */
data class HomeSectionPref(
    val id: String,
    val enabled: Boolean,
    val config: JsonObject? = null,
    val extras: JsonObject? = null,
)

/**
 * The whole stored layout: [sections] (as [HomeSectionPref]s), [wide], the
 * two-column memory of which sections pair up (`{"pairs":[["left","right"], …]}`,
 * owner 2026-11-23), opaque here like a section's config, and [extras], the
 * document's other keys (a newer build's), kept verbatim. Null [wide]: written
 * by a build without it (older Android, and iOS / HarmonyOS before parity p4,
 * which drop or carry it opaquely), so [HomeLayout.reconcile] derives it again.
 */
data class HomeLayoutDocument(
    val sections: List<HomeSectionPref>,
    val wide: JsonObject? = null,
    val extras: JsonObject? = null,
)

/**
 * The stored layout document (Android's `home_layout.sectionsJson`, iOS's and HarmonyOS's per-account
 * document, synced as an opaque string):
 * `{"version":1,"sections":[{"id":"activities","enabled":true,"config":{…}}, …],"wide":{"pairs":[…]}}`.
 *
 * Read entry by entry: a document that isn't an object with a `sections` array (or whose `version` isn't
 * an integer) is unreadable — "never customized" — while a malformed entry (no string `id`, no boolean
 * `enabled`, a `config` that isn't an object or null) drops only itself. A `wide` that isn't an object reads
 * as none. Every key this build doesn't read — the document's and each entry's — is kept and written back
 * (design.md: a newer build's data survives an older build's write). Written compact, keys in the order
 * `version`, `sections`, `wide`, then the kept ones; an entry `id`, `enabled`, `config`, then its kept ones.
 * `config` is omitted when absent, never null; `wide` is omitted when [HomeLayoutDocument.wide] is null
 * (a layout's own document always has one, [HomeLayout.toDocument]). Version stays 1: every addition is
 * optional and additive.
 */
object HomeLayoutCodec {

    const val SCHEMA_VERSION: Int = 1

    /** The document keys the codec reads; every other one is kept in [HomeLayoutDocument.extras]. */
    val DocumentKeys: Set<String> = setOf("version", "sections", "wide")

    /** The entry keys the codec reads; every other one is kept in [HomeSectionPref.extras]. */
    val EntryKeys: Set<String> = setOf("id", "enabled", "config")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** [raw] read, or null when it is unreadable (treated as "never set"). */
    fun decode(raw: String): HomeLayoutDocument? {
        val dto = runCatching { json.decodeFromString(DocumentDto.serializer(), raw) }.getOrNull() ?: return null
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        return HomeLayoutDocument(
            sections = dto.sections.mapNotNull(::decodeEntry),
            wide = dto.wide as? JsonObject,
            extras = root.without(DocumentKeys),
        )
    }

    /** One entry read; null when it is malformed (it drops alone). */
    fun decodeEntry(entry: JsonElement): HomeSectionPref? {
        val dto = runCatching { json.decodeFromJsonElement(EntryDto.serializer(), entry) }.getOrNull() ?: return null
        return HomeSectionPref(
            id = dto.id,
            enabled = dto.enabled,
            config = dto.config,
            extras = (entry as? JsonObject)?.without(EntryKeys),
        )
    }

    /** [document] as the stored text. */
    fun encode(document: HomeLayoutDocument): String =
        json.encodeToString(JsonElement.serializer(), encodeElement(document))

    /** [document] as a JSON object (what [encode] writes). */
    fun encodeElement(document: HomeLayoutDocument): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["version"] = JsonPrimitive(SCHEMA_VERSION)
        out["sections"] = JsonArray(document.sections.map(::encodeEntry))
        document.wide?.let { out["wide"] = it }
        document.extras?.forEach { (key, value) -> if (key !in DocumentKeys) out[key] = value }
        return JsonObject(out)
    }

    /** One entry as written. */
    fun encodeEntry(pref: HomeSectionPref): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(pref.id)
        out["enabled"] = JsonPrimitive(pref.enabled)
        pref.config?.let { out["config"] = it }
        pref.extras?.forEach { (key, value) -> if (key !in EntryKeys) out[key] = value }
        return JsonObject(out)
    }

    private fun JsonObject.without(keys: Set<String>): JsonObject? =
        filterKeys { it !in keys }.takeIf { it.isNotEmpty() }?.let(::JsonObject)

    // The reading rules of Android's store before :shared (kotlinx, ignoreUnknownKeys), kept exactly.
    @Serializable
    private data class DocumentDto(
        // Absent in pre-versioned rows → 1.
        val version: Int = SCHEMA_VERSION,
        // Raw entries, decoded one by one.
        val sections: List<JsonElement>,
        val wide: JsonElement? = null,
    )

    @Serializable
    private data class EntryDto(
        val id: String,
        val enabled: Boolean,
        val config: JsonObject? = null,
    )
}
