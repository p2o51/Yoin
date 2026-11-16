/// Now Playing's About widgets (Android AboutReceipt.kt, AboutFullscreenPane.kt,
/// AboutEntrySheet.kt, component/SongInfoDisplay.kt and GeminiPrompts.kt):
/// the receipt of short facts with a dashed rule between lines, the
/// paragraphs under it, the compact carousel, the expanded pane, the
/// long-pressed row's sheet, the quick prompts and the thinking dots.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/about/song_about_store.dart';
import '../shell/bar_suppression.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'about_logic.dart';
import 'haptics.dart';
import 'nowplaying_strings.dart';

/// [text] with `**bold**` / `__bold__` runs set bold (markdownBoldAnnotatedString).
TextSpan markdownBoldSpan(String text, {TextStyle? style}) {
  final spans = <TextSpan>[];
  final plain = StringBuffer();
  void flush() {
    if (plain.isEmpty) return;
    spans.add(TextSpan(text: plain.toString()));
    plain.clear();
  }

  var i = 0;
  while (i < text.length) {
    final c = text[i];
    final marker = (c == '*' || c == '_') && i + 1 < text.length && text[i + 1] == c ? '$c$c' : null;
    if (marker == null) {
      plain.write(c);
      i++;
      continue;
    }
    final close = text.indexOf(marker, i + 2);
    if (close < 0) {
      plain.write(marker);
      i += 2;
      continue;
    }
    flush();
    spans.add(
      TextSpan(
        text: text.substring(i + 2, close),
        style: const TextStyle(fontWeight: FontWeight.w700),
      ),
    );
    i = close + 2;
  }
  flush();
  return TextSpan(style: style, children: spans);
}

/// The receipt's words from [NowPlayingStrings].
final class NowPlayingAboutLabels implements AboutLabels {
  const NowPlayingAboutLabels(this.s);
  final NowPlayingStrings s;

  @override
  String about(String entryKey) => s.aboutField(entryKey);
  @override
  String get quality => s.spineQuality;
  @override
  String get serverRating => s.spineServerRating;
  @override
  String get genre => s.spineGenre;
  @override
  String get year => s.spineYear;
  @override
  String get track => s.spineTrack;
  @override
  String discTrack(int disc, int track) => s.spineDiscTrack(disc, track);
  @override
  String kbps(int value) => s.spineKbps(value);
  @override
  String khz(String value) => s.spineKhz(value);
  @override
  String bit(int value) => s.spineBit(value);
  @override
  String get duration => s.aboutDuration;
  @override
  String get channels => s.aboutChannels;
  @override
  String channelCount(int count) => s.aboutChannelCount(count);
  @override
  String get added => s.aboutAdded;
}

/// Three dots blinking in turn while the AI writes (held still under reduced motion).
class ThinkingDots extends StatefulWidget {
  const ThinkingDots({super.key, required this.color});

  final Color color;

  @override
  State<ThinkingDots> createState() => _ThinkingDotsState();
}

class _ThinkingDotsState extends State<ThinkingDots> with SingleTickerProviderStateMixin {
  static const _period = NowPlayingAboutTokens.dotBlink;

