/// The share images (Android ShareCards.kt, owner 2026-11-25; prototypes
/// design/hifi/src/components/share.js, measurements from there). Every image
/// is laid out at its logical size — Card 360 wide (exported 1440 px, height
/// following the content), Story 360 × 640 (exported 1080 × 1920) — at text
/// scale 1, and exported as a full rectangle: the Card is its own colour to
/// every edge, square-cornered, its height rounded up to whole export pixels
/// (filled); a Story's backdrop fills the corners around its card, or the
/// layout itself is the poster. Same family as the lyrics card: 24 padding,
/// the 44 cover + title / artist head, the Yoin mark at the foot, GSF rounded
/// SemiBold for hero text.
library;

import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/ai/ai_settings.dart';
import '../notes/note_markup_text.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import '../ui/component/spring_cross_fade.dart';
import 'about_widgets.dart' show DashedRule, markdownBoldSpan;
import 'lyrics_share.dart';
import 'nowplaying_strings.dart';
import 'share_content.dart';

/// How a layout's body is framed: the Card format, a card on a Story's backdrop, or a whole Story poster.
enum ShareFrame { card, storyCard, poster }

/// One share image at its logical size in [format] and [tone], text unscaled.
/// [animateTone]: a tone change cross-fades the whole image (colour is an
/// effect); the sheet's preview fades outside its export boundary instead, so
/// a PNG never catches a fade.
class ShareImage extends StatelessWidget {
  const ShareImage({
    super.key,
    required this.content,
    required this.layout,
    required this.format,
    required this.tone,
    this.animateTone = true,
  });

  final ShareContent content;
  final ShareLayout layout;
  final ShareFormat format;
  final ShareTone tone;
  final bool animateTone;

  @override
  Widget build(BuildContext context) {
    final colors = shareToneColors(tone, layout.tab.role, Theme.of(context).colorScheme);
    final Widget body = switch (format) {
      ShareFormat.card => SnapToExportPixels(
        pixelRatio: sharePixelRatio(ShareFormat.card),
        child: SizedBox(
          width: ShareCardMetrics.cardWidth,
          child: ShareBody(content: content, layout: layout, colors: colors, frame: ShareFrame.card),
        ),
      ),
      ShareFormat.story => SizedBox.fromSize(
        size: ShareCardMetrics.storySize,
        child: _ShareStory(content: content, layout: layout, colors: colors),
      ),
    };
    return MediaQuery(
      data: MediaQuery.of(context).copyWith(textScaler: TextScaler.noScaling),
      // The image's own text defaults, wherever it is drawn (a thumbnail, a test without a Material).
      child: DefaultTextStyle(
        style: Theme.of(context).textTheme.bodyMedium!,
        child: KeyedSubtree(
          key: const ValueKey('share-image'),
          child: animateTone ? SpringCrossFade(childKey: tone, fit: StackFit.loose, child: body) : body,
        ),
      ),
    );
  }
}

/// The 9:16 story: a poster edge to edge, or the card centred on the tone's backdrop, zoomed to fit.
class _ShareStory extends StatelessWidget {
  const _ShareStory({required this.content, required this.layout, required this.colors});

  final ShareContent content;
  final ShareLayout layout;
  final ShareCardColors colors;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final poster = layout.storyIsPoster;
    final paper = layout == ShareLayout.noteClipping;
    final ground = poster ? (paper ? scheme.surface : colors.card) : colors.backdrop;
    Widget frame = Padding(
      padding: EdgeInsets.symmetric(
        horizontal: YoinShareCards.storyMarginHorizontalDp.toDouble(),
        vertical: YoinShareCards.storyMarginVerticalDp.toDouble(),
      ),
      child: Center(
        // A long note or pick zooms out rather than ever losing a line; a poster's foot sits at the bottom.
        child: ShareZoomToFit(
          maxZoom: YoinShareCards.storyMaxZoom(layout),
          fillHeight: poster,
          child: ShareBody(
            content: content,
            layout: layout,
            colors: colors,
            frame: poster ? ShareFrame.poster : ShareFrame.storyCard,
          ),
        ),
      ),
    );
    if (paper) frame = CustomPaint(painter: _DotPaperPainter(scheme.outlineVariant), child: frame);
    return ColoredBox(color: ground, child: frame);
  }
}

/// One layout's body in [frame].
class ShareBody extends StatelessWidget {
  const ShareBody({super.key, required this.content, required this.layout, required this.colors, required this.frame});

  final ShareContent content;
  final ShareLayout layout;
  final ShareCardColors colors;
  final ShareFrame frame;

  @override
  Widget build(BuildContext context) {
    final lyrics = content.lyrics;
    return switch (layout) {
      ShareLayout.lyricsClassic =>
        lyrics == null
            ? const SizedBox.shrink()
            : LyricsShareCard(
                lines: lyrics.lines,
                passageStarts: lyrics.passageStarts.toSet(),
                showTranslation: lyrics.showTranslation,
                songTitle: content.song.title,
                artist: content.song.artist,
                coverUrl: content.song.coverArtUrl,
                colors: colors,
                radius: frame == ShareFrame.storyCard ? ShapeTokens.panel : 0,
              ),
      ShareLayout.lyricsPoster => _LyricsPoster(content, colors, frame),
      ShareLayout.lyricsPlayer => _LyricsPlayer(content, colors, frame),
      ShareLayout.lyricsVertical => _LyricsVertical(content, colors, frame),
      ShareLayout.aboutReceipt => _AboutReceipt(content, colors, frame),
      ShareLayout.aboutQuote => _AboutQuote(content, colors, frame),
      ShareLayout.aboutLiner => _AboutLiner(content, colors, frame),
      ShareLayout.noteBig => _NoteBig(content, colors, frame),
      ShareLayout.noteClipping => _NoteClipping(content, colors, frame),
      ShareLayout.noteTimeline => _NoteTimeline(content, colors, frame),
    };
  }
}

