/// The spread (Android showcase MemoriesSpread, twostate4 `.ts-L-spread`): a
/// wide container lays each memory out as two pages over one ground, no
/// divider and no morph.
///
///  · The LEFT page is the exhibit with everything Yoin wrote, in reading
///    order: the cover with its emblem, the title, Yoin's paragraph and
///    question, the artist line, Go to album. Every card's cover starts at the
///    same y and the whole deck shares one cover size (the height ladder,
///    [fitSpreadDeck]).
///  · The RIGHT page is the diary from your own entry on, then the track rows
///    and the ending. A page that fits sits centred on the left page's optical
///    line; a longer one starts level with the cover and scrolls.
///
/// Gestures: the left page is card body (a vertical drag is Home's); the right
/// page scrolls, and a new upward drag that starts at its end goes Home. Back
/// has one level (Home). Phone landscape opens the right page with Yoin's
/// paragraph instead.
library;

import 'package:flutter/material.dart';

import '../theme/theme.dart';
import '../theme/tokens.dart';
import '../ui/component/cover_art.dart';
import '../ui/component/meta_line.dart';
import 'copy/memory_voice.dart';
import 'memories_layout.dart';
import 'memories_strings.dart';
import 'memories_ui_state.dart';
import 'memory_other_profile_line.dart';
import 'memory_card_face.dart';
import 'memory_title_editor.dart';
import 'palette/memory_palette.dart';

enum SpreadTightness { normal, tight, tight2 }

/// The ladder's spacing steps (SpreadSpacing).
@immutable
final class SpreadSpacing {
  const SpreadSpacing(this.padTop, this.padBottom, this.citeTop, this.paragraphTop, this.albumTop, this.goTop);

  final double padTop;
  final double padBottom;
  final double citeTop;
  final double paragraphTop;
  final double albumTop;
  final double goTop;

  static SpreadSpacing of(SpreadTightness t) => switch (t) {
    SpreadTightness.normal => const SpreadSpacing(24, 56, 40, 14, 18, 30),
    SpreadTightness.tight => const SpreadSpacing(16, 24, 26, 14, 14, 22),
    SpreadTightness.tight2 => const SpreadSpacing(12, 24, 20, 10, 10, 14),
  };
}

/// One cover size and spacing step for the whole deck.
@immutable
final class SpreadFit {
  const SpreadFit({required this.cover, required this.seal, required this.tightness, required this.laddered});

  final double cover;
  final double seal;
  final SpreadTightness tightness;
  final bool laddered;

  SpreadSpacing get spacing => SpreadSpacing.of(tightness);

  /// The cover's top in the page's frame (under the bar).
  double get coverTop => spacing.padTop;
}

TextStyle spreadTitleStyle(SpreadTightness t, Color color) =>
    gsf(t == SpreadTightness.normal ? 27 : 25, weight: 600, height: 1.3, letterSpacing: 0).copyWith(color: color);

/// The left page's citation for its kind (Android spreadTitleStyle): a
/// written title in the serif, 30 (27 when the ladder tightens); the album
/// name 27 / 25 in GSF.
TextStyle spreadTitleStyleFor(MemoryTitleKind kind, SpreadTightness t, Color color) => kind == MemoryTitleKind.album
    ? spreadTitleStyle(t, color)
    : memoryWrittenTitleStyle(t == SpreadTightness.normal ? 30 : 27, height: 1.36, color: color);

/// The album name under a written title (spreadAlbumStyle).
TextStyle spreadAlbumStyle(SpreadTightness t, Color color) => gsf(
  t == SpreadTightness.normal ? MemoryTitleTokens.spreadAlbum : MemoryTitleTokens.spreadAlbumTight,
  weight: 600,
  height: 1.3,
  letterSpacing: 0,
).copyWith(color: color);

double spreadParagraphSize(SpreadTightness t) => t == SpreadTightness.tight2 ? 16 : 17;

TextStyle _artistStyle(Color color) => gsf(14, weight: 500, height: 1.35, letterSpacing: 0).copyWith(color: color);

double _textHeight(String text, TextStyle style, double width, {int? maxLines}) {
  final p = TextPainter(
    text: TextSpan(text: text, style: style),
    textDirection: TextDirection.ltr,
    textAlign: TextAlign.center,
    maxLines: maxLines,
  )..layout(maxWidth: width);
  final h = p.height;
  p.dispose();
  return h;
}

