/// Projection helpers shared by the adapters (Android adapters/SyncProjections.kt).
/// A projection is hashed with CanonicalJson, so the same value must always
/// be built with the same JSON type: strings as strings, timestamps as int,
/// ratings as double (so `7` from a peer and a stored 7.0 hash alike), absent
/// optionals as null.
library;

import '../canonical_json.dart';
import '../sync_contracts.dart';

/// Reading one optional payload field: present (maybe null) or malformed.
sealed class Field<T> {
  const Field();
}

final class FieldOk<T> extends Field<T> {
  const FieldOk(this.value);
  final T value;
}

final class FieldMalformed<T> extends Field<T> {
  const FieldMalformed();
}

String? requiredString(JsonMap json, String name) {
  final v = json[name];
  return v is String ? v : null;
}

/// Missing or JSON null -> Ok(null); a non-string value -> Malformed.
Field<String?> optionalString(JsonMap json, String name) {
  final v = json[name];
  if (v == null) return const FieldOk(null);
  return v is String ? FieldOk(v) : const FieldMalformed();
}

/// An integral JSON number (Kotlin's content.toLongOrNull: "5" yes, "5.0" no).
int? requiredLong(JsonMap json, String name) {
  final v = json[name];
  return v is int ? v : null;
}

/// Missing or JSON null -> Ok(null); anything but an integral number -> Malformed.
Field<int?> optionalLong(JsonMap json, String name) {
  final v = json[name];
  if (v == null) return const FieldOk(null);
  return v is int ? FieldOk(v) : const FieldMalformed();
}

/// A 0..10 rating; any JSON number spelling.
double? rating(JsonMap json, String name) {
  final v = json[name];
  if (v is! num) return null;
  final d = v.toDouble();
  return d.isFinite && d >= 0 && d <= 10 ? d : null;
}

/// Ratings are always projected as doubles.
double jsonRating(double value) => value.toDouble();

String? hashOrNull(JsonMap? projection) => projection == null ? null : CanonicalJson.hash(projection);

/// [SyncFormat.compositeKey] parts, or null when [key] doesn't have exactly [parts] parts.
List<String>? splitKey(String key, int parts) {
  final split = key.split(SyncFormat.keySeparator);
  return split.length == parts ? split : null;
}

/// [payload] with [field] replaced, every other (possibly unknown) key preserved.
JsonMap withField(JsonMap payload, String field, Object? value) => {...payload, field: value};
