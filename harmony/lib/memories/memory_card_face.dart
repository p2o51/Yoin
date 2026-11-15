/// The card state of one memory (Android showcase MemoryCardFace, twostate4
/// `.ts-a`): two clusters and the air between them.
///
///  · The exhibit sits at a fixed height — air1, the cover with the groove
///    emblem pinned to its lower-right corner, the title, the album row — so
///    every card's cover top is the same.
///  · The teaser anchors to the bottom — the excerpt, [Diary + note count |
///    Go to album] — with [MemoriesLayout.teaserBottom] of air under it.
///  · The excerpt is the first candidate whose block fits the slot between
///    the album row (+24) and the buttons (−16); the last candidate is the
///    attribution line alone, so nothing is ever cut or ellipsized.
///
/// Flat: no shadow, no cover border, Go to album has no arrow. The cover
/// itself is drawn by the page's flying cover (the card ⇄ diary shared
/// element); the card leaves its slot empty.
library;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../nowplaying/haptics.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import '../ui/component/meta_line.dart';
import 'copy/memory_voice.dart';
import 'memories_layout.dart';
import 'memories_motion.dart';
import 'memories_strings.dart';
import 'memories_ui_state.dart';
import 'memory_title_editor.dart';
import 'palette/memory_palette.dart';

/// The card title (album name, fallback A): GSF SemiBold, two lines at most.
TextStyle memoryCardTitleStyle(MemoriesLayout layout, Color color) =>
    gsf(layout.type.cardTitle, weight: 600, height: 1.3, letterSpacing: 0).copyWith(color: color);

/// The card title for its kind (Android cardTitleStyle): a written title —
/// the user's own, or Yoin's AI title — in the serif, the album name in GSF.
TextStyle memoryCardTitleStyleFor(MemoryTitleKind kind, MemoriesLayout layout, Color color) =>
    kind == MemoryTitleKind.album
    ? memoryCardTitleStyle(layout, color)
    : memoryWrittenTitleStyle(memoryCardWrittenTitleSize(layout), height: 1.36, color: color);

/// The album name under a written title (Android cardAlbumStyle).
TextStyle memoryCardAlbumStyle(MemoriesLayout layout, Color color) =>
    gsf(layout.type.cardAlbum, weight: 600, height: 1.3, letterSpacing: 0).copyWith(color: color);

double _albumTop(MemoriesLayout layout) =>
    layout.short ? MemoryTitleTokens.cardAlbumTopShort : MemoryTitleTokens.cardAlbumTop;

/// The album name is the title: two lines at most; a written title is whole.
int? memoryCardTitleLines(MemoryTitleKind kind) => kind == MemoryTitleKind.album ? 2 : null;

TextStyle memoryCardArtistStyle(MemoriesLayout layout, Color color) =>
    gsf(layout.type.cardArtist, weight: 500, height: 1.35, letterSpacing: 0).copyWith(color: color);

/// The user's own words, three sizes by length.
TextStyle memoryExcerptStyle(MemoryExcerptSize size, Color color) => switch (size) {
  MemoryExcerptSize.short => gsf(22, weight: 500, height: 1.4, letterSpacing: 0),
  MemoryExcerptSize.medium => gsf(17, weight: 400, height: 1.65, letterSpacing: 0),
  MemoryExcerptSize.long => gsf(16, weight: 400, height: 1.7, letterSpacing: 0),
}.copyWith(color: color);

TextStyle _attributionStyle(Color color) => gsf(
  12,
  weight: 500,
  height: 1.3,
  letterSpacing: 0.1,
).copyWith(color: color, fontFeatures: const [FontFeature.tabularFigures()]);

TextStyle _labelSmall(Color color) => gsf(
  TypeTokens.labelSmall.size,
  weight: 500,
  height: TypeTokens.labelSmall.lineHeight / TypeTokens.labelSmall.size,
  letterSpacing: 0.1,
).copyWith(color: color);

double _measure(String text, TextStyle style, double width, {int? maxLines, TextAlign align = TextAlign.center}) {
  final painter = TextPainter(
    text: TextSpan(text: text, style: style),
    textDirection: TextDirection.ltr,
    textAlign: align,
    maxLines: maxLines,
  )..layout(maxWidth: width);
  final h = painter.height;
  painter.dispose();
  return h;
}

/// Where the exhibit's cover sits inside the card face (its top-left), for
/// the page's flying cover.
Offset memoryCardCoverOffset(MemoriesLayout layout, double faceWidth) =>
    Offset((faceWidth - layout.cover) / 2, layout.air1);