/// The left page's height for [memory] at a cover / emblem / step (the exhibit's extent, no pads).
double spreadExtent(MemoryEntry memory, MemoriesLayout layout, double cover, double seal, SpreadTightness t) {
  final s = SpreadSpacing.of(t);
  final content = layout.leftPage - MemoriesTokens.spreadPagePadding * 2;
  final cite = content < MemoriesTokens.spreadCiteMax ? content : MemoriesTokens.spreadCiteMax;
  final album = content < MemoriesTokens.spreadAlbumMax ? content : MemoriesTokens.spreadAlbumMax;
  final paragraph = layout.landscape
      ? null
      : MemoryVoice.paragraph(memory.yoinNarration, memory.yoinQuestion, memory.proseLanguage);
  final artist = memory.artistName ?? memory.year?.toString() ?? '';
  final kind = memory.memoryTitleKind;
  final written = kind != MemoryTitleKind.album;
  return cover +
      seal * MemoriesTokens.sealOverhangBottom +
      s.citeTop +
      _textHeight(
        memory.memoryTitle,
        spreadTitleStyleFor(kind, t, const Color(0xFF000000)),
        cite,
        maxLines: memoryCardTitleLines(kind),
      ) +
      (written
          ? s.albumTop + _textHeight(memory.albumName, spreadAlbumStyle(t, const Color(0xFF000000)), album, maxLines: 2)
          : 0) +
      (paragraph == null
          ? 0
          : s.paragraphTop +
                _textHeight(paragraph, gsf(spreadParagraphSize(t), height: 1.6, letterSpacing: 0, round: 60), cite)) +
      (written ? 2 : (t == SpreadTightness.normal ? 8 : s.albumTop)) +
      (artist.isEmpty ? 0 : _textHeight(artist, _artistStyle(const Color(0xFF000000)), album, maxLines: 1)) +
      s.goTop +
      MemoriesTokens.spreadGoHeight;
}

/// The height ladder for the whole deck (fitSpreadCover): spacing tightens
/// first, then the cover steps down to the floor, then the last spacing step.
/// [room] is the left page's height under the bar.
SpreadFit fitSpreadDeck(List<MemoryEntry> memories, MemoriesLayout layout, double room, {double navBottom = 0}) {
  double need(double cover, SpreadTightness t, bool laddered) {
    final s = SpreadSpacing.of(t);
    final seal = memoriesSpreadSealFor(cover, landscape: layout.landscape, laddered: laddered);
    var extent = 0.0;
    for (final m in memories) {
      final e = spreadExtent(m, layout, cover, seal, t);
      if (e > extent) extent = e;
    }
    return s.padTop + extent + (s.padBottom > navBottom ? s.padBottom : navBottom);
  }

  var cover = layout.cover;
  var t = SpreadTightness.normal;
  var laddered = false;
  if (need(cover, t, false) > room) t = SpreadTightness.tight;
  while (need(cover, t, laddered) > room && cover > layout.minCover) {
    cover = (cover - MemoriesTokens.spreadLadderStep).clamp(layout.minCover, cover);
    laddered = true;
  }
  if (need(cover, t, laddered) > room) t = SpreadTightness.tight2;
  return SpreadFit(
    cover: cover,
    seal: memoriesSpreadSealFor(cover, landscape: layout.landscape, laddered: laddered),
    tightness: t,
    laddered: laddered,
  );
}

/// The left page: the exhibit and Yoin's words, centred; every card's cover at the deck's one y.
class MemorySpreadExhibit extends StatelessWidget {
  const MemorySpreadExhibit({
    super.key,
    required this.memory,
    required this.tones,
    required this.layout,
    required this.fit,
    required this.emblem,
    required this.onOpenAlbum,
    this.titleEditing,
    this.titleEnabled = false,
  });

  final MemoryEntry memory;
  final MemoryPaletteTones tones;
  final MemoriesLayout layout;
  final SpreadFit fit;
  final Widget emblem;
  final VoidCallback onOpenAlbum;

  /// The deck's title editor; null = the title is plain text.
  final MemoryTitleEditing? titleEditing;

  /// The page is current: its title can open.
  final bool titleEnabled;

  @override
  Widget build(BuildContext context) {
    final editor = titleEditing?.editor;
    if (editor == null) return _build(context, false);
    final key = memoryTitleKey(MemoryTitleSurface.spread, memory);
    return ListenableBuilder(listenable: editor, builder: (context, _) => _build(context, editor.isEditingKey(key)));
  }

  Widget _build(BuildContext context, bool editingTitle) {
    final colors = Theme.of(context).colorScheme;
    final strings = MemoriesStrings.of(context);
    final s = fit.spacing;
    final kind = memory.memoryTitleKind;
    final written = kind != MemoryTitleKind.album;
    final paragraph = layout.landscape
        ? null
        : MemoryVoice.paragraph(memory.yoinNarration, memory.yoinQuestion, memory.proseLanguage);
    final zh = memory.proseLanguage == MemoryProseLanguage.zh;
    final size = spreadParagraphSize(fit.tightness);
    final artist = memory.artistName ?? '';
    final year = memory.year?.toString();
    // The left page doesn't scroll: while its title is open it rises over the keyboard.
    return LayoutBuilder(
      builder: (context, constraints) {
        var lift = 0.0;
        if (editingTitle) {
          final content = layout.leftPage - MemoriesTokens.spreadPagePadding * 2;
          final cite = content < MemoriesTokens.spreadCiteMax ? content : MemoriesTokens.spreadCiteMax;
          final draft = titleEditing!.editor.draft.text;
          lift = memoryTitleLiftPx(
            blockBottom:
                s.padTop +
                fit.cover +
                fit.seal * MemoriesTokens.sealOverhangBottom +
                s.citeTop +
                _textHeight(
                  draft.isEmpty ? memory.memoryTitle : draft,
                  spreadTitleStyleFor(kind, fit.tightness, colors.onSurface),
                  cite,
                ) +
                MemoryTitleTokens.rowHeight,
            visibleBottom: constraints.maxHeight - MediaQuery.viewInsetsOf(context).bottom,
            clearance: MemoryTitleTokens.imeClearance,
          );
        }
        return MemoryTitleLift(lift: lift, child: _page(context, strings, paragraph, zh, size, artist, year, written));
      },
    );
  }

