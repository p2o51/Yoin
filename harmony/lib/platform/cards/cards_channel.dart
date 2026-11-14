/// Yoin's HarmonyOS service cards over the `yoin/cards` method channel
/// (ohos/entry/src/main/ets/yoin/cards/CardsPlugin.ets). Dart publishes what
/// the Play card shows; the host calls back when a card is tapped.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// One item of the Play card (Android widget/WidgetData.kt PlayItem).
@immutable
final class PlayCardItem {
  const PlayCardItem({required this.entity, required this.id, required this.title, required this.subtitle, this.cover});

  /// `album` or `playlist` (the card's backdrop shape follows it: Bun / Ghostish).
  final String entity;

  /// The MediaId string (`provider:rawId`).
  final String id;
  final String title;
  final String subtitle;

  /// Encoded cover image (JPEG/PNG as served), or null when there is none.
  final Uint8List? cover;

  Map<String, Object?> toMap() => {
        'entity': entity,
        'id': id,
        'title': title,
        'subtitle': subtitle,
        'cover': cover,
      };
}

/// One memory on the Memory card (Android widget/WidgetData.kt MemoryItem),
/// already worded: the card shows these strings as they come.
@immutable
final class MemoryCardItem {
  const MemoryCardItem({
    required this.sessionId,
    required this.album,
    this.artist,
    this.score,
    this.lastHeard,
    this.cover,
  });

  /// The memory's stable id (what Memories opens on).
  final int sessionId;
  final String album;
  final String? artist;

  /// "8.5", or null when nothing on the album is rated.
  final String? score;

  /// "3 Days Ago" / "Today", or null when it was never played in Yoin.
  final String? lastHeard;
  final Uint8List? cover;

  Map<String, Object?> toMap() => {
        'session': '$sessionId',
        'album': album,
        'artist': artist ?? '',
        'score': score ?? '',
        'lastHeard': lastHeard ?? '',
        'cover': cover,
      };
}

/// What a card tap asks for.
sealed class CardAction {
  const CardAction();

  /// Parses the host's `action` call; null for anything unknown.
  static CardAction? fromMap(Map<Object?, Object?> map) {
    final entity = map['entity'];
    final id = map['id'];
    return switch (map['kind']) {
      'open' when entity is String && id is String => CardOpen(entity, id),
      'play' when entity is String && id is String => CardPlay(entity, id),
      'app' => const CardOpenApp(),
      'memory' when id is String => CardOpenMemory(int.tryParse(id)),
      _ => null,
    };
  }
}

/// Open an album / playlist page.
final class CardOpen extends CardAction {
  const CardOpen(this.entity, this.id);
  final String entity;
  final String id;
}

/// Play an album / playlist from its first track.
final class CardPlay extends CardAction {
  const CardPlay(this.entity, this.id);
  final String entity;
  final String id;
}

/// Open Memories on one memory (the Memory card); null = just open Memories.
final class CardOpenMemory extends CardAction {
  const CardOpenMemory(this.sessionId);
  final int? sessionId;
}

/// Just bring the app forward (the empty card).
final class CardOpenApp extends CardAction {
  const CardOpenApp();
}

/// The host side of the service cards.
abstract interface class CardsHost {
  /// Replaces the Play card's content. [glyphLight] / [glyphDark] are the play
  /// symbol rendered for each theme (the card cannot draw Yoin Symbols itself).
  Future<void> publishPlay(List<PlayCardItem> items, {required Uint8List glyphLight, required Uint8List glyphDark});

  /// Replaces the Memory card's content (newest heard first).
  Future<void> publishMemory(List<MemoryCardItem> items);

  /// Starts delivering card taps to [handler] (including one that cold-started the app).
  Future<void> listen(void Function(CardAction action) handler);
}

const _cardsChannel = MethodChannel('yoin/cards');

/// [CardsHost] over `yoin/cards`.
final class ChannelCardsHost implements CardsHost {
  const ChannelCardsHost({MethodChannel channel = _cardsChannel}) : _channel = channel;

  final MethodChannel _channel;

  @override
  Future<void> publishPlay(List<PlayCardItem> items, {required Uint8List glyphLight, required Uint8List glyphDark}) =>
      _channel.invokeMethod<void>('publishPlay', {
        'items': [for (final item in items) item.toMap()],
        'glyphLight': glyphLight,
        'glyphDark': glyphDark,
      });

  @override
  Future<void> publishMemory(List<MemoryCardItem> items) => _channel.invokeMethod<void>('publishMemory', {
        'items': [for (final item in items) item.toMap()],
      });

  @override
  Future<void> listen(void Function(CardAction action) handler) async {
    _channel.setMethodCallHandler((call) async {
      if (call.method != 'action') return null;
      final args = call.arguments;
      if (args is Map) {
        final action = CardAction.fromMap(args);
        if (action != null) handler(action);
      }
      return null;
    });
    await _channel.invokeMethod<void>('ready');
  }
}

/// Hosts without service cards (Windows has no home-screen cards): nothing is
/// published and no tap ever arrives. A Windows build could map the Play
/// card to a jump list / taskbar thumbnail later (docs/windows.md).
final class NoCardsHost implements CardsHost {
  const NoCardsHost();

  @override
  Future<void> publishPlay(List<PlayCardItem> items, {required Uint8List glyphLight, required Uint8List glyphDark}) async {}

  @override
  Future<void> publishMemory(List<MemoryCardItem> items) async {}

  @override
  Future<void> listen(void Function(CardAction action) handler) async {}
}

/// The platform's card host (main.dart overrides it with the platform bundle's).
final cardsHostProvider = Provider<CardsHost>((ref) => const ChannelCardsHost());