class MemoryCardFace extends StatelessWidget {
  const MemoryCardFace({
    super.key,
    required this.memory,
    required this.tones,
    required this.layout,
    required this.diary,
    required this.emblem,
    required this.onOpenDiary,
    required this.onOpenAlbum,
    required this.reducedMotion,
    this.interactive = true,
    this.titleEditing,
    this.titleEnabled = false,
    this.titleLift,
  });

  final MemoryEntry memory;
  final MemoryPaletteTones tones;
  final MemoriesLayout layout;

  /// p: the morph moves the card's pieces (title, row, excerpt, teaser).
  final Animation<double> diary;

  /// The emblem at [MemoriesLayout.seal], already sized.
  final Widget emblem;
  final VoidCallback onOpenDiary;
  final VoidCallback onOpenAlbum;
  final bool reducedMotion;
  final bool interactive;

  /// The deck's title editor; null = the title is plain text.
  final MemoryTitleEditing? titleEditing;

  /// The page is current and the card takes input: its title can open.
  final bool titleEnabled;

  /// Where the card's keyboard lift is told, for the cover the page draws outside it.
  final ValueNotifier<double>? titleLift;

  @override
  Widget build(BuildContext context) {
    final editor = titleEditing?.editor;
    if (editor == null) return _build(context, false);
    final key = memoryTitleKey(MemoryTitleSurface.card, memory);
    return ListenableBuilder(listenable: editor, builder: (context, _) => _build(context, editor.isEditingKey(key)));
  }

