import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/artwork_image.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'continue_on_controller.dart';
import 'continue_on_cursor.dart';
import 'continue_on_strings.dart';

/// The small card a Continue On hand-off shows before anything plays here
/// (Android ui/continueon/ContinueOnCard.kt, owner 2026-11-08): the song,
/// where the other device is in it, and "Continue here". Only that tap starts
/// it and pauses the other device. Sits over the whole window (main.dart),
/// Now Playing and the first-run landing included, clear of the navigation bar.
class ContinueOnCardHost extends ConsumerWidget {
  const ContinueOnCardHost({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final prompt = ref.watch(continueOnProvider);
    final controller = ref.read(continueOnProvider.notifier);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.paddingOf(context).bottom),
      child: ContinueOnCard(prompt: prompt, onContinue: controller.confirm, onDismiss: controller.dismiss),
    );
  }
}

/// The card for [prompt] at the bottom centre; null = none. The last card
/// stays drawn while it leaves. It rises in on the Expressive spatial spring
/// (half its height, scale 0.92 → 1, fade in) and sinks out on the Standard
/// one (half its height, fade out).
class ContinueOnCard extends StatefulWidget {
  const ContinueOnCard({
    super.key,
    required this.prompt,
    required this.onContinue,
    required this.onDismiss,
    this.continueKey,
  });

  final ContinueOnPrompt? prompt;
  final VoidCallback onContinue;
  final VoidCallback onDismiss;

  /// On "Continue here" (a demo aims its finger at it).
  final Key? continueKey;

  @override
  State<ContinueOnCard> createState() => _ContinueOnCardState();
}