  /// One loop = a blink out and back in; each dot runs [NowPlayingAboutTokens.dotStep] behind the one before.
  late final AnimationController _clock = AnimationController(vsync: this, duration: _period * 2);

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (MediaQuery.disableAnimationsOf(context)) {
      _clock.stop();
    } else if (!_clock.isAnimating) {
      _clock.repeat();
    }
  }

  @override
  void dispose() {
    _clock.dispose();
    super.dispose();
  }

  /// Dot [i]'s alpha at [ms] into the loop: 1 → dim → 1, [i] steps late.
  static double _alpha(int i, double ms) {
    final blink = _period.inMilliseconds.toDouble();
    final t = (ms - i * NowPlayingAboutTokens.dotStep.inMilliseconds) % (blink * 2);
    final phase = t / blink;
    final f = phase <= 1 ? phase : 2 - phase;
    return 1 - (1 - NowPlayingAboutTokens.dotDim) * Curves.easeInOut.transform(f.clamp(0.0, 1.0));
  }

  @override
  Widget build(BuildContext context) {
    final reduced = MediaQuery.disableAnimationsOf(context);
    return Semantics(
      label: NowPlayingStrings.of(context).cdGeminiThinking,
      child: AnimatedBuilder(
        animation: _clock,
        builder: (context, _) {
          final ms = _clock.value * _clock.duration!.inMilliseconds;
          return Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              for (var i = 0; i < 3; i++) ...[
                if (i > 0) const SizedBox(width: NowPlayingAboutTokens.dotGap),
                Opacity(
                  opacity: reduced ? NowPlayingAboutTokens.dotReduced : _alpha(i, ms),
                  child: DecoratedBox(
                    decoration: BoxDecoration(color: widget.color, shape: BoxShape.circle),
                    child: const SizedBox.square(dimension: NowPlayingAboutTokens.dot),
                  ),
                ),
              ],
            ],
          );
        },
      ),
    );
  }
}

/// The receipt's dashed rule between lines.
class DashedRule extends StatelessWidget {
  const DashedRule({super.key, this.color});

  final Color? color;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: NowPlayingAboutTokens.dashStroke,
    width: double.infinity,
    child: CustomPaint(painter: _DashPainter(color ?? Theme.of(context).colorScheme.outlineVariant)),
  );
}

class _DashPainter extends CustomPainter {
  _DashPainter(this.color);
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    const dash = NowPlayingAboutTokens.dash;
    const gap = dash * 0.75;
    final paint = Paint()
      ..color = color
      ..strokeWidth = size.height;
    final y = size.height / 2;
    for (var x = 0.0; x < size.width; x += dash + gap) {
      canvas.drawLine(Offset(x, y), Offset((x + dash).clamp(0, size.width), y), paint);
    }
  }

  @override
  bool shouldRepaint(_DashPainter old) => old.color != color;
}

/// A row that answers a long press (the AI's rows in the expanded pane).
class _LongPressable extends StatelessWidget {
  const _LongPressable({required this.onLongPress, required this.child});

  final VoidCallback? onLongPress;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final press = onLongPress;
    if (press == null) return child;
    return Semantics(
      onLongPressHint: NowPlayingStrings.of(context).aboutCdEntryMenu,
      onLongPress: press,
      // A right click is the long press on a desktop.
      child: GestureDetector(behavior: HitTestBehavior.opaque, onLongPress: press, onSecondaryTap: press, child: child),
    );
  }
}

class _ReceiptLine extends StatelessWidget {
  const _ReceiptLine({required this.label, required this.value, this.thinking = false, this.onLongPress});

