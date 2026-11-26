import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/ai/ai_service_name.dart';
import '../../data/ai/ai_settings.dart';

/// The AI service in use, for copy that names it ("Ask DeepSeek", "千问正在写…"): provided once for the
/// window by [ActiveAiServiceScope] (Android LocalAiProvider, provided by YoinActivityRoot). Gemini where
/// no scope is above (previews and tests), as on Android.
class AiServiceScope extends InheritedWidget {
  const AiServiceScope({super.key, required this.provider, required super.child});

  final AiProvider provider;

  static AiProvider of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<AiServiceScope>()?.provider ?? AiProvider.gemini;

  /// [of] without depending on the scope: for a callback (a snackbar after an AI call).
  static AiProvider read(BuildContext context) =>
      context.getInheritedWidgetOfExactType<AiServiceScope>()?.provider ?? AiProvider.gemini;

  @override
  bool updateShouldNotify(AiServiceScope oldWidget) => oldWidget.provider != provider;
}

/// [AiServiceScope] following Settings › AI features.
class ActiveAiServiceScope extends ConsumerWidget {
  const ActiveAiServiceScope({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) =>
      AiServiceScope(provider: ref.watch(activeAiProviderProvider), child: child);
}