class _ContinueOnCardState extends State<ContinueOnCard> with TickerProviderStateMixin {
  late final SpringValue _move = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);
  late final SpringValue _alpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  ContinueOnPrompt? _shown;
  bool _leaving = false;

  @override
  void initState() {
    super.initState();
    _shown = widget.prompt;
    _alpha.animation.addStatusListener(_onAlphaStatus);
    if (_shown != null) WidgetsBinding.instance.addPostFrameCallback((_) => _enter());
  }

  @override
  void didUpdateWidget(ContinueOnCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    final prompt = widget.prompt;
    if (prompt != null) {
      final wasAway = oldWidget.prompt == null;
      _shown = prompt;
      if (wasAway) _enter();
    } else if (oldWidget.prompt != null) {
      _leave();
    }
  }

  bool get _reduce => MediaQuery.maybeDisableAnimationsOf(context) ?? false;

  void _enter() {
    if (!mounted) return;
    _leaving = false;
    _move.spring = YoinSprings.defaultSpatial;
    _reduce ? _move.snapTo(1) : _move.animateTo(1);
    _alpha.animateTo(1);
  }

  void _leave() {
    _leaving = true;
    _move.spring = YoinSprings.standardDefaultSpatial;
    _reduce ? _move.snapTo(0) : _move.animateTo(0);
    _alpha.animateTo(0);
  }

  /// The fade-out settled: the card is gone.
  void _onAlphaStatus(AnimationStatus status) {
    if (status != AnimationStatus.completed || !_leaving || _alpha.value > 0.01 || !mounted) return;
    setState(() => _shown = null);
  }

  @override
  void dispose() {
    _alpha.animation.removeStatusListener(_onAlphaStatus);
    _move.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final shown = _shown;
    if (shown == null) return const SizedBox.shrink();
    return Align(
      alignment: Alignment.bottomCenter,
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: ContinueOnCardTokens.outerHorizontal,
          vertical: ContinueOnCardTokens.outerVertical,
        ),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: ContinueOnCardTokens.maxWidth),
          child: AnimatedBuilder(
            animation: Listenable.merge([_move.animation, _alpha.animation]),
            builder: (context, child) {
              final m = _move.value;
              final scale = _leaving
                  ? 1.0
                  : ContinueOnCardTokens.enterScale + (1 - ContinueOnCardTokens.enterScale) * m;
              return IgnorePointer(
                ignoring: _leaving,
                child: FractionalTranslation(
                  translation: Offset(0, (1 - m) / 2),
                  child: Transform.scale(
                    scale: scale,
                    child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
                  ),
                ),
              );
            },
            child: Semantics(
              liveRegion: true,
              container: true,
              child: _CardBody(
                prompt: shown,
                onContinue: widget.onContinue,
                onDismiss: widget.onDismiss,
                continueKey: widget.continueKey,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _CardBody extends StatelessWidget {
  const _CardBody({required this.prompt, required this.onContinue, required this.onDismiss, this.continueKey});

  final ContinueOnPrompt prompt;
  final VoidCallback onContinue;
  final VoidCallback onDismiss;
  final Key? continueKey;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final text = theme.textTheme;
    final strings = ContinueOnStrings.of(context);
    final artist = prompt.artist?.trim().isNotEmpty ?? false ? prompt.artist! : null;
    final album = prompt.album?.trim().isNotEmpty ?? false ? prompt.album! : null;
    return Material(
      color: scheme.surfaceContainerHigh,
      surfaceTintColor: Colors.transparent,
      elevation: ContinueOnCardTokens.elevation,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(ContinueOnCardTokens.corner))),
      child: Padding(
        padding: const EdgeInsets.all(ContinueOnCardTokens.padding),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                _Cover(url: prompt.coverUrl),
                const SizedBox(width: ContinueOnCardTokens.coverGap),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        strings.from(prompt.deviceName ?? strings.otherDevice),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: text.labelMedium!.copyWith(color: scheme.primary),
                      ),
                      Text(
                        prompt.title ?? strings.unknownSong,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: text.titleMedium!.copyWith(color: scheme.onSurface),
                      ),
                      // As a song row has them: the artist, then the album muted, no glyph between.
                      if (artist != null || album != null)
                        MetaLine(
                          groups: [
                            ?(artist == null ? null : MetaPlain(artist)),
                            ?(album == null ? null : MetaMuted(album)),
                          ],
                          style: text.bodyMedium,
                        ),
                      MetaLine(
                        groups: [
                          MetaKind(prompt.playing ? strings.playing : strings.paused),
                          MetaStat(formatContinueOnPosition(prompt.positionMs), ''),
                        ],
                        style: text.labelMedium,
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: ContinueOnCardTokens.sectionGap),
            SpringCrossFade(
              childKey: (prompt.stage, prompt.account.runtimeType),
              fit: StackFit.loose,
              child: _Actions(
                prompt: prompt,
                continueKey: continueKey,
                onContinue: () {
                  YoinHaptics.confirm();
                  onContinue();
                },
                onDismiss: () {
                  YoinHaptics.click();
                  onDismiss();
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Actions extends StatelessWidget {
  const _Actions({required this.prompt, required this.onContinue, required this.onDismiss, this.continueKey});

  final ContinueOnPrompt prompt;
  final VoidCallback onContinue;
  final VoidCallback onDismiss;
  final Key? continueKey;

  @override
  Widget build(BuildContext context) {
    final strings = ContinueOnStrings.of(context);
    final theme = Theme.of(context);
    final stage = prompt.stage;
    final account = prompt.account;
    final note = switch (stage) {
      ContinueOnStage.otherStillPlaying => strings.otherStillPlaying,
      ContinueOnStage.failed => strings.failed,
      _ when account is ContinueOnAccountMissing => strings.noAccount,
      _ => null,
    };
    final asking = stage == ContinueOnStage.asking && account is! ContinueOnAccountMissing;
    final List<Widget> buttons = switch (stage) {
      ContinueOnStage.starting => [const YoinLoadingIndicator(size: ContinueOnCardTokens.loading)],
      _ when asking => [
        TextButton(onPressed: onDismiss, child: Text(strings.notNow)),
        FilledButton(
          key: continueKey,
          onPressed: account is ContinueOnAccountFound ? onContinue : null,
          child: Text(strings.continueHere),
        ),
      ],
      _ => [TextButton(onPressed: onDismiss, child: Text(strings.ok))],
    };
    return SizedBox(
      width: double.infinity,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (note != null) ...[
            Text(note, style: theme.textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            const SizedBox(height: ContinueOnCardTokens.actionGap),
          ],
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              for (final (i, b) in buttons.indexed) ...[
                if (i > 0) const SizedBox(width: ContinueOnCardTokens.actionGap),
                b,
              ],
            ],
          ),
        ],
      ),
    );
  }
}

class _Cover extends StatelessWidget {
  const _Cover({required this.url});

  final String? url;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final px = (ContinueOnCardTokens.cover * MediaQuery.devicePixelRatioOf(context)).round();
    final image = url;
    return SizedBox.square(
      dimension: ContinueOnCardTokens.cover,
      child: ClipRSuperellipse(
        borderRadius: BorderRadius.circular(ShapeTokens.thumb),
        child: ColoredBox(
          color: scheme.surfaceContainerHighest,
          child: Stack(
            fit: StackFit.expand,
            children: [
              Center(child: Icon(YoinSymbols.album, color: scheme.onSurfaceVariant)),
              if (image != null)
                Image(
                  image: artworkImageProvider(image, cacheWidth: px),
                  fit: BoxFit.cover,
                  errorBuilder: (_, _, _) => const SizedBox.shrink(),
                  frameBuilder: (context, child, frame, sync) =>
                      sync || frame != null ? child : const SizedBox.shrink(),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// `m:ss`, or `h:mm:ss` from an hour on.
String formatContinueOnPosition(int positionMs) {
  final seconds = positionMs < 0 ? 0 : positionMs ~/ 1000;
  final hours = seconds ~/ 3600;
  final minutes = (seconds % 3600) ~/ 60;
  final secs = (seconds % 60).toString().padLeft(2, '0');
  return hours > 0 ? '$hours:${minutes.toString().padLeft(2, '0')}:$secs' : '$minutes:$secs';
}

/// A card for a hand-off that never happened (tests, a future Tips demo).
ContinueOnPrompt sampleContinueOnPrompt({
  required String title,
  required String artist,
  required String album,
  String? coverUrl,
  ContinueOnStage stage = ContinueOnStage.asking,
  ContinueOnAccount account = const ContinueOnAccountFound('sample', 'Home server'),
}) => ContinueOnPrompt(
  cursor: ContinueOnCursor(
    provider: 'subsonic',
    accountKey: null,
    trackId: 'sample',
    title: title,
    artist: artist,
    album: album,
    cover: null,
    queueIds: const ['sample'],
    index: 0,
    positionMs: 83000,
    capturedAtMs: 0,
    playing: true,
    repeatMode: 0,
    shuffle: false,
    context: null,
    deviceName: 'HUAWEI Mate 70 Air',
    pause: null,
  ),
  account: account,
  coverUrl: coverUrl,
  stage: stage,
);