  Widget _build(BuildContext context, bool editingTitle) {
    final colors = Theme.of(context).colorScheme;
    final strings = MemoriesStrings.of(context);
    final excerpts = layout.wide ? memory.excerptCandidatesMedium : memory.excerptCandidates;
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final height = constraints.maxHeight;
        final inner = (layout.cardColumn(width) - MemoriesTokens.cardSidePadding * 2).clamp(1.0, double.infinity);
        final titleWidth = inner < layout.titleMaxWidth ? inner : layout.titleMaxWidth;
        final excerptWidth = inner < layout.excerptMaxWidth ? inner : layout.excerptMaxWidth;
        final kind = memory.memoryTitleKind;
        final titleStyle = memoryCardTitleStyleFor(kind, layout, colors.onSurface);
        final albumStyle = memoryCardAlbumStyle(layout, colors.onSurface);
        final written = kind != MemoryTitleKind.album;
        final artistStyle = memoryCardArtistStyle(layout, colors.onSurfaceVariant);
        final artist = memory.artistName ?? '';
        final year = memory.year?.toString();
        final artistLine = artist.isNotEmpty ? artist : (year ?? '');

        final exhibitHeight =
            layout.air1 +
            layout.cover +
            layout.seal * MemoriesTokens.sealOverhangBottom +
            layout.titleTop +
            _measure(memory.memoryTitle, titleStyle, titleWidth, maxLines: memoryCardTitleLines(kind)) +
            6 +
            (written
                ? _albumTop(layout) - 6 + _measure(memory.albumName, albumStyle, titleWidth, maxLines: 2) + 2
                : 0) +
            (artistLine.isEmpty ? 0 : _measure(artistLine, artistStyle, titleWidth, maxLines: 1));
        // The title block's bottom while its row is open: what the keyboard must not cover.
        final titleBlockBottom =
            layout.air1 +
            layout.cover +
            layout.seal * MemoriesTokens.sealOverhangBottom +
            layout.titleTop +
            _measure(
              editingTitle && titleEditing!.editor.draft.text.isNotEmpty
                  ? titleEditing!.editor.draft.text
                  : memory.memoryTitle,
              titleStyle,
              titleWidth,
            ) +
            MemoryTitleTokens.rowHeight;
        final lift = editingTitle
            ? memoryTitleLiftPx(
                blockBottom: titleBlockBottom,
                visibleBottom: height - MediaQuery.viewInsetsOf(context).bottom,
                clearance: MemoryTitleTokens.imeClearance,
              )
            : 0.0;
        final teaserHeight = MemoriesTokens.buttonHeight;
        final bottomPad = layout.teaserBottom > MediaQuery.paddingOf(context).bottom
            ? layout.teaserBottom
            : MediaQuery.paddingOf(context).bottom;
        final slot =
            height - bottomPad - teaserHeight - MemoriesTokens.excerptGap - exhibitHeight - MemoriesTokens.excerptClear;
        double blockHeight(int i) {
          final c = excerpts[i];
          final quote = c.text == null
              ? 0.0
              : _measure(strings.excerptQuote(c.text!), memoryExcerptStyle(c.size, colors.onSurface), excerptWidth) +
                    MemoriesTokens.excerptAttributionGap;
          return quote + 16;
        }

        final pick = pickExcerpt(excerpts.length, (i) => excerpts[i].attributionOnly, slot, blockHeight);

        return AnimatedBuilder(
          animation: diary,
          builder: (context, _) {
            final p = diary.value.clamp(0.0, 1.0);
            final travel = ((p - MemoriesTokens.titleHold) / (1 - MemoriesTokens.titleHold)).clamp(0.0, 1.0);
            final double titleAlpha, rowAlpha, rowLift, teaserAlpha, teaserDrop, excerptAlpha;
            if (reducedMotion) {
              final a = 1 - smoothstep(0, MemoriesTokens.reducedSwap, p);
              titleAlpha = rowAlpha = teaserAlpha = excerptAlpha = a;
              rowLift = teaserDrop = 0;
            } else {
              titleAlpha = 1 - smoothstep(0.36, 0.72, p);
              rowAlpha = 1 - smoothstep(0, MemoriesTokens.albumRowFadeTo, p);
              rowLift = MemoriesTokens.albumRowLift * p;
              teaserAlpha = 1 - smoothstep(0, MemoriesTokens.teaserFadeTo, p);
              excerptAlpha = 1 - smoothstep(0, MemoriesTokens.excerptFadeTo, p);
              teaserDrop = MemoriesTokens.teaserDrop * p;
            }
            final titleLift = reducedMotion ? 0.0 : -travel * 48;
            return MemoryTitleLift(
              lift: lift,
              mirror: this.titleLift,
              child: Stack(
                children: [
                  // Exhibit.
                  Positioned(
                    left: 0,
                    right: 0,
                    top: 0,
                    child: Column(
                      children: [
                        SizedBox(height: layout.air1),
                        SizedBox(
                          width: layout.cover,
                          height: layout.cover + layout.seal * MemoriesTokens.sealOverhangBottom,
                          child: Stack(
                            clipBehavior: Clip.none,
                            children: [
                              Positioned(
                                left: layout.cover - layout.seal * (1 - MemoriesTokens.sealOverhangRight),
                                top: layout.cover - layout.seal * (1 - MemoriesTokens.sealOverhangBottom),
                                width: layout.seal,
                                height: layout.seal,
                                child: emblem,
                              ),
                            ],
                          ),
                        ),
                        SizedBox(height: layout.titleTop),
                        Opacity(
                          opacity: titleAlpha,
                          child: Transform.translate(
                            offset: Offset(0, titleLift),
                            child: SizedBox(
                              width: titleWidth,
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  MemoryTitleSlot(
                                    memory: memory,
                                    surface: MemoryTitleSurface.card,
                                    editing: titleEditing,
                                    tones: tones,
                                    style: (k) => memoryCardTitleStyleFor(k, layout, colors.onSurface),
                                    maxLines: memoryCardTitleLines,
                                    enabled: titleEnabled && interactive && p < 0.05,
                                    centred: true,
                                  ),
                                  MemoryTitleEditRow(
                                    memory: memory,
                                    surface: MemoryTitleSurface.card,
                                    editing: titleEditing,
                                    tones: tones,
                                    centred: true,
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(height: 6),
                        Opacity(
                          opacity: rowAlpha,
                          child: Transform.translate(
                            offset: Offset(0, -rowLift),
                            child: SizedBox(
                              width: titleWidth,
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  // A written title brings the album name in (and out) on the spatial spring.
                                  MemorySpringFold(
                                    visible: written,
                                    child: Padding(
                                      padding: EdgeInsets.only(top: _albumTop(layout) - 6, bottom: 2),
                                      child: Text(
                                        memory.albumName,
                                        style: albumStyle,
                                        textAlign: TextAlign.center,
                                        maxLines: 2,
                                        overflow: TextOverflow.fade,
                                      ),
                                    ),
                                  ),
                                  Center(
                                    child: MetaLine(
                                      centered: true,
                                      style: artistStyle,
                                      color: colors.onSurfaceVariant,
                                      groups: [
                                        if (artist.isNotEmpty) MetaPlain(artist),
                                        if (year != null) MetaMuted(year),
                                      ],
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  // Excerpt.
                  if (pick != null)
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: bottomPad + teaserHeight + MemoriesTokens.excerptGap - teaserDrop,
                      child: Opacity(
                        opacity: excerptAlpha,
                        child: MemorySpringOpacity(
                          // While the title is open the keyboard holds the bottom: the excerpt steps aside.
                          visible: !editingTitle,
                          child: Center(
                            child: SizedBox(
                              width: excerptWidth,
                              child: _Excerpt(candidate: excerpts[pick], strings: strings),
                            ),
                          ),
                        ),
                      ),
                    ),
                  // Teaser.
                  Positioned(
                    left: 0,
                    right: 0,
                    bottom: bottomPad - teaserDrop,
                    child: Opacity(
                      opacity: teaserAlpha,
                      child: MemorySpringOpacity(
                        visible: !editingTitle,
                        child: IgnorePointer(
                          ignoring: !interactive || p > 0.05,
                          child: _Teaser(
                            memory: memory,
                            tones: tones,
                            strings: strings,
                            onOpenDiary: onOpenDiary,
                            onOpenAlbum: onOpenAlbum,
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }
}

class _Excerpt extends StatelessWidget {
  const _Excerpt({required this.candidate, required this.strings});

  final MemoryExcerptCandidate candidate;
  final MemoriesStrings strings;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = candidate.text;
    final detail = candidate.attribution.detail;
    return MergeSemantics(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (text != null) ...[
            Text(
              strings.excerptQuote(text),
              style: memoryExcerptStyle(candidate.size, colors.onSurface),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: MemoriesTokens.excerptAttributionGap),
          ],
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                candidate.attribution.kind == MemoryExcerptKind.review ? strings.reviewYours : strings.noteYours,
                style: _labelSmall(colors.onSurfaceVariant),
                maxLines: 1,
              ),
              if (detail != null && detail.isNotEmpty) ...[
                const SizedBox(width: 12),
                Flexible(
                  child: Text(
                    detail,
                    style: _attributionStyle(colors.onSurface.withValues(alpha: 0.6)),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }
}

class _Teaser extends StatelessWidget {
  const _Teaser({
    required this.memory,
    required this.tones,
    required this.strings,
    required this.onOpenDiary,
    required this.onOpenAlbum,
  });

  final MemoryEntry memory;
  final MemoryPaletteTones tones;
  final MemoriesStrings strings;
  final VoidCallback onOpenDiary;
  final VoidCallback onOpenAlbum;

  @override
  Widget build(BuildContext context) {
    final label = gsf(15, weight: 600, height: 1, letterSpacing: 0);
    final notes = memory.noteCount;
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Semantics(
          button: true,
          label: strings.cdOpenDiary,
          excludeSemantics: true,
          child: _Pill(
            color: tones.ink.withValues(alpha: MemoriesTokens.diaryTonalAlpha),
            padding: const EdgeInsetsDirectional.only(start: 16, end: 20),
            onTap: () {
              YoinHaptics.click();
              onOpenDiary();
            },
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(YoinSymbols.note, size: 18, color: tones.ink),
                const SizedBox(width: 8),
                Text(strings.cardDiary, style: label.copyWith(color: tones.ink), maxLines: 1, softWrap: false),
                if (notes > 0) ...[
                  const SizedBox(width: 6),
                  Container(
                    height: 24,
                    constraints: const BoxConstraints(minWidth: 24),
                    padding: const EdgeInsets.symmetric(horizontal: 6),
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: tones.ink.withValues(alpha: 0.18),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: Text(
                      '$notes',
                      style: gsf(15, weight: 500, height: 1, letterSpacing: 0).copyWith(
                        color: tones.ink.withValues(alpha: 0.78),
                        fontFeatures: const [FontFeature.tabularFigures()],
                      ),
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
        const SizedBox(width: MemoriesTokens.buttonGap),
        _Pill(
          color: tones.button,
          padding: const EdgeInsets.symmetric(horizontal: 24),
          onTap: () {
            YoinHaptics.click();
            onOpenAlbum();
          },
          child: Text(strings.cardGoAlbum, style: label.copyWith(color: tones.onButton), maxLines: 1, softWrap: false),
        ),
      ],
    );
  }
}

/// A flat pill button (48 high), its ripple clipped to the pill.
class _Pill extends StatelessWidget {
  const _Pill({required this.color, required this.padding, required this.onTap, required this.child});

  final Color color;
  final EdgeInsetsGeometry padding;
  final VoidCallback onTap;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: color,
      shape: const StadiumBorder(),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          height: MemoriesTokens.buttonHeight,
          child: Padding(
            padding: padding,
            child: Center(widthFactor: 1, child: child),
          ),
        ),
      ),
    );
  }
}

/// A flat pill button shared by the card and the diary.
class MemoryPillButton extends StatelessWidget {
  const MemoryPillButton({
    super.key,
    required this.label,
    required this.color,
    required this.onColor,
    required this.onTap,
  });

  final String label;
  final Color color;
  final Color onColor;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => _Pill(
    color: color,
    padding: const EdgeInsets.symmetric(horizontal: 24),
    onTap: onTap,
    child: Text(
      label,
      style: gsf(15, weight: 600, height: 1, letterSpacing: 0).copyWith(color: onColor),
      maxLines: 1,
      softWrap: false,
    ),
  );
}