  final String label;
  final String value;
  final bool thinking;
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return _LongPressable(
      onLongPress: onLongPress,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: NowPlayingAboutTokens.receiptLinePadV),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: NowPlayingAboutTokens.receiptLabelWidth,
              child: Padding(
                padding: const EdgeInsetsDirectional.only(
                  top: NowPlayingAboutTokens.receiptLabelTop,
                  end: NowPlayingAboutTokens.receiptLabelGap,
                ),
                child: Text(label, style: theme.textTheme.labelMedium?.copyWith(color: colors.onSurfaceVariant)),
              ),
            ),
            Expanded(
              child: thinking
                  ? Align(
                      alignment: AlignmentDirectional.centerEnd,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 6),
                        child: ThinkingDots(color: colors.primary),
                      ),
                    )
                  : Text.rich(
                      markdownBoldSpan(value),
                      textAlign: TextAlign.end,
                      style: theme.textTheme.bodyMedium?.copyWith(color: colors.onSurface),
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The receipt card ([rows] with a dashed rule between). [thinking] marks a
/// row the AI is writing again; [onLongPress] opens an AI row's sheet (null:
/// rows don't respond).
class AboutReceipt extends StatelessWidget {
  const AboutReceipt({super.key, required this.rows, this.thinking, this.onLongPress});

  final List<ReceiptRow> rows;
  final bool Function(SongAboutEntry entry)? thinking;
  final void Function(ReceiptRow row)? onLongPress;

  @override
  Widget build(BuildContext context) {
    if (rows.isEmpty) return const SizedBox.shrink();
    final colors = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: ShapeDecoration(
        color: colors.surfaceContainerLow,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(NowPlayingAboutTokens.receiptCorner)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: NowPlayingAboutTokens.receiptPadH,
          vertical: NowPlayingAboutTokens.receiptPadV,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final (i, row) in rows.indexed) ...[
              if (i > 0) const DashedRule(),
              _ReceiptLine(
                label: row.label,
                value: row.value,
                thinking: row.entry != null && (thinking?.call(row.entry!) ?? false),
                onLongPress: row.entry != null && onLongPress != null ? () => onLongPress!(row) : null,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// One paragraph: a small heading in the primary and its text.
class AboutParagraphSection extends StatelessWidget {
  const AboutParagraphSection({super.key, required this.paragraph, this.thinking = false, this.onLongPress});

  final AboutParagraph paragraph;
  final bool thinking;
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return _LongPressable(
      onLongPress: onLongPress,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: NowPlayingAboutTokens.paragraphPadV),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(paragraph.label, style: theme.textTheme.labelLarge?.copyWith(color: colors.primary)),
            if (thinking)
              Padding(
                padding: const EdgeInsets.only(top: 10, bottom: 6),
                child: Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: ThinkingDots(color: colors.primary),
                ),
              )
            else
              Padding(
                padding: const EdgeInsets.only(top: NowPlayingAboutTokens.paragraphGap),
                child: Text.rich(
                  markdownBoldSpan(paragraph.text),
                  style: theme.textTheme.bodyMedium?.copyWith(color: colors.onSurface),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// The collapsed About: [pages] one at a time, turning on their own every
/// seven seconds. No page indicator and no tap of its own: a tap falls
/// through to the pager page, which expands About. [songKey] starts it over.
class AboutCarousel extends StatefulWidget {
  const AboutCarousel({super.key, required this.pages, required this.songKey});

  final List<AboutCarouselPage> pages;
  final String songKey;

  @override
  State<AboutCarousel> createState() => _AboutCarouselState();
}

class _AboutCarouselState extends State<AboutCarousel> {
  int _index = 0;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _arm();
  }

  @override
  void didUpdateWidget(AboutCarousel old) {
    super.didUpdateWidget(old);
    if (old.songKey != widget.songKey) {
      _index = 0;
      _arm();
    } else if (old.pages.length != widget.pages.length) {
      _arm();
    }
  }

  void _arm() {
    _timer?.cancel();
    if (widget.pages.length < 2) return;
    _timer = Timer.periodic(NowPlayingAboutTokens.carouselDwell, (_) {
      if (mounted) setState(() => _index++);
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (widget.pages.isEmpty) return const SizedBox.shrink();
    final current = _index % widget.pages.length;
    final page = widget.pages[current];
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final Widget body = switch (page) {
      AboutCarouselLines(:final rows) => Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final (i, row) in rows.indexed) ...[
            if (i > 0) const DashedRule(),
            _ReceiptLine(label: row.label, value: row.value),
          ],
        ],
      ),
      AboutCarouselParagraph(:final paragraph) => Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(paragraph.label, style: theme.textTheme.labelLarge?.copyWith(color: colors.primary)),
          Padding(
            padding: const EdgeInsets.only(top: NowPlayingAboutTokens.paragraphGap),
            child: Text.rich(
              markdownBoldSpan(paragraph.text),
              maxLines: NowPlayingAboutTokens.carouselParagraphLines,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodyMedium?.copyWith(color: colors.onSurface),
            ),
          ),
        ],
      ),
    };
    return SpringCrossFade(
      childKey: '${widget.songKey}#$current',
      child: Align(alignment: AlignmentDirectional.centerStart, child: body),
    );
  }
}

/// The compact About inside the resting window (SongInfoDisplay): the
/// carousel of the receipt and paragraphs; the AI's state (load, searching,
/// no key, the error with Retry) under it, or alone with nothing to show yet.
/// Read-only: editing and asking happen in the expanded pane.
class SongInfoDisplay extends StatelessWidget {
  const SongInfoDisplay({
    super.key,
    required this.state,
    required this.onRetry,
    required this.onOpenSettings,
    this.facts,
    this.songKey = '',
  });

  final AboutUiState state;
  final VoidCallback onRetry;
  final VoidCallback onOpenSettings;
  final AboutSourceFacts? facts;
  final String songKey;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final entries = switch (state) {
      AboutReady(:final entries) => entries,
      _ => const <SongAboutEntry>[],
    };
    final pages = aboutCarouselPages(
      aboutReceiptRows(entries, facts, NowPlayingAboutLabels(strings)),
      aboutParagraphs(entries, strings.aboutReview),
    );
    final status = _AboutStatus(state: state, onRetry: onRetry, onOpenSettings: onOpenSettings);
    if (pages.isEmpty) return Align(alignment: AlignmentDirectional.centerStart, child: status);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Expanded(
          child: AboutCarousel(pages: pages, songKey: songKey),
        ),
        if (state is! AboutReady) status,
      ],
    );
  }
}

