/// Now Playing's About widgets (Android AboutReceipt.kt, AboutFullscreenPane.kt,
/// AboutEntrySheet.kt, component/SongInfoDisplay.kt and GeminiPrompts.kt):
/// the receipt of short facts with a dashed rule between lines, the
/// paragraphs under it, the compact carousel, the expanded pane, the
/// long-pressed row's sheet, the quick prompts and the thinking dots.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/about/song_about_store.dart';
import '../data/model/model.dart';
import '../home/widgets/spring_height.dart';
import '../shell/bar_suppression.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/ai_service_scope.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'about_logic.dart';
import 'haptics.dart';
import 'nowplaying_strings.dart';
import 'share_content.dart';
import 'share_sheet.dart';

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
  @override
  String get arranger => s.aboutArranger;
  @override
  String get size => s.aboutSize;
  @override
  String megabytes(String value) => s.aboutMegabytes(value);
  @override
  String get bpm => s.aboutBpm;
  @override
  String get playCount => s.aboutPlayCount;
  @override
  String get comment => s.aboutComment;
  @override
  String get file => s.aboutFile;
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
      label: NowPlayingStrings.of(context).aiWriting(AiServiceScope.of(context)),
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

/// The receipt card ([rows] with a dashed rule between). [credits] give the
/// lyrics' other roles, behind an "N more · …" line that unrolls them on a
/// spatial spring ("Show less" rolls them up), and the copyright notices,
/// small at the foot. [thinking] marks a row the AI is writing again;
/// [onLongPress] opens an AI row's sheet (null: rows don't respond).
class AboutReceipt extends StatefulWidget {
  const AboutReceipt({super.key, required this.rows, this.credits, this.thinking, this.onLongPress});

  final List<ReceiptRow> rows;
  final LyricCredits? credits;
  final bool Function(SongAboutEntry entry)? thinking;
  final void Function(ReceiptRow row)? onLongPress;

  @override
  State<AboutReceipt> createState() => _AboutReceiptState();
}

class _AboutReceiptState extends State<AboutReceipt> {
  /// The other credits are shown; folds again when the credits change (a new song).
  bool _expanded = false;

  @override
  void didUpdateWidget(AboutReceipt old) {
    super.didUpdateWidget(old);
    if (old.credits != widget.credits) _expanded = false;
  }