  Widget _page(
    BuildContext context,
    MemoriesStrings strings,
    String? paragraph,
    bool zh,
    double size,
    String artist,
    String? year,
    bool written,
  ) {
    final colors = Theme.of(context).colorScheme;
    final s = fit.spacing;
    return Padding(
      padding: EdgeInsets.only(
        top: s.padTop,
        left: MemoriesTokens.spreadPagePadding,
        right: MemoriesTokens.spreadPagePadding,
      ),
      child: Column(
        children: [
          SizedBox(
            width: fit.cover,
            height: fit.cover + fit.seal * MemoriesTokens.sealOverhangBottom,
            child: Stack(
              clipBehavior: Clip.none,
              children: [
                CoverArt(
                  cover: memory.coverArt,
                  size: fit.cover,
                  radius: MemoriesTokens.coverCornerCard,
                  requestSize: 640,
                ),
                Positioned(
                  left: fit.cover - fit.seal * (1 - MemoriesTokens.sealOverhangRight),
                  top: fit.cover - fit.seal * (1 - MemoriesTokens.sealOverhangBottom),
                  width: fit.seal,
                  height: fit.seal,
                  child: emblem,
                ),
              ],
            ),
          ),
          SizedBox(height: s.citeTop),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: MemoriesTokens.spreadCiteMax),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                MemoryTitleSlot(
                  memory: memory,
                  surface: MemoryTitleSurface.spread,
                  editing: titleEditing,
                  tones: tones,
                  style: (k) => spreadTitleStyleFor(k, fit.tightness, colors.onSurface),
                  maxLines: memoryCardTitleLines,
                  enabled: titleEnabled,
                  centred: true,
                ),
                MemoryTitleEditRow(
                  memory: memory,
                  surface: MemoryTitleSurface.spread,
                  editing: titleEditing,
                  tones: tones,
                  centred: true,
                ),
              ],
            ),
          ),
          if (paragraph != null) ...[
            SizedBox(height: s.paragraphTop),
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: MemoriesTokens.spreadCiteMax),
              child: Text.rich(
                TextSpan(
                  style: gsf(size, height: 1.6, letterSpacing: 0, round: 60).copyWith(color: colors.onSurfaceVariant),
                  children: [
                    if (memory.yoinNarration != null) TextSpan(text: memory.yoinNarration),
                    if (memory.yoinNarration != null && memory.yoinQuestion != null && !zh) const TextSpan(text: ' '),
                    if (memory.yoinQuestion != null)
                      TextSpan(
                        text: memory.yoinQuestion,
                        style: gsf(
                          size,
                          weight: 500,
                          height: 1.6,
                          letterSpacing: 0,
                          round: 60,
                        ).copyWith(color: colors.onSurface),
                      ),
                  ],
                ),
                textAlign: TextAlign.center,
              ),
            ),
          ],
          // A written title brings the album name in (and out) on the spatial spring.
          MemorySpringFold(
            visible: written,
            child: Padding(
              padding: EdgeInsets.only(top: s.albumTop),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: MemoriesTokens.spreadAlbumMax),
                child: Text(
                  memory.albumName,
                  style: spreadAlbumStyle(fit.tightness, colors.onSurface),
                  textAlign: TextAlign.center,
                  maxLines: 2,
                  overflow: TextOverflow.fade,
                ),
              ),
            ),
          ),
          SizedBox(height: written ? 2 : (fit.tightness == SpreadTightness.normal ? 8 : s.albumTop)),
          ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: MemoriesTokens.spreadAlbumMax),
            child: MetaLine(
              centered: true,
              style: _artistStyle(colors.onSurfaceVariant),
              color: colors.onSurfaceVariant,
              groups: [if (artist.isNotEmpty) MetaPlain(artist), if (year != null) MetaMuted(year)],
            ),
          ),
          SizedBox(height: s.goTop),
          // Another account's memory: whose it is, beside Go to album (which switches there).
          Row(
            mainAxisSize: MainAxisSize.min,
            spacing: OtherProfileTokens.lineGoGap,
            children: [
              MemoryPillButton(
                label: strings.cardGoAlbum, // mem_spread_go_album (same words)
                color: tones.button,
                onColor: tones.onButton,
                onTap: onOpenAlbum,
              ),
              if (memory.otherProfile case final owner?) MemoryOtherProfileLine(owner: owner, tones: tones),
            ],
          ),
        ],
      ),
    );
  }
}
