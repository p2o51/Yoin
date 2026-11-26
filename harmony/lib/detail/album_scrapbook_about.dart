part of 'album_scrapbook_page.dart';

// The album's About on the scrapbook paper (owner + Carbon 2026-11-25; Android AlbumAboutCard.kt): the
// credits line under the title beside the cover, and the editorial card after the opening. The sheet behind
// the card is album_about_sheet.dart.

/// The credits line ([YoinAlbumAbout.creditsLine]) in this language; nothing at all when no item is known.
/// The text carries the shared line-break model (no-break spaces, word joiners), which Flutter's line
/// breaker reads as Android's does.
class _AlbumCreditsLine extends StatelessWidget {
  const _AlbumCreditsLine({required this.items});

  final List<AlbumCreditItem> items;

  @override
  Widget build(BuildContext context) {
    final line = albumCreditsLineFor(items, DetailStrings.of(context));
    if (line == null) return const SizedBox.shrink();
    final colors = _ScrapScope.of(context).colors;
    final style = Theme.of(context).textTheme.bodyMedium?.copyWith(
      fontSize: ScrapbookLayout.creditsFontSize,
      height: ScrapbookLayout.creditsLineHeight,
      letterSpacing: 0,
      color: colors.muted,
    );
    return _scrapFade(
      ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: ScrapbookLayout.aboutCardMaxWidth),
        child: Semantics(
          label: line.plain,
          excludeSemantics: true,
          child: Text(line.text, style: style),
        ),
      ),
    );
  }
}

/// The line for [items] in [strings]' language, the service-dated first release in its own date words;
/// null when no item is known.
AlbumCreditsLine? albumCreditsLineFor(List<AlbumCreditItem> items, DetailStrings strings) {
  if (items.isEmpty) return null;
  final dated = items.where((i) => i.date != null).firstOrNull?.date;
  return YoinAlbumAbout.creditsLine(items, strings.albumCreditLabels, strings.releaseDateText(dated));
}

/// The paragraph on a tertiary card, at most [ScrapbookLayout.aboutCardLines] lines with "Read all" when it
/// runs on (or when questions wait in the sheet); the AI's dots while it is first written. With no
/// paragraph: the newest question's card when the album was asked about, else the dashed ✦ circle.
class _AlbumAboutCard extends StatelessWidget {
  const _AlbumAboutCard({required this.piece, required this.albumName, required this.onTap});

  final ScrapAlbumAbout piece;
  final String albumName;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final text = piece.text;
    if (text == null && !piece.pending) {
      final ask = piece.asks.firstOrNull;
      if (ask == null) return _EmptyAboutMark(onTap: onTap);
      // No paragraph, but questions about the album: the newest as the Ask index card songs get.
      return Align(
        alignment: AlignmentDirectional.topStart,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: ScrapbookLayout.aboutCardMaxWidth),
          child: _scrapFade(
            _AskCard(
              ask: ScrapAsk(question: ask.question, title: ask.title, answer: ask.answer),
              moreAsks: piece.asks.length - 1,
              budget: ScrapbookRules.answerBudget,
              tilt: piece.tilt,
              trackTitle: albumName,
              onTap: onTap,
            ),
          ),
        ),
      );
    }
    final colors = _ScrapScope.of(context).colors;
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final bodyStyle = theme.textTheme.bodyMedium?.copyWith(
      fontSize: ScrapbookLayout.aboutBodySize,
      height: ScrapbookLayout.aboutBodyHeight,
      color: colors.onAbout,
    );
    // The card's every mark is its own ink (onTertiaryContainer): tertiary is too faint on a cover's
    // tertiaryContainer (Android device check 2026-11-25, dark theme).
    final label = Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(YoinSymbols.sparkle, size: ScrapbookLayout.aboutLabelGlyph, color: colors.onAbout),
        const SizedBox(width: 6),
        Text(
          strings.scrapAbout,
          style: theme.textTheme.labelLarge?.copyWith(
            fontWeight: FontWeight.w600,
            letterSpacing: 0.2,
            color: colors.onAbout,
          ),
        ),
      ],
    );
    return Align(
      alignment: AlignmentDirectional.topStart,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: ScrapbookLayout.aboutCardMaxWidth),
        child: _scrapFade(
          _ScrapLayer(
            tilt: piece.tilt,
            depth: 0.35,
            child: Semantics(
              button: text != null,
              label: strings.scrapCdAbout(albumName, text ?? ''),
              onTapHint: text == null ? null : strings.scrapCdReadAll,
              excludeSemantics: true,
              child: Material(
                color: colors.about,
                shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
                clipBehavior: Clip.antiAlias,
                child: InkWell(
                  onTap: text == null ? null : onTap,
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 14),
                    child: LayoutBuilder(
                      builder: (context, constraints) {
                        final overflowing =
                            text != null &&
                            _runsOver(
                              text,
                              bodyStyle,
                              constraints.maxWidth,
                              Directionality.of(context),
                              MediaQuery.textScalerOf(context),
                            );
                        return Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            label,
                            if (text == null)
                              Padding(
                                padding: const EdgeInsets.only(top: 12, bottom: 4),
                                child: ThinkingDots(color: colors.onAbout),
                              )
                            else ...[
                              Padding(
                                padding: const EdgeInsets.only(top: 6),
                                child: Text(
                                  text,
                                  style: bodyStyle,
                                  maxLines: ScrapbookLayout.aboutCardLines,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                              if (overflowing || piece.asks.isNotEmpty)
                                Padding(
                                  padding: const EdgeInsets.only(top: 6),
                                  child: Text(
                                    strings.scrapReadAll,
                                    style: theme.textTheme.labelLarge?.copyWith(
                                      fontWeight: FontWeight.w600,
                                      letterSpacing: 0,
                                      color: colors.onAbout,
                                    ),
                                  ),
                                ),
                            ],
                          ],
                        );
                      },
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// Whether [text] needs more than the card's lines at [width].
  static bool _runsOver(String text, TextStyle? style, double width, TextDirection direction, TextScaler scaler) {
    if (!width.isFinite) return false;
    final painter = TextPainter(
      text: TextSpan(text: text, style: style),
      textDirection: direction,
      textScaler: scaler,
      maxLines: ScrapbookLayout.aboutCardLines,
    )..layout(maxWidth: width);
    final over = painter.didExceedMaxLines;
    painter.dispose();
    return over;
  }
}

/// The album's About was asked for and came back empty: no card, only a dashed circle with the ✦ (the
/// blank review's pattern, in the generated text's tertiary). It opens the About sheet, where the AI can be
/// asked again or asked a question. No words of its own: a screen reader reads "About".
class _EmptyAboutMark extends StatelessWidget {
  const _EmptyAboutMark({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    return Align(
      alignment: AlignmentDirectional.centerStart,
      child: _scrapFade(
        Semantics(
          button: true,
          label: DetailStrings.of(context).scrapAbout,
          excludeSemantics: true,
          child: Material(
            type: MaterialType.transparency,
            shape: const CircleBorder(),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              key: const ValueKey('albumAboutEmpty'),
              onTap: onTap,
              child: CustomPaint(
                painter: _DashedCircle(colors.aboutMark),
                child: SizedBox.square(
                  dimension: ScrapbookLayout.aboutMark,
                  child: Icon(YoinSymbols.sparkle, size: ScrapbookLayout.aboutMarkGlyph, color: colors.aboutMark),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
