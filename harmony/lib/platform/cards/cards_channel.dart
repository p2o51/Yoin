/// Yoin's HarmonyOS service cards over the `yoin/cards` method channel
/// (ohos/entry/src/main/ets/yoin/cards/CardsPlugin.ets). Dart publishes what
/// the Play card shows; the host calls back when a card is tapped.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

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

/// Just bring the app forward (the empty card).
final class CardOpenApp extends CardAction {
  const CardOpenApp();
}

/// The host side of the service cards.
abstract interface class CardsHost {
  /// Replaces the Play card's content. [glyphLight] / [glyphDark] are the play
  /// symbol rendered for each theme (the card cannot draw Yoin Symbols itself).
  Future<void> publishPlay(List<PlayCardItem> items, {required Uint8List glyphLight, required Uint8List glyphDark});

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