/// The body's own ground: the card colour edge to edge (Card), a Panel-rounded card (Story), none (poster).
Widget _frameGround(ShareFrame frame, Color card, Widget child, {double padding = ShareCardMetrics.cardPadding}) =>
    switch (frame) {
      ShareFrame.card => ColoredBox(
        color: card,
        child: Padding(padding: EdgeInsets.all(padding), child: child),
      ),
      ShareFrame.storyCard => DecoratedBox(
        decoration: ShapeDecoration(
          color: card,
          shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.panel)),
        ),
        child: Padding(padding: EdgeInsets.all(padding), child: child),
      ),
      ShareFrame.poster => child,
    };

// ---------------------------------------------------------------------------
// Shared pieces.
// ---------------------------------------------------------------------------

/// Cover + title + artist, as on the lyrics card.
class _ShareHead extends StatelessWidget {
  const _ShareHead(this.song, this.colors, {this.coverSize = ShareCardMetrics.cover});

  final ShareSong song;
  final ShareCardColors colors;
  final double coverSize;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Row(
      children: [
        ShareCover(url: song.coverArtUrl, size: coverSize, tint: colors.content),
        const SizedBox(width: ShareCardMetrics.coverGap),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                song.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: shareWeight(text.titleSmall!, 600).copyWith(color: colors.content),
              ),
              if (song.artist.trim().isNotEmpty)
                Text(
                  song.artist,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: text.bodySmall!.copyWith(color: colors.secondary),
                ),
            ],
          ),
        ),
      ],
    );
  }
}

/// The mark + "Yoin", then, at the end, who wrote the words ("由 Gemini 整理", "写于 …").
class _ShareFoot extends StatelessWidget {
  const _ShareFoot(this.colors, {this.sign, this.ink});

  final ShareCardColors colors;
  final Widget? sign;
  final Color? ink;

  @override
  Widget build(BuildContext context) {
    final sign = this.sign;
    return Row(
      children: [
        ShareBrand(color: ink ?? colors.content),
        if (sign == null)
          const Spacer()
        else ...[
          // The sign keeps its distance from the mark and wraps (right-aligned) rather than ever being cut:
          // a story zoomed in lays the card out narrower.
          const SizedBox(width: ShareCardMetrics.signGap),
          Expanded(
            child: Align(alignment: AlignmentDirectional.centerEnd, child: sign),
          ),
        ],
      ],
    );
  }
}

/// About's sign when the image carries AI words; none when it is the service's credits alone.
Widget? _aiSignOrNull(ShareContent content, ShareCardColors colors) =>
    content.about?.aiContent == true ? _AiSign(content.aiProvider, colors) : null;

/// "✦ 由 Gemini 整理": the active AI service signs About's words.
class _AiSign extends StatelessWidget {
  const _AiSign(this.provider, this.colors);

  final AiProvider provider;
  final ShareCardColors colors;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    return Row(
      key: const ValueKey('share-sign'),
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(YoinSymbols.sparkle, size: 14, color: colors.secondary),
        const SizedBox(width: 4),
        Flexible(
          child: Text(
            strings.shareAiSign(provider),
            textAlign: TextAlign.end,
            style: shareWeight(Theme.of(context).textTheme.labelMedium!, 400).copyWith(color: colors.secondary),
          ),
        ),
      ],
    );
  }
}

/// "写于 2026年7月18日": the note's date signs the listener's words.
class _NoteSign extends StatelessWidget {
  const _NoteSign(this.note, this.color);

  final ShareNote note;
  final Color color;

  @override
  Widget build(BuildContext context) => Text(
    NowPlayingStrings.of(
      context,
    ).shareNoteWritten(shareNoteDate(note.createdAt, Localizations.maybeLocaleOf(context) ?? const Locale('en'))),
    key: const ValueKey('share-sign'),
    textAlign: TextAlign.end,
    style: shareWeight(Theme.of(context).textTheme.labelMedium!, 400).copyWith(color: color),
  );
}

/// ▶ 3:46 (and an optional end, quieter) on a 12% pill of the ink.
class _ShareTimeChip extends StatelessWidget {
  const _ShareTimeChip(this.text, this.colors, {this.end});

  final String text;
  final ShareCardColors colors;
  final String? end;

  @override
  Widget build(BuildContext context) {
    final end = this.end;
    return Container(
      height: 26,
      padding: const EdgeInsetsDirectional.only(start: 8, end: 10),
      decoration: BoxDecoration(color: colors.content.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(13)),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(YoinSymbols.playFilled, size: 12, color: colors.content),
          const SizedBox(width: 4),
          Text(text, maxLines: 1, softWrap: false, style: gsf(13, weight: 600).copyWith(color: colors.content)),
          if (end != null)
            Text(
              ' – $end',
              maxLines: 1,
              softWrap: false,
              style: gsf(13, weight: 500).copyWith(color: colors.content.withValues(alpha: 0.6)),
            ),
        ],
      ),
    );
  }
}

/// The note's Markdown in [style]: mentions as pills of the ink with their covers, no touch.
class _ShareNoteText extends StatelessWidget {
  const _ShareNoteText(this.note, this.style, this.color);

  final ShareNote note;
  final TextStyle style;
  final Color color;

  @override
  Widget build(BuildContext context) => NoteMarkupText(
    note.content,
    style: style.copyWith(color: color),
    mentionColor: color,
    interactive: false,
  );
}

TextStyle _endsStyle(Color ink) => gsf(11, weight: 500).copyWith(color: ink.withValues(alpha: 0.6));

/// The 0:00 / length ends under a bar.
class _Ends extends StatelessWidget {
  const _Ends(this.durationMs, this.ink);

  final int durationMs;
  final Color ink;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Text(shareTime(0), style: _endsStyle(ink)),
      const Spacer(),
      Text(shareTime(durationMs), style: _endsStyle(ink)),
    ],
  );
}

/// Places a child so its centre sits at [fraction] of the parent's width, kept inside it.
class _CentredAt extends SingleChildLayoutDelegate {
  const _CentredAt(this.fraction);

  final double fraction;