class _AboutStatus extends StatelessWidget {
  const _AboutStatus({required this.state, required this.onRetry, required this.onOpenSettings});

  final AboutUiState state;
  final VoidCallback onRetry;
  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final muted = theme.textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant);
    final Widget child = switch (state) {
      AboutIdle() => TextButton(onPressed: onRetry, child: Text(strings.songInfoTapLoad)),
      AboutLoading() => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const YoinLoadingIndicator(size: NowPlayingAboutTokens.statusIndicator),
          const SizedBox(width: NowPlayingAboutTokens.statusGap),
          Flexible(child: Text(strings.songInfoSearching, style: muted)),
        ],
      ),
      AboutKeyMissing() => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(child: Text(strings.songInfoNoKey, style: muted)),
          TextButton(onPressed: onOpenSettings, child: Text(strings.songInfoSettings)),
        ],
      ),
      AboutError(:final message) => Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(
            child: Text(
              message,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodyMedium?.copyWith(color: colors.error),
            ),
          ),
          TextButton(onPressed: onRetry, child: Text(strings.songInfoRetry)),
        ],
      ),
      AboutReady() => const SizedBox.shrink(),
    };
    return SpringCrossFade(fit: StackFit.loose, childKey: state.runtimeType, child: child);
  }
}

/// What a long-pressed About row can do; Now Playing provides it.
final class AboutEntryActions {
  const AboutEntryActions({
    required this.canManage,
    required this.onManage,
    required this.onRegenerate,
    this.regenerating = const {},
  });

  /// The rows the AI is writing again.
  final Set<AboutRef> regenerating;

  /// Whether the song on show has an album page (its scrapbook is there).
  final bool Function() canManage;
  final ValueChanged<SongAboutEntry> onManage;
  final ValueChanged<SongAboutEntry> onRegenerate;
}

/// The expanded About (AboutFullscreenPane): searching, no key (the
/// server's facts, the line and Settings), the error (the facts, the message
/// and Retry), or the receipt and the paragraphs. The Ask bar is not here:
/// it is the accessory row's, under the pane. [actions] null: rows take no
/// long press.
class AboutPane extends StatelessWidget {
  const AboutPane({
    super.key,
    required this.state,
    required this.onRetry,
    required this.onOpenSettings,
    this.facts,
    this.actions,
    this.bottomPadding = NowPlayingAboutTokens.paneBottom,
  });