  @override
  Widget build(BuildContext context) {
    final rows = widget.rows;
    final others = widget.credits?.others ?? const <LyricCredit>[];
    final notices = widget.credits?.notices ?? const <String>[];
    if (rows.isEmpty && others.isEmpty && notices.isEmpty) return const SizedBox.shrink();
    final strings = NowPlayingStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final thinking = widget.thinking;
    final onLongPress = widget.onLongPress;
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
        // The card follows its content on the spatial spring (animateContentSize).
        child: SpringHeight(
          // Any change of what the card holds (an AI row landing or rewritten, the credits unrolling, a new
          // song's credits) springs; the thinking dots swap in place.
          heightKey: (_expanded, Object.hashAll(rows), widget.credits),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final (i, row) in rows.indexed) ...[
                if (i > 0) const DashedRule(),
                _ReceiptLine(
                  label: row.label,
                  value: row.value,
                  thinking: row.entry != null && (thinking?.call(row.entry!) ?? false),
                  onLongPress: row.entry != null && onLongPress != null ? () => onLongPress(row) : null,
                ),
              ],
              if (others.isNotEmpty) ...[
                if (rows.isNotEmpty) const DashedRule(),
                if (_expanded)
                  for (final credit in others) ...[_OtherCreditLine(credit), const DashedRule()],
                // clickable(role = Button): a plain rectangular ripple inside the card's padding, so no
                // rounded clip (CornerMask) is needed.
                Semantics(
                  button: true,
                  child: Material(
                    type: MaterialType.transparency,
                    child: InkWell(
                      onTap: () => setState(() => _expanded = !_expanded),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: NowPlayingAboutTokens.creditsTogglePadV),
                        // "N more", then the first roles quieter beside it: space between, no glyph.
                        child: Row(
                          children: [
                            Text(
                              _expanded ? strings.lyricCreditsLess : strings.lyricCreditsMore(others.length),
                              maxLines: 1,
                              style: theme.textTheme.labelMedium?.copyWith(color: colors.primary),
                            ),
                            if (!_expanded) ...[
                              const SizedBox(width: NowPlayingAboutTokens.creditsToggleGap),
                              Flexible(
                                child: Text(
                                  lyricCreditsMorePreview(others, strings.lyricCreditsNote),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                  style: theme.textTheme.labelMedium?.copyWith(color: colors.onSurfaceVariant),
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ],
              if (notices.isNotEmpty) ...[
                const DashedRule(),
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: NowPlayingAboutTokens.noticesPadV),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      for (final (i, notice) in notices.indexed)
                        Padding(
                          padding: EdgeInsets.only(top: i == 0 ? 0 : NowPlayingAboutTokens.noticeGap),
                          child: Text(
                            notice,
                            textAlign: TextAlign.center,
                            style: theme.textTheme.labelSmall?.copyWith(color: colors.onSurfaceVariant),
                          ),
                        ),
                    ],
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

/// One of the lyrics' other credits: a receipt line, or a free-form line on its own.
class _OtherCreditLine extends StatelessWidget {
  const _OtherCreditLine(this.credit);

  final LyricCredit credit;

  @override
  Widget build(BuildContext context) {
    if (credit.role.isNotEmpty) return _ReceiptLine(label: credit.role, value: credit.names);
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: NowPlayingAboutTokens.receiptLinePadV),
      child: Text(credit.names, style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurface)),
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
    // Centred on the window's height; a page taller than the window (a long
    // credits line, a landscape handset's few lines) starts at the top and
    // fades out downward instead of being cut at both ends (Android 11-16).
    return SpringCrossFade(
      childKey: '${widget.songKey}#$current',
      child: AboutCarouselFrame(fade: NowPlayingAboutTokens.carouselOverflowFade, child: body),
    );
  }
}

/// A compact About page in its window (AboutCarousel): centred when it fits;
/// taller than the window, it starts at the top, clipped, with its bottom
/// [fade] dp fading out. The page is laid out at its own height, never squeezed.
class AboutCarouselFrame extends SingleChildRenderObjectWidget {
  const AboutCarouselFrame({super.key, required this.fade, required Widget super.child});

  final double fade;

  @override
  RenderObject createRenderObject(BuildContext context) => RenderAboutCarouselFrame(fade);

  @override
  void updateRenderObject(BuildContext context, RenderAboutCarouselFrame renderObject) => renderObject.fade = fade;
}

/// smoothstep sampled at six points (EdgeFade.kt easedMaskColors).
const _easedFadeStops = [0.0, 0.104, 0.352, 0.648, 0.896, 1.0];

/// [AboutCarouselFrame]'s box: lays the page out unbounded, centres or tops it, clips and fades an overflow.
class RenderAboutCarouselFrame extends RenderShiftedBox {
  RenderAboutCarouselFrame(this._fade) : super(null);

  double _fade;
  set fade(double value) {
    if (value == _fade) return;
    _fade = value;
    markNeedsPaint();
  }

  bool _overflows = false;

  /// The page is taller than the window: it starts at the top and fades out downward.
  bool get overflows => _overflows;

  final LayerHandle<ClipRectLayer> _clip = LayerHandle<ClipRectLayer>();
  final LayerHandle<ShaderMaskLayer> _mask = LayerHandle<ShaderMaskLayer>();

  @override
  bool get alwaysNeedsCompositing => child != null;

  @override
  void performLayout() {
    final page = child;
    if (page == null) {
      size = constraints.smallest;
      return;
    }
    page.layout(constraints.copyWith(minHeight: 0, maxHeight: double.infinity), parentUsesSize: true);
    size = constraints.constrain(
      Size(
        constraints.hasBoundedWidth ? constraints.maxWidth : page.size.width,
        constraints.hasBoundedHeight ? constraints.maxHeight : page.size.height,
      ),
    );
    _overflows = page.size.height > size.height + 0.5;
    // The page fills the width (its columns stretch), so only its y moves.
    (page.parentData! as BoxParentData).offset = Offset(0, _overflows ? 0 : (size.height - page.size.height) / 2);
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    if (child == null) return;
    if (!_overflows) {
      _clip.layer = null;
      _mask.layer = null;
      super.paint(context, offset);
      return;
    }
    _clip.layer = context.pushClipRect(needsCompositing, offset, Offset.zero & size, (context, offset) {
      final layer = _mask.layer ??= ShaderMaskLayer();
      final start = size.height <= 0 ? 1.0 : (1 - _fade / size.height).clamp(0.0, 1.0);
      // Opaque down to the fade, then out on a smoothstep ramp (edgeFade's
      // eased stops), so the fade has no visible onset line.
      layer
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [const Color(0xFFFFFFFF), for (final t in _easedFadeStops) Color.fromRGBO(255, 255, 255, 1 - t)],
          stops: [0, for (final t in _easedFadeStops) start + (1 - start) * t],
        ).createShader(Offset.zero & size)
        ..maskRect = offset & size
        ..blendMode = BlendMode.dstIn;
      context.pushLayer(layer, super.paint, offset);
    }, oldLayer: _clip.layer);
  }

  @override
  void dispose() {
    _clip.layer = null;
    _mask.layer = null;
    super.dispose();
  }
}

/// The compact About inside the resting window (SongInfoDisplay): the
/// carousel of the receipt and paragraphs; no key or the error with Retry
/// under it (a search over it shows in the tab row), or the AI's state (load,
/// searching, no key, the error) alone with nothing to show yet.
/// Read-only: editing and asking happen in the expanded pane.
class SongInfoDisplay extends StatelessWidget {
  const SongInfoDisplay({
    super.key,
    required this.state,
    required this.onRetry,
    required this.onOpenSettings,
    this.credits,
    this.facts,
    this.songKey = '',
  });

  final AboutUiState state;
  final VoidCallback onRetry;
  final VoidCallback onOpenSettings;

  /// The credits the lyrics source wrote into the lyrics; shown with or without the AI.
  final LyricCredits? credits;
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
      aboutReceiptRows(entries, credits, facts, NowPlayingAboutLabels(strings)),
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
        // Loading over content shows in the tab row (between About and Note), not under it.
        if (state is AboutKeyMissing || state is AboutError) status,
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
          Flexible(child: Text(strings.aiNoKey(AiServiceScope.of(context)), style: muted)),
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
/// and Retry), or the receipt and the paragraphs — the credits and facts
/// alone while the AI is still to come or searching. The Ask bar is not here:
/// it is the accessory row's, under the pane. [actions] null: rows take no
/// long press.
class AboutPane extends StatelessWidget {
  const AboutPane({
    super.key,
    required this.state,
    required this.onRetry,
    required this.onOpenSettings,
    this.credits,
    this.facts,
    this.actions,
    this.bottomPadding = NowPlayingAboutTokens.paneBottom,
  });

  final AboutUiState state;
  final VoidCallback onRetry;
  final VoidCallback onOpenSettings;

  /// The credits the lyrics source wrote into the lyrics; shown first, with or without the AI.
  final LyricCredits? credits;
  final AboutSourceFacts? facts;
  final AboutEntryActions? actions;
  final double bottomPadding;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    // The receipt without the AI (no key, or it failed): the lyrics' credits and the server's facts.
    Widget sourceReceipt() {
      final rows = aboutReceiptRows(const [], credits, facts, NowPlayingAboutLabels(strings));
      final c = credits;
      final empty = rows.isEmpty && (c == null || (c.others.isEmpty && c.notices.isEmpty));
      return empty
          ? const SizedBox.shrink()
          : Padding(
              padding: const EdgeInsets.only(bottom: NowPlayingAboutTokens.receiptStatusGap),
              child: AboutReceipt(rows: rows, credits: credits),
            );
    }

    // Credits / server facts already in hand: show them while the AI is still
    // to come or loading (the tab row carries the wait), instead of a blank
    // page or a lone spinner. Same key as Ready, so the AI's rows join in
    // place rather than cross-fading the page (Android 11-16).
    final AboutUiState shown = (state is AboutIdle || state is AboutLoading) && aboutHasSourceRows(credits, facts)
        ? const AboutReady([])
        : state;
    final Widget body = switch (shown) {
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
            Text(
              strings.aiNoKey(AiServiceScope.of(context)),
              style: theme.textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant),
            ),
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
        credits: credits,
        facts: facts,
        actions: actions,
        bottomPadding: bottomPadding,
      ),
    };
    return SpringCrossFade(childKey: shown.runtimeType, child: body);
  }
}

class _ReadyAbout extends StatefulWidget {
  const _ReadyAbout({
    required this.entries,
    required this.credits,
    required this.facts,
    required this.actions,
    required this.bottomPadding,
  });

  final List<SongAboutEntry> entries;
  final LyricCredits? credits;
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
    final rows = aboutReceiptRows(widget.entries, widget.credits, widget.facts, NowPlayingAboutLabels(strings));
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
                credits: widget.credits,
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
/// of its text, then Manage in Scrapbook (when the song has an album page),
/// Regenerate with the AI service in use (not for the listener's own entry) and Share as
/// image (inside Now Playing; not for the listener's own entry either —
/// About's image carries only the AI's words), which opens the share sheet on
/// 关于 with this row's paragraph.
Future<void> showAboutEntrySheet(
  BuildContext context, {
  required SongAboutEntry entry,
  required String label,
  required AboutEntryActions actions,
}) {
  final strings = NowPlayingStrings.of(context);
  final share = NowPlayingShareScope.maybeOf(context);
  final rows = <(String, IconData, VoidCallback)>[
    if (actions.canManage()) (strings.aboutManage, YoinSymbols.note, () => actions.onManage(entry)),
    if (!entry.isOwn)
      (strings.aiRegenerateWith(AiServiceScope.of(context)), YoinSymbols.sparkle, () => actions.onRegenerate(entry)),
    if (!entry.isOwn && share != null)
      (strings.shareSheetTitle, YoinSymbols.share, () => share(ShareRequest(ShareTab.about, aboutFocus: entry))),
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