  @override
  // The chip keeps its one line whatever the width (Compose's softWrap = false); it is kept inside when it fits.
  BoxConstraints getConstraintsForChild(BoxConstraints constraints) => BoxConstraints(maxHeight: constraints.maxHeight);

  @override
  Offset getPositionForChild(Size size, Size childSize) {
    final x = (size.width * fraction - childSize.width / 2)
        .clamp(0.0, math.max(0.0, size.width - childSize.width))
        .toDouble();
    return Offset(x, 0);
  }

  @override
  bool shouldRelayout(_CentredAt old) => old.fraction != fraction;
}

Widget _chipAt(double fraction, Widget chip) => SizedBox(
  height: 34,
  width: double.infinity,
  child: CustomSingleChildLayout(delegate: _CentredAt(fraction), child: chip),
);

// ---------------------------------------------------------------------------
// Lyrics: Poster, Player, Vertical (Classic is LyricsShareCard).
// ---------------------------------------------------------------------------

class _LyricsPoster extends StatelessWidget {
  const _LyricsPoster(this.content, this.colors, this.frame);

  final ShareContent content;
  final ShareCardColors colors;
  final ShareFrame frame;

  @override
  Widget build(BuildContext context) {
    final pick = content.lyrics;
    final poster = pick == null ? null : YoinShareCards.poster(pick);
    if (poster == null) return const SizedBox.shrink();
    final text = Theme.of(context).textTheme;
    final story = frame == ShareFrame.poster;
    final lead = poster.leadSizeSp + (story ? YoinShareCards.posterStoryLeadBoostSp : 0);
    final by = [if (content.song.artist.trim().isNotEmpty) content.song.artist, ?poster.leadTime].join(' · ');
    Widget body = Column(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              height: 44,
              child: OverflowBox(
                alignment: AlignmentDirectional.topStart,
                maxHeight: double.infinity,
                child: Text(
                  '“',
                  style: gsf(72, weight: 700, height: 56 / 72).copyWith(color: colors.content.withValues(alpha: 0.35)),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text(poster.lead.text, style: shareHeroStyle(lead, 1.18).copyWith(color: colors.content)),
            ),
            for (final (i, line) in poster.rest.indexed)
              Padding(
                padding: EdgeInsets.only(top: i == 0 ? 16 : 4),
                child: Text(
                  line.text,
                  style: shareHeroStyle(
                    story ? 20 : 18,
                    1.35,
                    500,
                  ).copyWith(color: colors.content.withValues(alpha: 0.82)),
                ),
              ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.only(top: 28),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  ShareCover(url: content.song.coverArtUrl, size: ShareCardMetrics.cover, tint: colors.content),
                  const SizedBox(width: ShareCardMetrics.coverGap),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          content.song.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: shareWeight(text.titleSmall!, 600).copyWith(color: colors.content),
                        ),
                        Text(
                          by,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: text.bodySmall!.copyWith(color: colors.secondary),
                        ),
                      ],
                    ),
                  ),
                  ShareMark(color: colors.content),
                ],
              ),
              // The translation: one small, quiet line at the very bottom (owner 2026-11-25).
              if (poster.translation != null)
                Padding(
                  padding: const EdgeInsets.only(top: 14),
                  child: Text(
                    poster.translation!,
                    style: text.bodySmall!.copyWith(
                      color: colors.content.withValues(alpha: ShareCardMetrics.quietAlpha),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ],
    );
    if (frame == ShareFrame.card) {
      body = ConstrainedBox(
        constraints: const BoxConstraints(
          minHeight: ShareCardMetrics.posterCardMinHeight - ShareCardMetrics.posterPadding * 2,
        ),
        child: body,
      );
    }
    return _frameGround(frame, colors.card, body, padding: ShareCardMetrics.posterPadding);
  }
}

class _LyricsPlayer extends StatelessWidget {
  const _LyricsPlayer(this.content, this.colors, this.frame);

  final ShareContent content;
  final ShareCardColors colors;
  final ShareFrame frame;

  @override
  Widget build(BuildContext context) {
    final pick = content.lyrics;
    if (pick == null) return const SizedBox.shrink();
    final excerpt = YoinShareCards.playerExcerpt(pick, content.song.durationMs);
    final lineStyle = shareHeroStyle(YoinShareCards.cardLineSizeSp([for (final l in pick.lines) l.text]));
    final translation = Theme.of(context).textTheme.bodyMedium!;
    return _frameGround(
      frame,
      colors.card,
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ShareHead(content.song, colors),
          const SizedBox(height: 20),
          // The lines alone — the per-line times are gone (owner 2026-11-25); the bar says where they are.
          for (final (i, line) in pick.lines.indexed)
            Padding(
              padding: EdgeInsets.only(top: i == 0 ? 0 : 6),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(line.text, style: lineStyle.copyWith(color: colors.content)),
                  if (pick.showTranslation && (line.translation?.trim().isNotEmpty ?? false))
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text(
                        line.translation!,
                        style: shareWeight(
                          translation,
                          500,
                        ).copyWith(color: colors.content.withValues(alpha: ShareCardMetrics.translationAlpha)),
                      ),
                    ),
                ],
              ),
            ),
          if (excerpt != null) ...[const SizedBox(height: 18), _PlayerBar(excerpt, content.song.durationMs, colors)],
          const SizedBox(height: 24),
          _ShareFoot(colors),
        ],
      ),
    );
  }
}

/// The track line, the excerpt as a solid bar (≥ 6% of it), its time range in a chip above, 0:00 / length.
class _PlayerBar extends StatelessWidget {
  const _PlayerBar(this.excerpt, this.durationMs, this.colors);

  final ShareExcerpt excerpt;
  final int durationMs;
  final ShareCardColors colors;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      _chipAt(excerpt.centre, _ShareTimeChip(shareTime(excerpt.startMs), colors, end: shareTime(excerpt.endMs))),
      SizedBox(height: 8, child: CustomPaint(painter: _ExcerptBarPainter(excerpt, colors.content))),
      Padding(padding: const EdgeInsets.only(top: 6), child: _Ends(durationMs, colors.content)),
    ],
  );
}