  final AboutUiState state;
  final VoidCallback onRetry;
  final VoidCallback onOpenSettings;
  final AboutSourceFacts? facts;
  final AboutEntryActions? actions;
  final double bottomPadding;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    Widget sourceReceipt() {
      final rows = aboutReceiptRows(const [], facts, NowPlayingAboutLabels(strings));
      return rows.isEmpty
          ? const SizedBox.shrink()
          : Padding(
              padding: const EdgeInsets.only(bottom: NowPlayingAboutTokens.receiptStatusGap),
              child: AboutReceipt(rows: rows),
            );
    }

    final Widget body = switch (state) {
      AboutIdle() => const SizedBox.expand(),
      AboutLoading() => Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const YoinLoadingIndicator(),
            const SizedBox(height: NowPlayingAboutTokens.loadingGap),
            Text(strings.aboutSearching, style: theme.textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant)),
          ],
        ),
      ),
      AboutKeyMissing() => SingleChildScrollView(
        padding: const EdgeInsets.symmetric(vertical: NowPlayingAboutTokens.paneStatusPadV),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            sourceReceipt(),
            Text(strings.aboutKeyMissing, style: theme.textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant)),
            TextButton(onPressed: onOpenSettings, child: Text(strings.aboutOpenSettings)),
          ],
        ),
      ),
      AboutError(:final message) => SingleChildScrollView(
        padding: const EdgeInsets.symmetric(vertical: NowPlayingAboutTokens.paneStatusPadV),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            sourceReceipt(),
            Text(message, style: theme.textTheme.bodyMedium?.copyWith(color: colors.error)),
            TextButton(onPressed: onRetry, child: Text(strings.aboutRetry)),
          ],
        ),
      ),
      AboutReady(:final entries) => _ReadyAbout(
        entries: entries,
        facts: facts,
        actions: actions,
        bottomPadding: bottomPadding,
      ),
    };
    return SpringCrossFade(childKey: state.runtimeType, child: body);
  }
}

class _ReadyAbout extends StatefulWidget {
  const _ReadyAbout({required this.entries, required this.facts, required this.actions, required this.bottomPadding});

  final List<SongAboutEntry> entries;
  final AboutSourceFacts? facts;
  final AboutEntryActions? actions;
  final double bottomPadding;

  @override
  State<_ReadyAbout> createState() => _ReadyAboutState();
}

class _ReadyAboutState extends State<_ReadyAbout> with TickerProviderStateMixin {
  final ScrollController _scroll = ScrollController();

