import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';

/// Width units a title may have and still be glued into one unbreakable run.
const flowingTitleGlueUnits = 16;

const _nbsp = ' ';
const _wordJoiner = '⁠';

/// East Asian wide glyphs (CJK, kana, Hangul, full-width forms).
bool isWideRune(int r) =>
    (r >= 0x1100 && r <= 0x115F) ||
    (r >= 0x2E80 && r <= 0xA4CF) ||
    (r >= 0xAC00 && r <= 0xD7A3) ||
    (r >= 0xF900 && r <= 0xFAFF) ||
    (r >= 0xFE30 && r <= 0xFE4F) ||
    (r >= 0xFF00 && r <= 0xFF60) ||
    (r >= 0xFFE0 && r <= 0xFFE6) ||
    (r >= 0x20000 && r <= 0x3FFFD);

/// A wide glyph counts 2, everything else 1.
int titleWidthUnits(String title) => title.runes.fold(0, (sum, r) => sum + (isWideRune(r) ? 2 : 1));

/// A short title (≤ 16 units) glued so it wraps as a whole: spaces become
/// NBSP and a WORD JOINER goes between wide glyphs and at wide/narrow
/// boundaries; longer titles stay breakable (Android flowingTitle).
String glueTitle(String title) {
  if (titleWidthUnits(title) > flowingTitleGlueUnits) return title;
  final out = StringBuffer();
  int? previous;
  for (final r in title.runes) {
    if (previous != null && (isWideRune(previous) || isWideRune(r)) && r != 0x20 && previous != 0x20) {
      out.write(_wordJoiner);
    }
    out.write(r == 0x20 ? _nbsp : String.fromCharCode(r));
    previous = r;
  }
  return out.toString();
}

/// One title of the flowing headline.
@immutable
final class FlowingTitle {
  const FlowingTitle(this.title, {this.feat});
  final String title;

  /// The " (feat. X)" credit already formatted, or null.
  final String? feat;
}

/// All of a page's track titles as ONE flowing headline (headlineMedium),
/// joined by a plain space, tone alternating onSurface / onSurfaceVariant;
/// each title is its own link ([onTap] with its index) and underlines
/// while pressed. Links only work while [enabled]. Unbounded height,
/// never ellipsized.
class FlowingTitles extends StatefulWidget {
  const FlowingTitles({
    super.key,
    required this.titles,
    required this.onTap,
    this.enabled = true,
    this.emptyText,
    this.style,
  });

  final List<FlowingTitle> titles;
  final ValueChanged<int> onTap;
  final bool enabled;

  /// Shown when there are no titles (the album name).
  final String? emptyText;

  /// In place of headlineMedium (a landscape hero's headlineSmall).
  final TextStyle? style;

  @override
  State<FlowingTitles> createState() => _FlowingTitlesState();
}

class _FlowingTitlesState extends State<FlowingTitles> {
  final _recognizers = <TapGestureRecognizer>[];
  int? _pressed;

  @override
  void initState() {
    super.initState();
    _syncRecognizers();
  }

  @override
  void didUpdateWidget(FlowingTitles old) {
    super.didUpdateWidget(old);
    if (old.titles.length != widget.titles.length || old.enabled != widget.enabled) _syncRecognizers();
  }

  /// One recognizer per title, kept across rebuilds (a press rebuilds for the
  /// underline while its recognizer is still tracking the tap).
  void _syncRecognizers() {
    _disposeRecognizers();
    if (!widget.enabled) {
      _pressed = null;
      return;
    }
    for (var i = 0; i < widget.titles.length; i++) {
      _recognizers.add(_recognizer(i));
    }
  }

  @override
  void dispose() {
    _disposeRecognizers();
    super.dispose();
  }

  void _disposeRecognizers() {
    for (final r in _recognizers) {
      r.dispose();
    }
    _recognizers.clear();
  }

  TapGestureRecognizer _recognizer(int i) {
    final r = TapGestureRecognizer();
    r.onTapDown = (_) => setState(() => _pressed = i);
    r.onTapCancel = () => setState(() => _pressed = null);
    r.onTapUp = (_) => setState(() => _pressed = null);
    r.onTap = () => widget.onTap(i);
    return r;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = widget.style ?? theme.textTheme.headlineMedium!;
    final colors = theme.colorScheme;
    if (widget.titles.isEmpty) {
      return Text(widget.emptyText ?? '', style: style.copyWith(color: colors.onSurface));
    }
    final spans = <InlineSpan>[];
    for (final (i, t) in widget.titles.indexed) {
      final recognizer = i < _recognizers.length ? _recognizers[i] : null;
      final ink = i.isEven ? colors.onSurface : colors.onSurfaceVariant;
      final underline = _pressed == i ? TextDecoration.underline : TextDecoration.none;
      if (i > 0) spans.add(const TextSpan(text: ' '));
      spans.add(
        TextSpan(
          text: glueTitle(t.title),
          recognizer: recognizer,
          style: style.copyWith(color: ink, decoration: underline, decorationColor: ink),
          children: [
            if (t.feat != null)
              TextSpan(
                text: t.feat,
                style: style.copyWith(
                  fontSize: style.fontSize! * 0.6,
                  color: colors.onSurfaceVariant,
                  decoration: underline,
                ),
              ),
          ],
        ),
      );
    }
    return RepaintBoundary(child: Text.rich(TextSpan(children: spans), softWrap: true));
  }
}