class _ExcerptBarPainter extends CustomPainter {
  _ExcerptBarPainter(this.excerpt, this.ink);

  final ShareExcerpt excerpt;
  final Color ink;

  @override
  void paint(Canvas canvas, Size size) {
    const track = 4.0;
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(0, (size.height - track) / 2, size.width, track),
        const Radius.circular(track / 2),
      ),
      Paint()..color = ink.withValues(alpha: 0.24),
    );
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(size.width * excerpt.start, 0, size.width * (excerpt.end - excerpt.start), size.height),
        Radius.circular(size.height / 2),
      ),
      Paint()..color = ink,
    );
  }

  @override
  bool shouldRepaint(_ExcerptBarPainter old) => old.ink != ink || old.excerpt != excerpt;
}

class _LyricsVertical extends StatelessWidget {
  const _LyricsVertical(this.content, this.colors, this.frame);

  final ShareContent content;
  final ShareCardColors colors;
  final ShareFrame frame;

  @override
  Widget build(BuildContext context) {
    final pick = content.lyrics;
    if (pick == null) return const SizedBox.shrink();
    final strings = NowPlayingStrings.of(context);
    final translations = YoinShareCards.verticalGist(pick);
    final plan = YoinShareCards.verticalLyricsPlan(pick);
    const columnHeight = YoinShareCards.verticalColumnHeightDp;
    Widget body = Column(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 「歌词大意：……」 — the translation as a footnote, the far left column, small and quiet.
            if (translations.isNotEmpty) ...[
              _VerticalText(
                strings.shareLyricsGist(translations.join(YoinShareCards.verticalGistSeparator)),
                sizeSp: 11,
                color: colors.content.withValues(alpha: ShareCardMetrics.quietAlpha),
                columnHeight: columnHeight,
                weight: 400,
              ),
              const SizedBox(width: 10),
            ],
            // Read right to left: the title, then the artist to its left.
            if (content.song.artist.trim().isNotEmpty) ...[
              _VerticalText(
                content.song.artist,
                sizeSp: 12,
                color: colors.secondary,
                columnHeight: columnHeight,
                weight: 400,
              ),
              const SizedBox(width: 4),
            ],
            _VerticalText(
              content.song.title,
              sizeSp: 14,
              color: colors.content,
              columnHeight: columnHeight,
              weight: 600,
            ),
            const Spacer(),
            // Right to left: the first line is the rightmost column.
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              spacing: plan.fontSizeSp * (YoinShareCards.verticalColumnAdvance - 1),
              children: [
                for (final column in plan.columns.reversed)
                  _VerticalColumn(column, plan.fontSizeSp, colors.content, 600, rounded: true),
              ],
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.only(top: 20),
          child: Row(
            children: [
              ShareCover(url: content.song.coverArtUrl, size: 36, tint: colors.content),
              const Spacer(),
              ShareBrand(color: colors.content),
            ],
          ),
        ),
      ],
    );
    if (frame == ShareFrame.card) {
      body = ConstrainedBox(
        constraints: const BoxConstraints(
          minHeight: ShareCardMetrics.verticalCardMinHeight - ShareCardMetrics.posterPadding * 2,
        ),
        child: body,
      );
    }
    return _frameGround(frame, colors.card, body, padding: ShareCardMetrics.posterPadding);
  }
}

/// Short text set vertically: CJK stacked character by character, anything else turned on its side.
class _VerticalText extends StatelessWidget {
  const _VerticalText(
    this.text, {
    required this.sizeSp,
    required this.color,
    required this.columnHeight,
    required this.weight,
  });

  final String text;
  final double sizeSp;
  final Color color;
  final double columnHeight;
  final int weight;

  @override
  Widget build(BuildContext context) {
    final columns = YoinShareCards.verticalTextColumns(text, sizeSp, columnHeight);
    if (columns != null) {
      return Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: sizeSp * 0.3,
        children: [for (final c in columns.reversed) _VerticalColumn(c, sizeSp, color, weight, rounded: false)],
      );
    }
    return RotatedBox(
      quarterTurns: 1,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: columnHeight),
        child: Text(
          text,
          style: gsf(sizeSp, weight: weight).copyWith(color: color),
        ),
      ),
    );
  }
}

/// One column of characters, top to bottom, CJK punctuation in its vertical form.
class _VerticalColumn extends StatelessWidget {
  const _VerticalColumn(this.text, this.sizeSp, this.color, this.weight, {required this.rounded});

  final String text;
  final double sizeSp;
  final Color color;
  final int weight;
  final bool rounded;