  /// The bottom fade: shown while there is more below, released at the end
  /// so the last entry reads crisp above the Ask bar (effects spring).
  late final SpringValue _fade = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_syncFade);
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncFade());
  }

  void _syncFade() {
    if (!mounted || !_scroll.hasClients) return;
    final more = _scroll.position.extentAfter > 1;
    if (MediaQuery.disableAnimationsOf(context)) {
      _fade.snapTo(more ? 1 : 0);
    } else {
      _fade.animateTo(more ? 1 : 0);
    }
  }

  @override
  void didUpdateWidget(_ReadyAbout old) {
    super.didUpdateWidget(old);
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncFade());
  }

  @override
  void dispose() {
    _scroll.dispose();
    _fade.dispose();
    super.dispose();
  }

  void _open(SongAboutEntry entry, String label) {
    final actions = widget.actions;
    if (actions == null) return;
    YoinHaptics.longPress();
    showAboutEntrySheet(context, entry: entry, label: label, actions: actions);
  }

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final actions = widget.actions;
    final regenerating = actions?.regenerating ?? const <AboutRef>{};
    final rows = aboutReceiptRows(widget.entries, widget.facts, NowPlayingAboutLabels(strings));
    final paragraphs = aboutParagraphs(widget.entries, strings.aboutReview);
    return AnimatedBuilder(
      animation: _fade.animation,
      builder: (context, child) {
        final f = _fade.value.clamp(0.0, 1.0);
        if (f <= 0.001) return child!;
        return ShaderMask(
          blendMode: BlendMode.dstIn,
          shaderCallback: (rect) {
            final stop = rect.height <= 0
                ? 1.0
                : (1 - NowPlayingAboutTokens.paneFadeBottom / rect.height).clamp(0.0, 1.0);
            return LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [
                Colors.white,
                Colors.white,
                Colors.white.withValues(alpha: 1 - f),
              ],
              stops: [0, stop, 1],
            ).createShader(rect);
          },
          child: child,
        );
      },
      child: NotificationListener<ScrollMetricsNotification>(
        onNotification: (_) {
          _syncFade();
          return false;
        },
        child: SingleChildScrollView(
          controller: _scroll,
          padding: EdgeInsets.only(top: NowPlayingAboutTokens.paneTop, bottom: widget.bottomPadding),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              AboutReceipt(
                rows: rows,
                thinking: (entry) => regenerating.contains(entry.ref),
                onLongPress: actions == null ? null : (row) => _open(row.entry!, row.label),
              ),
              // The review, the answers and the listener's own entries (written in the scrapbook).
              for (final (i, p) in paragraphs.indexed) ...[
                if (i > 0)
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: NowPlayingAboutTokens.paragraphInset),
                    child: DashedRule(),
                  ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: NowPlayingAboutTokens.paragraphInset),
                  child: AboutParagraphSection(
                    paragraph: p,
                    thinking: regenerating.contains(p.entry.ref),
                    onLongPress: actions == null ? null : () => _open(p.entry, p.label),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// The long-pressed row's sheet (AboutEntrySheet): its heading and the start
/// of its text, then Manage in Scrapbook (when the song has an album page)
/// and Regenerate with Gemini (not for the listener's own entry).
Future<void> showAboutEntrySheet(
  BuildContext context, {
  required SongAboutEntry entry,
  required String label,
  required AboutEntryActions actions,
}) {
  final strings = NowPlayingStrings.of(context);
  final rows = <(String, IconData, VoidCallback)>[
    if (actions.canManage()) (strings.aboutManage, YoinSymbols.note, () => actions.onManage(entry)),
    if (!entry.isOwn) (strings.aboutRegenerate, YoinSymbols.sparkle, () => actions.onRegenerate(entry)),
  ];
  return showModalBottomSheet<void>(
    context: context,
    useSafeArea: false,
    isScrollControlled: true,
    showDragHandle: true,
    backgroundColor: Theme.of(context).colorScheme.surfaceContainerLow,
    builder: (sheet) {
      final theme = Theme.of(sheet);
      final colors = theme.colorScheme;
      final navBottom = MediaQuery.paddingOf(sheet).bottom;
      return ChromeBarSuppression(
        child: Padding(
          padding: EdgeInsets.fromLTRB(
            NowPlayingAboutTokens.sheetPadH,
            0,
            NowPlayingAboutTokens.sheetPadH,
            NowPlayingAboutTokens.sheetPadH + navBottom,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(
                  NowPlayingAboutTokens.sheetHeadingInset,
                  0,
                  NowPlayingAboutTokens.sheetHeadingInset,
                  4,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.titleMedium?.copyWith(color: colors.primary),
                    ),
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text.rich(
                        markdownBoldSpan(entry.answerText),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant),
                      ),
                    ),
                  ],
                ),
              ),
              if (rows.isNotEmpty) ...[
                const SizedBox(height: NowPlayingAboutTokens.sheetGap - 4),
                for (final (i, (text, icon, run)) in rows.indexed) ...[
                  if (i > 0) const SizedBox(height: SheetTokens.trackMenuRowGap),
                  Material(
                    color: colors.surfaceContainerHigh,
                    shape: aboutSheetSegmentShape(i, rows.length),
                    clipBehavior: Clip.antiAlias,
                    child: InkWell(
                      onTap: () {
                        YoinHaptics.contextClick();
                        Navigator.of(sheet).pop();
                        run();
                      },
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(minHeight: SheetTokens.trackMenuRowHeight),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: SheetTokens.trackMenuRowPaddingH),
                          child: Row(
                            children: [
                              Icon(icon, color: colors.onSurfaceVariant),
                              const SizedBox(width: SheetTokens.trackMenuIconGap),
                              Expanded(
                                child: Text(text, style: theme.textTheme.bodyLarge?.copyWith(color: colors.onSurface)),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ],
            ],
          ),
        ),
      );
    },
  );
}

/// A row's shape in a sheet's group (TrackMenuGroup): the outer corners round, the inner ones tighter.
ShapeBorder aboutSheetSegmentShape(int index, int count) {
  const outer = Radius.circular(SheetTokens.segmentOuterCorner);
  const inner = Radius.circular(SheetTokens.segmentInnerCorner);
  final top = index == 0 ? outer : inner;
  final bottom = index == count - 1 ? outer : inner;
  return RoundedRectangleBorder(
    borderRadius: BorderRadius.only(topLeft: top, topRight: top, bottomLeft: bottom, bottomRight: bottom),
  );
}

/// The quick questions as a row of pills that scrolls sideways (QuickPrompts);
/// a tap hands the question to [onPick]. With [fadeEdges] each end fades
/// while more pills lie beyond it.
class QuickPrompts extends StatefulWidget {
  const QuickPrompts({
    super.key,
    required this.prompts,
    required this.container,
    required this.content,
    required this.onPick,
    this.padding = EdgeInsets.zero,
    this.fadeEdges = false,
  });

  final List<String> prompts;
  final Color container;
  final Color content;
  final ValueChanged<String> onPick;
  final EdgeInsetsGeometry padding;
  final bool fadeEdges;

  @override
  State<QuickPrompts> createState() => _QuickPromptsState();
}

class _QuickPromptsState extends State<QuickPrompts> {
  final ScrollController _scroll = ScrollController();
  bool _before = false;
  bool _after = false;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_sync);
    WidgetsBinding.instance.addPostFrameCallback((_) => _sync());
  }

  void _sync() {
    if (!mounted || !_scroll.hasClients) return;
    final before = _scroll.position.extentBefore > 1;
    final after = _scroll.position.extentAfter > 1;
    if (before != _before || after != _after) {
      setState(() {
        _before = before;
        _after = after;
      });
    }
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w500, color: widget.content);
    Widget row = SingleChildScrollView(
      controller: _scroll,
      scrollDirection: Axis.horizontal,
      padding: widget.padding,
      child: Row(
        children: [
          for (final (i, p) in widget.prompts.indexed) ...[
            if (i > 0) const SizedBox(width: NowPlayingAboutTokens.promptGap),
            Material(
              color: widget.container,
              shape: const StadiumBorder(),
              clipBehavior: Clip.antiAlias,
              child: InkWell(
                onTap: () {
                  YoinHaptics.tick();
                  widget.onPick(p);
                },
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: NowPlayingAboutTokens.promptPadH,
                    vertical: NowPlayingAboutTokens.promptPadV,
                  ),
                  child: Text(p, maxLines: 1, style: style),
                ),
              ),
            ),
          ],
        ],
      ),
    );
    if (widget.fadeEdges) {
      row = NotificationListener<ScrollMetricsNotification>(
        onNotification: (_) {
          _sync();
          return false;
        },
        child: ShaderMask(
          blendMode: BlendMode.dstIn,
          shaderCallback: (rect) {
            final f = rect.width <= 0 ? 0.0 : (NowPlayingAboutTokens.promptFadeEdge / rect.width).clamp(0.0, 0.5);
            return LinearGradient(
              colors: [
                Colors.white.withValues(alpha: _before ? 0 : 1),
                Colors.white,
                Colors.white,
                Colors.white.withValues(alpha: _after ? 0 : 1),
              ],
              stops: [0, f, 1 - f, 1],
            ).createShader(rect);
          },
          child: row,
        ),
      );
    }
    return Semantics(label: NowPlayingStrings.of(context).quickPromptsLabel, container: true, child: row);
  }
}
