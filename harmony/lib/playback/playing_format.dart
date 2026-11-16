/// What actually plays, for Now Playing's format tags (Android
/// PlaybackService.playingFormat + SubsonicStreamResolver.deliveryFor):
/// each queue entry's delivery (the stream's transcode, or the quality its
/// download was kept at) and what the native player decodes.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/model/model.dart';
import '../platform/playback/playback_engine.dart';

/// One entry's two sources: the stream ([stream], null = not known) and the
/// downloaded file ([file], null = no download).
@immutable
final class EntryDelivery {
  const EntryDelivery({this.stream, this.file});

  final StreamDelivery? stream;
  final StreamDelivery? file;

  /// What arrives: the file's when the player opened it, the stream's when it
  /// streamed. Before the player says ([fromFile] null), the download wins:
  /// the native player opens it whenever it is there.
  StreamDelivery? playing({bool? fromFile}) => switch (fromFile) {
    true => file ?? stream,
    false => stream,
    null => file ?? stream,
  };

  @override
  bool operator ==(Object other) => other is EntryDelivery && other.stream == stream && other.file == file;

  @override
  int get hashCode => Object.hash(stream, file);
}

/// Entry id → its deliveries, for the queue the controller last handed the
/// engine (written as items are built, and again from a rehydrated queue).
class EntryDeliveries extends Notifier<Map<String, EntryDelivery>> {
  @override
  Map<String, EntryDelivery> build() => const {};

  /// A new queue: only [items] count.
  void replace(Iterable<EngineItem> items) => state = _of(items);

  /// Entries added to the queue.
  void add(Iterable<EngineItem> items) => state = {...state, ..._of(items)};

  static Map<String, EntryDelivery> _of(Iterable<EngineItem> items) => {
    for (final item in items) ?item.entryId: EntryDelivery(stream: item.delivery, file: item.fileDelivery),
  };
}

final entryDeliveriesProvider = NotifierProvider<EntryDeliveries, Map<String, EntryDelivery>>(EntryDeliveries.new);

/// The native player's last [PlayingFormatEvent]; Now Playing reads it only
/// while it is about the entry playing.
class PlayingFormatState extends Notifier<PlayingFormatEvent?> {
  @override
  PlayingFormatEvent? build() => null;

  void report(PlayingFormatEvent event) {
    if (event != state) state = event;
  }
}

final playingFormatProvider = NotifierProvider<PlayingFormatState, PlayingFormatEvent?>(PlayingFormatState.new);