  @override
  Widget build(BuildContext context) {
    final advance = YoinShareCards.verticalCharAdvance;
    final style = (rounded ? shareHeroStyle(sizeSp, advance, weight) : gsf(sizeSp, weight: weight, height: advance))
        .copyWith(color: color);
    return SizedBox(
      width: sizeSp * 1.1,
      child: Column(
        children: [
          for (var i = 0; i < text.length; i++)
            Text(
              String.fromCharCode(YoinShareCards.verticalForm(text.codeUnitAt(i))),
              maxLines: 1,
              softWrap: false,
              overflow: TextOverflow.visible,
              textAlign: TextAlign.center,
              style: style,
            ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// About: Receipt, Quote, Liner notes (tertiary).
// ---------------------------------------------------------------------------

class _AboutReceipt extends StatelessWidget {
  const _AboutReceipt(this.content, this.colors, this.frame);

  final ShareContent content;
  final ShareCardColors colors;
  final ShareFrame frame;

  @override
  Widget build(BuildContext context) {
    final about = content.about;
    if (about == null) return const SizedBox.shrink();
    final text = Theme.of(context).textTheme;
    final paragraph = about.paragraph;
    return _frameGround(
      frame,
      colors.card,
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ShareHead(content.song, colors),
          if (about.credits.isNotEmpty) ...[
            const SizedBox(height: 12),
            for (final (i, credit) in about.credits.indexed) ...[
              if (i > 0) DashedRule(color: colors.content.withValues(alpha: 0.3)),
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(
                      width: 88,
                      child: Padding(
                        padding: const EdgeInsetsDirectional.only(top: 2, end: 12),
                        child: Text(credit.label, style: text.labelMedium!.copyWith(color: colors.secondary)),
                      ),
                    ),
                    Expanded(
                      child: Text.rich(
                        markdownBoldSpan(credit.value),
                        textAlign: TextAlign.end,
                        style: shareWeight(text.bodyMedium!, 500).copyWith(color: colors.content),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ],
          if (paragraph != null) ...[
            Padding(
              padding: const EdgeInsets.only(top: 16),
              child: Text(about.heading, style: shareWeight(text.titleSmall!, 600).copyWith(color: colors.content)),
            ),
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text.rich(
                markdownBoldSpan(paragraph),
                style: text.bodyMedium!.copyWith(height: 22 / (text.bodyMedium!.fontSize ?? 14), color: colors.content),
              ),
            ),
          ],
          const SizedBox(height: 24),
          _ShareFoot(colors, sign: _aiSignOrNull(content, colors)),
        ],
      ),
    );
  }
}

class _AboutQuote extends StatelessWidget {
  const _AboutQuote(this.content, this.colors, this.frame);

  final ShareContent content;
  final ShareCardColors colors;
  final ShareFrame frame;

  @override
  Widget build(BuildContext context) {
    final about = content.about;
    if (about == null) return const SizedBox.shrink();
    final text = Theme.of(context).textTheme;
    final paragraph = about.paragraph;
    return _frameGround(
      frame,
      colors.card,
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // The tag: ✦ + the paragraph's heading, inverted.
          Container(
            height: 28,
            padding: const EdgeInsetsDirectional.only(start: 10, end: 12),
            decoration: BoxDecoration(color: colors.content, borderRadius: BorderRadius.circular(14)),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(YoinSymbols.sparkle, size: 14, color: colors.card),
                const SizedBox(width: 5),
                Flexible(
                  child: Text(
                    about.heading,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: shareWeight(text.labelLarge!, 600).copyWith(color: colors.card),
                  ),
                ),
              ],
            ),
          ),
          if (paragraph != null)
            Padding(
              padding: const EdgeInsets.only(top: 18),
              child: Text.rich(
                markdownBoldSpan(paragraph),
                style: shareHeroStyle(19, 1.5).copyWith(color: colors.content),
              ),
            ),
          if (about.credits.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 18),
              child: Row(
                spacing: 6,
                children: [
                  for (final credit in about.credits.take(YoinShareCards.quoteCredits))
                    Flexible(child: _CreditChip(credit, colors)),
                ],
              ),
            ),
          Padding(
            padding: const EdgeInsets.only(top: 20, bottom: 16),
            child: SizedBox(
              height: 1,
              width: double.infinity,
              child: ColoredBox(color: colors.content.withValues(alpha: 0.18)),
            ),
          ),
          _ShareHead(content.song, colors, coverSize: 32),
          const SizedBox(height: 24),
          _ShareFoot(colors, sign: _aiSignOrNull(content, colors)),
        ],
      ),
    );
  }
}

class _CreditChip extends StatelessWidget {
  const _CreditChip(this.credit, this.colors);

  final ShareCredit credit;
  final ShareCardColors colors;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final labelStyle = text.labelSmall!.copyWith(color: colors.secondary);
    final valueStyle = shareWeight(text.labelLarge!, 600).copyWith(color: colors.content);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(color: colors.content.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(8)),
      child: LayoutBuilder(
        builder: (context, constraints) {
          // As Compose measures the row: the label at its own width first, the name in what is left (cut with
          // an ellipsis); only a chip too narrow for the label itself cuts the label too.
          final painter = TextPainter(
            text: TextSpan(text: credit.label, style: labelStyle),
            textDirection: Directionality.of(context),
            textScaler: MediaQuery.textScalerOf(context),
            maxLines: 1,
          )..layout();
          final labelFits = painter.width + 6 < constraints.maxWidth;
          painter.dispose();
          final label = Text(credit.label, maxLines: 1, overflow: TextOverflow.ellipsis, style: labelStyle);
          return Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              labelFits ? label : Flexible(child: label),
              const SizedBox(width: 6),
              Flexible(
                child: Text.rich(
                  markdownBoldSpan(credit.value),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: valueStyle,
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _AboutLiner extends StatelessWidget {
  const _AboutLiner(this.content, this.colors, this.frame);

  final ShareContent content;
  final ShareCardColors colors;
  final ShareFrame frame;

  @override
  Widget build(BuildContext context) {
    final about = content.about;
    if (about == null) return const SizedBox.shrink();
    final text = Theme.of(context).textTheme;
    final cells = about.credits.take(YoinShareCards.linerCredits).toList();
    final paragraph = about.paragraph;
    final label = text.labelMedium!;
    return _frameGround(
      frame,
      colors.card,
      Column(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text(
                        about.heading.toUpperCase(),
                        style: shareWeight(label, 600).copyWith(letterSpacing: 1.6, color: colors.secondary),
                      ),
                    ),
                  ),
                  ShareCover(url: content.song.coverArtUrl, size: 72, tint: colors.content, radius: 8),
                ],
              ),
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(content.song.title, style: shareHeroStyle(36, 1.12).copyWith(color: colors.content)),
              ),
              if (content.song.artist.trim().isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(content.song.artist, style: text.bodyMedium!.copyWith(color: colors.secondary)),
                ),
              if (cells.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 20),
                  child: Column(
                    children: [
                      for (var i = 0; i < cells.length; i += 2)
                        Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          spacing: 16,
                          children: [
                            Expanded(child: _LinerCell(cells[i], colors)),
                            if (i + 1 < cells.length)
                              Expanded(child: _LinerCell(cells[i + 1], colors))
                            else
                              const Spacer(),
                          ],
                        ),
                    ],
                  ),
                ),
              if (paragraph != null)
                Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text.rich(
                    markdownBoldSpan(paragraph),
                    style: text.bodyMedium!.copyWith(
                      height: 22 / (text.bodyMedium!.fontSize ?? 14),
                      color: colors.content,
                    ),
                  ),
                ),
            ],
          ),
          Padding(
            padding: const EdgeInsets.only(top: 24),
            child: _ShareFoot(colors, sign: _aiSignOrNull(content, colors)),
          ),
        ],
      ),
    );
  }
}

