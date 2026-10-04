package com.gpo.yoin.data.sync.adapters

import com.gpo.yoin.data.sync.CanonicalJson
import com.gpo.yoin.data.sync.SyncFormat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Projection helpers shared by the adapters. A projection is hashed with
 * CanonicalJson, so the same value must always be built with the same JSON
 * primitive type: strings as strings, timestamps as Long, ratings as Float,
 * absent optionals as JsonNull. Both readAll (domain -> projection) and
 * project (payload -> projection) go through these, never through ad-hoc
 * JsonPrimitive calls.
 */

/** Result of reading one optional payload field: present (maybe null) or malformed. */
internal sealed interface Field<out T> {
    data class Ok<T>(val value: T) : Field<T>

    data object Malformed : Field<Nothing>
}

/** The field's value, or [onMalformed] (typically a non-local `return null`). */
internal inline fun <T> Field<T>.orElse(onMalformed: () -> Nothing): T = when (this) {
    is Field.Ok -> value
    Field.Malformed -> onMalformed()
}

internal fun JsonObject.requiredString(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Missing or JSON null -> Ok(null); a non-string value -> Malformed. */
internal fun JsonObject.optionalString(name: String): Field<String?> = when (val element = this[name]) {
    null, JsonNull -> Field.Ok(null)
    is JsonPrimitive -> if (element.isString) Field.Ok(element.content) else Field.Malformed
    else -> Field.Malformed
}

internal fun JsonObject.requiredLong(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toLongOrNull()

/** Missing or JSON null -> Ok(null); anything but an integral number -> Malformed. */
internal fun JsonObject.optionalLong(name: String): Field<Long?> = when (val element = this[name]) {
    null, JsonNull -> Field.Ok(null)
    is JsonPrimitive ->
        if (element.isString) {
            Field.Malformed
        } else {
            element.content.toLongOrNull()?.let { Field.Ok(it) } ?: Field.Malformed
        }
    else -> Field.Malformed
}

/** A 0..10 rating. Accepts any JSON number spelling ("4", "4.0", "4e0") so peers' encoders never matter. */
internal fun JsonObject.rating(name: String): Float? = (this[name] as? JsonPrimitive)
    ?.takeIf { it !is JsonNull && !it.isString }
    ?.content
    ?.toFloatOrNull()
    ?.takeIf { it.isFinite() && it in 0f..10f }

internal fun jsonString(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

internal fun jsonLong(value: Long?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

/** Ratings are always projected as Float so "7" from a peer and 7.0f from Room hash alike. */
internal fun jsonRating(value: Float): JsonElement = JsonPrimitive(value)

internal fun JsonObject?.hashOrNull(): String? = this?.let(CanonicalJson::hash)

/** [SyncFormat.compositeKey] parts, or null when [key] doesn't have exactly [parts] parts. */
internal fun splitKey(key: String, parts: Int): List<String>? =
    key.split(SyncFormat.KEY_SEPARATOR).takeIf { it.size == parts }

/** [payload] with [field] replaced, every other (possibly unknown) key preserved. */
internal fun JsonObject.with(field: String, value: JsonElement): JsonObject = JsonObject(this + (field to value))