class _LinerCell extends StatelessWidget {
  const _LinerCell(this.credit, this.colors);

  final ShareCredit credit;
  final ShareCardColors colors;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return DecoratedBox(
      decoration: BoxDecoration(
        border: Border(top: BorderSide(color: colors.content.withValues(alpha: 0.22))),
      ),
      child: Padding(
        padding: const EdgeInsets.only(top: 10, bottom: 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(credit.label, style: text.labelSmall!.copyWith(color: colors.secondary)),
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Text.rich(
                markdownBoldSpan(credit.value),
                style: shareWeight(text.titleMedium!, 600).copyWith(color: colors.content),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Note: Big type, Clipping, Timeline (primary).
// ---------------------------------------------------------------------------

class _NoteBig extends StatelessWidget {
  const _NoteBig(this.content, this.colors, this.frame);

  final ShareContent content;
  final ShareCardColors colors;
  final ShareFrame frame;

  @override
  Widget build(BuildContext context) {
    final note = content.note;
    if (note == null) return const SizedBox.shrink();
    final at = note.positionMs;
    return _frameGround(
      frame,
      colors.card,
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _ShareHead(content.song, colors),
          if (at != null)
            Padding(padding: const EdgeInsets.only(top: 20), child: _ShareTimeChip(shareTime(at), colors)),
          Padding(
            padding: EdgeInsets.only(top: at != null ? 12 : 20),
            // Lighter than a lyric line, so the note's own **bold** reads.
            child: _ShareNoteText(note, gsf(21, weight: 400, height: 1.38), colors.content),
          ),
          const SizedBox(height: 24),
          _ShareFoot(colors, sign: _NoteSign(note, colors.secondary)),
        ],
      ),
    );
  }
}

class _NoteClipping extends StatelessWidget {
  const _NoteClipping(this.content, this.colors, this.frame);

  final ShareContent content;
  final ShareCardColors colors;
  final ShareFrame frame;

  @override
  Widget build(BuildContext context) {
    final note = content.note;
    if (note == null) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final story = frame == ShareFrame.poster;
    final locale = Localizations.maybeLocaleOf(context) ?? const Locale('en');
    final body = Column(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Transform.rotate(
                  angle: -3 * math.pi / 180,
                  child: ShareCover(
                    url: content.song.coverArtUrl,
                    size: story ? 136 : 104,
                    tint: scheme.onSurface,
                    radius: 6,
                  ),
                ),
                Flexible(child: _TicketStub(content.song, note, story: story)),
              ],
            ),
            Padding(
              padding: EdgeInsets.only(top: story ? 18 : 14),
              child: Transform.rotate(
                angle: 1 * math.pi / 180,
                child: Container(
                  width: double.infinity,
                  padding: const EdgeInsets.fromLTRB(18, 16, 18, 14),
                  decoration: BoxDecoration(color: colors.card, borderRadius: BorderRadius.circular(16)),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _ShareNoteText(note, gsf(story ? 18 : 16, weight: 500, height: 1.6), colors.content),
                      Padding(
                        padding: const EdgeInsets.only(top: 6),
                        child: Text(
                          shareNoteDate(note.createdAt, locale),
                          textAlign: TextAlign.end,
                          style: shareWeight(text.labelMedium!, 400).copyWith(color: colors.secondary),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.only(top: 24),
          child: _ShareFoot(colors, ink: scheme.onSurface),
        ),
      ],
    );
    // The paper is the image's ground; the tone colours the clipping.
    return switch (frame) {
      ShareFrame.card => ColoredBox(
        color: scheme.surface,
        child: CustomPaint(
          painter: _DotPaperPainter(scheme.outlineVariant),
          child: Padding(padding: const EdgeInsets.fromLTRB(24, 28, 24, 24), child: body),
        ),
      ),
      ShareFrame.storyCard => ClipRSuperellipse(
        borderRadius: BorderRadius.circular(ShapeTokens.panel),
        child: ColoredBox(
          color: scheme.surface,
          child: CustomPaint(
            painter: _DotPaperPainter(scheme.outlineVariant),
            child: Padding(padding: const EdgeInsets.all(24), child: body),
          ),
        ),
      ),
      ShareFrame.poster => body,
    };
  }
}

/// The ticket stub beside the clipping's cover: title > artist > time (owner
/// 2026-11-25) — the title larger, the time last, lighter, narrower and muted.
class _TicketStub extends StatelessWidget {
  const _TicketStub(this.song, this.note, {required this.story});

  final ShareSong song;
  final ShareNote note;
  final bool story;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final titleSize = story ? 20.0 : 17.0;
    final artistSize = story ? 14.0 : 13.0;
    final at = note.positionMs;
    final narrow = gsf(story ? 13 : 12, weight: 400, letterSpacing: -0.1);
    return Transform.translate(
      offset: const Offset(-10, 0),
      child: Padding(
        padding: const EdgeInsets.only(bottom: 6),
        child: Transform.rotate(
          angle: 2 * math.pi / 180,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 190),
            child: Container(
              padding: const EdgeInsetsDirectional.fromSTEB(16, 9, 14, 9),
              decoration: BoxDecoration(color: scheme.surfaceContainerHigh, borderRadius: BorderRadius.circular(10)),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    song.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: gsf(
                      titleSize,
                      weight: 600,
                      height: (story ? 26 : 22) / titleSize,
                    ).copyWith(color: scheme.onSurface),
                  ),
                  if (song.artist.trim().isNotEmpty)
                    Text(
                      song.artist,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: text.bodySmall!
                          .merge(gsf(artistSize, height: 18 / artistSize))
                          .copyWith(color: scheme.onSurfaceVariant),
                    ),
                  if (at != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 3),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(YoinSymbols.playFilled, size: 10, color: scheme.onSurfaceVariant.withValues(alpha: 0.7)),
                          const SizedBox(width: 3),
                          Text(
                            shareTime(at),
                            // Google Sans Flex at 80% width, Regular: the stub's quiet time.
                            style: narrow.copyWith(
                              fontVariations: [...?narrow.fontVariations, const FontVariation('wdth', 80)],
                              color: scheme.onSurfaceVariant.withValues(alpha: 0.9),
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _NoteTimeline extends StatelessWidget {
  const _NoteTimeline(this.content, this.colors, this.frame);

  final ShareContent content;
  final ShareCardColors colors;
  final ShareFrame frame;

  @override
  Widget build(BuildContext context) {
    final note = content.note;
    final at = note?.positionMs;
    final duration = content.song.durationMs;
    final fraction = YoinShareCards.timelineFraction(at, duration);
    if (note == null || at == null || fraction == null) return const SizedBox.shrink();
    return _frameGround(
      frame,
      colors.card,
      Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _ShareHead(content.song, colors),
          const SizedBox(height: 22),
          _chipAt(fraction, _ShareTimeChip(shareTime(at), colors)),
          SizedBox(height: 32, child: CustomPaint(painter: _WaveLinePainter(fraction, colors.content))),
          Padding(padding: const EdgeInsets.only(top: 2), child: _Ends(duration, colors.content)),
          // The note hangs from its moment: a bubble whose notch points at the marker.
          Padding(
            padding: const EdgeInsets.only(top: 16),
            child: CustomPaint(
              painter: _BubblePainter(fraction, colors.content.withValues(alpha: 0.09)),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
                child: _ShareNoteText(note, gsf(17, weight: 500, height: 1.5), colors.content),
              ),
            ),
          ),
          const SizedBox(height: 24),
          _ShareFoot(colors, sign: _NoteSign(note, colors.secondary)),
        ],
      ),
    );
  }
}

/// Yoin's wavy progress: the played part a sine, the rest a flat track, a short bar at the moment.
class _WaveLinePainter extends CustomPainter {
  _WaveLinePainter(this.fraction, this.ink);

  final double fraction;
  final Color ink;

  @override
  void paint(Canvas canvas, Size size) {
    final mid = size.height / 2;
    const amp = 3.2;
    const wavelength = 16.0;
    const stroke = 4.0;
    const gap = 6.0;
    final x = size.width * fraction;
    final wave = Path()..moveTo(0, mid);
    for (var px = 0.0; px <= x - gap; px += 1) {
      wave.lineTo(px, mid + amp * math.sin(px / wavelength * 2 * math.pi));
    }
    canvas.drawPath(
      wave,
      Paint()
        ..color = ink
        ..style = PaintingStyle.stroke
        ..strokeWidth = stroke
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round,
    );
    if (x + gap < size.width) {
      canvas.drawLine(
        Offset(x + gap, mid),
        Offset(size.width, mid),
        Paint()
          ..color = ink.withValues(alpha: 0.24)
          ..strokeWidth = stroke
          ..strokeCap = StrokeCap.round,
      );
    }
    canvas.drawRRect(
      RRect.fromRectAndRadius(Rect.fromLTWH(x - 2, mid - 12, 4, 24), const Radius.circular(2)),
      Paint()..color = ink,
    );
  }

  @override
  bool shouldRepaint(_WaveLinePainter old) => old.fraction != fraction || old.ink != ink;
}

class _BubblePainter extends CustomPainter {
  _BubblePainter(this.fraction, this.fill);

  final double fraction;
  final Color fill;

  @override
  void paint(Canvas canvas, Size size) {
    const notch = 8.0;
    final x = (size.width * fraction).clamp(18.0, math.max(18.0, size.width - 18)).toDouble();
    final paint = Paint()..color = fill;
    canvas.drawPath(
      Path()
        ..moveTo(x - notch, 0)
        ..lineTo(x, -notch)
        ..lineTo(x + notch, 0)
        ..close(),
      paint,
    );
    canvas.drawRRect(RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(16)), paint);
  }

  @override
  bool shouldRepaint(_BubblePainter old) => old.fraction != fraction || old.fill != fill;
}

/// The scrapbook's dot paper: a 16 grid of 1.2 dots in outlineVariant.
class _DotPaperPainter extends CustomPainter {
  _DotPaperPainter(this.dot);

  final Color dot;

  @override
  void paint(Canvas canvas, Size size) {
    const step = 16.0;
    const radius = 1.2;
    final paint = Paint()..color = dot.withValues(alpha: 0.7);
    for (var y = step / 2; y < size.height; y += step) {
      for (var x = step / 2; x < size.width; x += step) {
        canvas.drawCircle(Offset(x, y), radius, paint);
      }
    }
  }

  @override
  bool shouldRepaint(_DotPaperPainter old) => old.dot != dot;
}

// ---------------------------------------------------------------------------
// Layout: the Card's whole export pixels, the Story's zoom, the preview's fit.
// ---------------------------------------------------------------------------

/// Lays the Card out at its own height, then again at that height rounded up
/// to whole export pixels (× [pixelRatio]): the body fills the sliver, so the
/// PNG's last row is never half see-through.
class SnapToExportPixels extends SingleChildRenderObjectWidget {
  const SnapToExportPixels({super.key, required this.pixelRatio, super.child});

  final double pixelRatio;

  @override
  RenderObject createRenderObject(BuildContext context) => RenderShareSnap(pixelRatio);

  @override
  void updateRenderObject(BuildContext context, RenderShareSnap renderObject) => renderObject.pixelRatio = pixelRatio;
}

class RenderShareSnap extends RenderProxyBox {
  RenderShareSnap(this._pixelRatio);

  double _pixelRatio;

  set pixelRatio(double value) {
    if (value == _pixelRatio) return;
    _pixelRatio = value;
    markNeedsLayout();
  }

  @override
  void performLayout() {
    final child = this.child;
    if (child == null) {
      size = constraints.smallest;
      return;
    }
    // The card's own width decides (a tight parent doesn't stretch the image).
    child.layout(constraints.loosen(), parentUsesSize: true);
    final natural = child.size;
    final snapped = (natural.height * _pixelRatio - 1e-3).ceil() / _pixelRatio;
    if (snapped != natural.height) {
      child.layout(BoxConstraints.tightFor(width: natural.width, height: snapped), parentUsesSize: true);
    }
    size = constraints.constrain(child.size);
  }
}

/// Fills the incoming width and fits the incoming height by ZOOMING (Android
/// ZoomToFit): the child is laid out at width / zoom (wider when zooming out,
/// so lines wrap less; narrower when zooming in) and drawn back at `zoom`,
/// the largest zoom up to [maxZoom] whose height fits ([YoinShareCards.storyZoom],
/// measured synchronously, one layout per zoom tried — no intrinsics, which a
/// note's mention pills can't answer). [fillHeight] stretches the child to the
/// frame's height at that zoom (a poster's foot sits at the bottom). Even the
/// furthest zoom can fall short: the rest is shrunk, never a line cut.
class ShareZoomToFit extends SingleChildRenderObjectWidget {
  const ShareZoomToFit({super.key, required this.maxZoom, this.fillHeight = false, super.child});

  final double maxZoom;
  final bool fillHeight;

  @override
  RenderObject createRenderObject(BuildContext context) => RenderShareZoomToFit(maxZoom, fillHeight);

  @override
  void updateRenderObject(BuildContext context, RenderShareZoomToFit renderObject) {
    renderObject
      ..maxZoom = maxZoom
      ..fillHeight = fillHeight;
  }
}

class RenderShareZoomToFit extends RenderProxyBox {
  RenderShareZoomToFit(this._maxZoom, this._fillHeight);

  double _maxZoom;
  bool _fillHeight;
  double _scale = 1;

  /// The zoom the last layout settled on (tests).
  double zoom = 1;

  set maxZoom(double value) {
    if (value == _maxZoom) return;
    _maxZoom = value;
    markNeedsLayout();
  }

  set fillHeight(bool value) {
    if (value == _fillHeight) return;
    _fillHeight = value;
    markNeedsLayout();
  }

  @override
  void performLayout() {
    final child = this.child;
    if (child == null || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
      super.performLayout();
      _scale = 1;
      return;
    }
    final frameW = constraints.maxWidth;
    final frameH = constraints.maxHeight;
    zoom = YoinShareCards.storyZoom(_maxZoom, (z) {
      child.layout(BoxConstraints.tightFor(width: frameW / z), parentUsesSize: true);
      return YoinShareCards.storyFits(child.size.height.ceil(), z, frameH.floor());
    });
    final logicalW = frameW / zoom;
    child.layout(
      BoxConstraints(minWidth: logicalW, maxWidth: logicalW, minHeight: _fillHeight ? frameH / zoom : 0),
      parentUsesSize: true,
    );
    _scale = math.min(zoom, frameH / child.size.height);
    size = constraints.constrain(Size(child.size.width * _scale, child.size.height * _scale));
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final child = this.child;
    if (child == null) return;
    if (_scale == 1) {
      context.paintChild(child, offset);
      return;
    }
    context.pushTransform(needsCompositing, offset, Matrix4.diagonal3Values(_scale, _scale, 1), (context, offset) {
      context.paintChild(child, offset);
    });
  }

  @override
  void applyPaintTransform(RenderBox child, Matrix4 transform) {
    transform.multiply(Matrix4.diagonal3Values(_scale, _scale, 1));
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) => false;
}

/// Lays the child out at its own size and scales it — never above
/// [maxScale] — to fit the incoming width and [maxHeight] ([YoinShareCards.fitScale]):
/// a preview of exactly what will be exported.
class ShareFitScale extends SingleChildRenderObjectWidget {
  const ShareFitScale({super.key, required this.maxHeight, this.maxScale = 1, super.child});

  final double maxHeight;
  final double maxScale;

  @override
  RenderObject createRenderObject(BuildContext context) => RenderShareFitScale(maxHeight, maxScale);

  @override
  void updateRenderObject(BuildContext context, RenderShareFitScale renderObject) {
    renderObject
      ..maxHeight = maxHeight
      ..maxScale = maxScale;
  }
}

class RenderShareFitScale extends RenderProxyBox {
  RenderShareFitScale(this._maxHeight, this._maxScale);

  double _maxHeight;
  double _maxScale;
  double _scale = 1;

  set maxHeight(double value) {
    if (value == _maxHeight) return;
    _maxHeight = value;
    markNeedsLayout();
  }

  set maxScale(double value) {
    if (value == _maxScale) return;
    _maxScale = value;
    markNeedsLayout();
  }

  int _px(double v) => v.isFinite ? v.floor() : 0x7FFFFFFF;

  @override
  void performLayout() {
    final child = this.child;
    if (child == null) {
      size = constraints.smallest;
      return;
    }
    child.layout(const BoxConstraints(), parentUsesSize: true);
    final s = child.size;
    _scale = YoinShareCards.fitScale(
      s.width.round(),
      s.height.round(),
      _px(constraints.maxWidth),
      _px(math.min(constraints.maxHeight, _maxHeight)),
      _maxScale,
    );
    size = constraints.constrain(Size(s.width * _scale, s.height * _scale));
  }

  @override
  void paint(PaintingContext context, Offset offset) {
    final child = this.child;
    if (child == null) return;
    context.pushTransform(needsCompositing, offset, Matrix4.diagonal3Values(_scale, _scale, 1), (context, offset) {
      context.paintChild(child, offset);
    });
  }

  @override
  void applyPaintTransform(RenderBox child, Matrix4 transform) {
    transform.multiply(Matrix4.diagonal3Values(_scale, _scale, 1));
  }

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) => false;
}
