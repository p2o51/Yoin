/// The diary state of one memory (Android showcase MemoryDiary +
/// MemoryDiaryWriter): vertical scroll only. From top to bottom: Yoin's
/// paragraph (only without a review) beside the native 48 dp emblem → your
/// entry (the review, or today's blank page with Cancel / Save) → • • • →
/// track rows (only rated or noted songs, notes hung under them like lyrics)
/// → the end: the run-out rings, two numerals, Go to album.
library;

import 'package:flutter/material.dart';

import '../data/model/model.dart';
import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'copy/memory_copy.dart';
import 'copy/memory_voice.dart';
import 'memories_layout.dart';
import 'memories_motion.dart';
import 'memories_strings.dart';
import 'memories_ui_state.dart';
import 'memory_card_face.dart';
import 'palette/memory_palette.dart';

TextStyle _ui(double size, int weight, double height, Color color, {double spacing = 0}) =>
    gsf(size, weight: weight, height: height, letterSpacing: spacing).copyWith(color: color);

const _tabular = [FontFeature.tabularFigures()];

class MemoryDiary extends StatelessWidget {
  const MemoryDiary({
    super.key,
    required this.memory,
    required this.tones,
    required this.layout,
    required this.diary,
    required this.scroll,
    required this.topInset,
    required this.emblem,
    required this.today,
    required this.reducedMotion,
    required this.interactive,
    required this.playingTrackId,
    required this.litNoteId,
    required this.onPlayTrack,
    required this.onPlayNote,
    required this.onOpenAlbum,
    required this.onSaveReview,
  });

  final MemoryEntry memory;
  final MemoryPaletteTones tones;
  final MemoriesLayout layout;
  final Animation<double> diary;
  final ScrollController scroll;

  /// The status bar plus the top bar: the text starts under them.
  final double topInset;
  final Widget emblem;
  final MemoryDay today;
  final bool reducedMotion;
  final bool interactive;
  final MediaId? playingTrackId;
  final String? litNoteId;
  final void Function(MemoryTrack track) onPlayTrack;
  final void Function(MemoryTrack track, MemoryWriting note) onPlayNote;
  final VoidCallback onOpenAlbum;
  final Future<bool> Function(String text) onSaveReview;

  @override
  Widget build(BuildContext context) {
    final strings = MemoriesStrings.of(context);
    final type = layout.type;
    final paragraph = MemoryVoice.paragraph(memory.yoinNarration, memory.yoinQuestion, memory.proseLanguage);
    final hasEntry = true; // the review, or today's blank page
    final hasTracks = memory.diaryTracks.isNotEmpty;
    final blocks = <Widget>[
      // Head: Yoin's paragraph beside the 48 (the album name already flew to the bar).
      Padding(
        padding: EdgeInsets.only(
          bottom: paragraph != null ? MemoriesTokens.diaryHeadGapWithAsk : MemoriesTokens.diaryHeadGap,
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: paragraph == null
                  ? const SizedBox.shrink()
                  : Padding(
                      padding: const EdgeInsetsDirectional.only(end: MemoriesTokens.diaryEmblemGap),
                      child: _YoinParagraph(memory: memory, size: type.paragraph),
                    ),
            ),
            SizedBox.square(dimension: MemoriesTokens.diaryEmblem, child: emblem),
          ],
        ),
      ),
      if (hasEntry)
        _DiaryEntry(
          key: ValueKey('entry:${memory.sessionId}'),
          memory: memory,
          tones: tones,
          type: type,
          today: today,
          strings: strings,
          interactive: interactive,
          onSave: onSaveReview,
        ),
      if (hasEntry && hasTracks) _Separator(tones: tones, type: type),
      for (final group in memory.diaryTracks)
        _TrackGroup(
          group: group,
          tones: tones,
          type: type,
          strings: strings,
          interactive: interactive,
          playing: group.track.trackId == playingTrackId,
          litNoteId: litNoteId,
          onTrack: () => onPlayTrack(group.track),
          onNote: (note) => onPlayNote(group.track, note),
        ),
      Padding(
        padding: const EdgeInsets.only(top: MemoriesTokens.diaryEndGap),
        child: _DiaryFoot(
          memory: memory,
          tones: tones,
          type: type,
          today: today,
          strings: strings,
          interactive: interactive,
          onOpenAlbum: onOpenAlbum,
        ),
      ),
    ];

    return LayoutBuilder(
      builder: (context, constraints) {
        final column = layout.diaryColumn(constraints.maxWidth);
        final side = (layout.wide ? 16.0 : MemoriesTokens.diarySide) - MemoriesTokens.diaryRowBleed;
        return ShaderMask(
          // Text dissolves under the bar (top) and into the bottom edge.
          blendMode: BlendMode.dstIn,
          shaderCallback: (rect) => LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: const [
              Color(0x00000000),
              Color(0x00000000),
              Color(0xFF000000),
              Color(0xFF000000),
              Color(0x00000000),
            ],
            stops: [
              0,
              (topInset - 8) / rect.height,
              (topInset + 16) / rect.height,
              1 - MemoriesTokens.diaryBottomFade / rect.height,
              1,
            ],
          ).createShader(rect),
          child: SingleChildScrollView(
            controller: scroll,
            // Always takes the drag, so a pull past either edge reaches p / q even when the page fits.
            physics: const AlwaysScrollableScrollPhysics(parent: ClampingScrollPhysics()),
            padding: EdgeInsets.only(
              top: topInset + MemoriesTokens.diaryTopInset,
              bottom: MemoriesTokens.diaryBottomInset + MediaQuery.paddingOf(context).bottom,
            ),
            child: Center(
              child: SizedBox(
                width: column,
                child: Padding(
                  padding: EdgeInsets.symmetric(horizontal: side),
                  child: AnimatedBuilder(
                    animation: diary,
                    builder: (context, _) {
                      final p = diary.value.clamp(0.0, 1.0);
                      return Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          for (var k = 0; k < blocks.length; k++)
                            _block(
                              blocks[k] is _TrackGroup
                                  ? blocks[k]
                                  : Padding(
                                      padding: const EdgeInsets.symmetric(horizontal: MemoriesTokens.diaryRowBleed),
                                      child: blocks[k],
                                    ),
                              k,
                              p,
                            ),
                        ],
                      );
                    },
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  /// Block k rises staggered behind the title: alpha over
  /// [blockFadeFrom + k·step … blockFadeTo], travel 28·k + 36 below.
  Widget _block(Widget child, int k, double p) {
    final i = k > 6 ? 6 : k;
    if (reducedMotion) {
      return Opacity(opacity: smoothstep(MemoriesTokens.reducedSwap, 1, p), child: child);
    }
    final a = smoothstep(
      MemoriesTokens.blockFadeFrom + MemoriesTokens.blockFadeStep * i,
      MemoriesTokens.blockFadeTo,
      p,
    );
    final dy = (1 - a) * (MemoriesTokens.blockStagger * i + MemoriesTokens.blockLagExtra);
    return Opacity(
      opacity: a,
      child: Transform.translate(offset: Offset(0, dy), child: child),
    );
  }
}

class _YoinParagraph extends StatelessWidget {
  const _YoinParagraph({required this.memory, required this.size});

  final MemoryEntry memory;
  final double size;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final zh = memory.proseLanguage == MemoryProseLanguage.zh;
    final narration = memory.yoinNarration;
    final question = memory.yoinQuestion;
    final base = gsf(
      size,
      weight: 400,
      height: 1.6,
      letterSpacing: 0,
      round: 60,
    ).copyWith(color: colors.onSurfaceVariant);
    return Text.rich(
      TextSpan(
        style: base,
        children: [
          if (narration != null && narration.isNotEmpty) TextSpan(text: narration),
          if (narration != null && narration.isNotEmpty && question != null && !zh) const TextSpan(text: ' '),
          if (question != null)
            TextSpan(
              text: question,
              style: gsf(size, weight: 500, height: 1.6, letterSpacing: 0, round: 60).copyWith(color: colors.onSurface),
            ),
        ],
      ),
    );
  }
}

/// Your entry: the review (no quotes, no signature; a short one with no
/// notes is set large), or today's blank page — the rail, one placeholder
/// line, no box, no outline — with Cancel / Save under it. Saving turns it
/// into the review in place: the rail fades, the text slides 14 → 0 on the
/// spatial spring, the label crossfades Today → Your review.
class _DiaryEntry extends StatefulWidget {
  const _DiaryEntry({
    super.key,
    required this.memory,
    required this.tones,
    required this.type,
    required this.today,
    required this.strings,
    required this.interactive,
    required this.onSave,
  });

  final MemoryEntry memory;
  final MemoryPaletteTones tones;
  final MemoriesType type;
  final MemoryDay today;
  final MemoriesStrings strings;
  final bool interactive;
  final Future<bool> Function(String text) onSave;

  @override
  State<_DiaryEntry> createState() => _DiaryEntryState();
}

class _DiaryEntryState extends State<_DiaryEntry> with TickerProviderStateMixin {
  final TextEditingController _text = TextEditingController();
  final FocusNode _focus = FocusNode();
  bool _saving = false;

  /// 1 = today's blank page (rail, inset 14), 0 = the review.
  late final SpringValue _blank = SpringValue(
    vsync: this,
    initial: widget.memory.review == null ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _railAlpha = SpringValue(
    vsync: this,
    initial: widget.memory.review == null ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void initState() {
    super.initState();
    _text.addListener(() => setState(() {}));
    _focus.addListener(() => setState(() {}));
  }

  @override
  void didUpdateWidget(_DiaryEntry old) {
    super.didUpdateWidget(old);
    final blank = widget.memory.review == null;
    final reduced = MediaQuery.disableAnimationsOf(context);
    if (reduced) {
      _blank.snapTo(blank ? 1 : 0);
    } else {
      _blank.animateTo(blank ? 1 : 0);
    }
    _railAlpha.animateTo(blank ? 1 : 0);
  }

  @override
  void dispose() {
    _text.dispose();
    _focus.dispose();
    _blank.dispose();
    _railAlpha.dispose();
    super.dispose();
  }

  bool get _writing => _focus.hasFocus || _text.text.isNotEmpty;

  Future<void> _save() async {
    final words = _text.text.trim();
    if (words.isEmpty || _saving) return;
    setState(() => _saving = true);
    final ok = await widget.onSave(words);
    if (!mounted) return;
    setState(() => _saving = false);
    if (ok) {
      YoinHaptics.confirm();
      _focus.unfocus();
      _text.clear();
    } else {
      ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(widget.strings.snackbarSaveReview)));
    }
  }

  void _cancel() {
    _text.clear();
    _focus.unfocus();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final memory = widget.memory;
    final review = memory.review;
    final type = widget.type;
    final strings = widget.strings;
    final date = review == null ? widget.today : localMemoryDay(review.writtenAt);
    final headerStyle = _ui(13, 500, 1.35, colors.onSurfaceVariant);
    return AnimatedBuilder(
      animation: Listenable.merge([_blank.animation, _railAlpha.animation]),
      builder: (context, _) {
        final blank = _blank.value;
        final rail = _railAlpha.value.clamp(0.0, 1.0);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: Row(
                children: [
                  Text(
                    strings.zh ? '${date.year}年${date.month}月${date.day}日' : MemoryDates.dayWithYear(date),
                    style: headerStyle.copyWith(fontFeatures: _tabular),
                  ),
                  const SizedBox(width: 8),
                  // Today → Your review, crossfaded.
                  Stack(
                    children: [
                      Opacity(
                        opacity: rail,
                        child: Text(strings.reviewToday, style: headerStyle),
                      ),
                      Opacity(
                        opacity: 1 - rail,
                        child: Text(strings.reviewYours, style: headerStyle),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            Stack(
              children: [
                if (rail > 0.001)
                  Positioned(
                    left: 4,
                    top: 0,
                    bottom: 0,
                    child: Opacity(
                      opacity: rail,
                      child: Container(
                        width: MemoriesTokens.diaryRailWidth,
                        decoration: BoxDecoration(
                          color: widget.tones.ink.withValues(alpha: _writing ? 1 : 0.4),
                          borderRadius: BorderRadius.circular(1),
                        ),
                      ),
                    ),
                  ),
                Padding(
                  padding: EdgeInsetsDirectional.only(start: MemoriesTokens.diaryWriterInset * blank.clamp(0.0, 1.0)),
                  child: review != null ? _ReviewBody(text: review.text, memory: memory, type: type) : _field(type),
                ),
              ],
            ),
            if (review == null && _writing)
              Padding(
                padding: const EdgeInsets.only(top: 6),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: _cancel,
                      child: Text(strings.reviewCancel, style: _ui(14, 600, 1, colors.onSurfaceVariant)),
                    ),
                    const SizedBox(width: 8),
                    Semantics(
                      button: true,
                      label: strings.cdSaveReview,
                      excludeSemantics: true,
                      child: Opacity(
                        opacity: _text.text.trim().isEmpty ? 0.38 : 1,
                        child: SizedBox(
                          height: 36,
                          child: Material(
                            color: widget.tones.button,
                            shape: const StadiumBorder(),
                            clipBehavior: Clip.antiAlias,
                            child: InkWell(
                              onTap: _text.text.trim().isEmpty ? null : _save,
                              child: Padding(
                                padding: const EdgeInsets.symmetric(horizontal: 16),
                                child: Center(
                                  widthFactor: 1,
                                  child: Text(strings.reviewSave, style: _ui(14, 600, 1, widget.tones.onButton)),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
          ],
        );
      },
    );
  }

  Widget _field(MemoriesType type) {
    final colors = Theme.of(context).colorScheme;
    final style = gsf(
      type.review,
      weight: 400,
      height: type.reviewLine,
      letterSpacing: 0,
    ).copyWith(color: colors.onSurface);
    return ConstrainedBox(
      constraints: const BoxConstraints(minHeight: 64),
      child: Semantics(
        label: widget.strings.cdReviewField,
        child: TextField(
          controller: _text,
          focusNode: _focus,
          enabled: widget.interactive && !_saving,
          maxLines: null,
          style: style,
          cursorColor: widget.tones.ink,
          decoration: InputDecoration.collapsed(
            hintText: widget.strings.reviewPlaceholder,
            hintStyle: style.copyWith(color: colors.onSurfaceVariant.withValues(alpha: 0.6)),
          ),
        ),
      ),
    );
  }
}

class _ReviewBody extends StatelessWidget {
  const _ReviewBody({required this.text, required this.memory, required this.type});

  final String text;
  final MemoryEntry memory;
  final MemoriesType type;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    // A short review (≤ 16 chars) with no notes is set large.
    final large = text.length <= 16 && memory.noteCount == 0;
    final size = large ? type.reviewShort : type.review;
    final line = large ? type.reviewShortLine : type.reviewLine;
    final style = gsf(
      size,
      weight: large ? 500 : 400,
      height: line,
      letterSpacing: 0,
    ).copyWith(color: colors.onSurface);
    final paragraphs = text.split('\n').where((s) => s.trim().isNotEmpty).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (var i = 0; i < paragraphs.length; i++) ...[
          if (i > 0) SizedBox(height: type.review * 0.85),
          Text(paragraphs[i], style: style),
        ],
      ],
    );
  }
}

class _Separator extends StatelessWidget {
  const _Separator({required this.tones, required this.type});

  final MemoryPaletteTones tones;
  final MemoriesType type;

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.only(top: type.separatorTop, bottom: type.separatorBottom),
    child: ExcludeSemantics(
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          for (var i = 0; i < 3; i++) ...[
            if (i > 0) const SizedBox(width: 10),
            Container(
              width: 4,
              height: 4,
              decoration: BoxDecoration(color: tones.dot.withValues(alpha: 0.5), shape: BoxShape.circle),
            ),
          ],
        ],
      ),
    ),
  );
}

class _TrackGroup extends StatelessWidget {
  const _TrackGroup({
    required this.group,
    required this.tones,
    required this.type,
    required this.strings,
    required this.interactive,
    required this.playing,
    required this.litNoteId,
    required this.onTrack,
    required this.onNote,
  });

  final MemoryDiaryTrack group;
  final MemoryPaletteTones tones;
  final MemoriesType type;
  final MemoriesStrings strings;
  final bool interactive;
  final bool playing;
  final String? litNoteId;
  final VoidCallback onTrack;
  final void Function(MemoryWriting note) onNote;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final track = group.track;
    final rated = track.rating != null;
    final score = rated ? MemoryScores.text(track.rating!) : null;
    final description = score != null
        ? strings.cdTrackNumberedRated(track.number, track.title, score)
        : strings.cdTrackNumbered(track.number, track.title);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Semantics(
          button: interactive,
          label: description,
          value: playing ? strings.cdPlaying : null,
          excludeSemantics: true,
          child: _Bleed(
            onTap: interactive ? onTrack : null,
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: MemoriesTokens.diaryRowMin),
              child: Row(
                children: [
                  SizedBox(
                    width: type.linerColumn,
                    child: _SpringText(
                      '${track.number}',
                      style: _ui(type.trackNumber, 500, 1, colors.onSurfaceVariant).copyWith(fontFeatures: _tabular),
                      color: playing ? tones.highlight : colors.onSurfaceVariant,
                    ),
                  ),
                  Expanded(
                    child: Text(
                      track.title,
                      style: _ui(16, rated ? 600 : 500, 1.3, rated ? colors.onSurface : colors.onSurfaceVariant),
                      maxLines: 1,
                      overflow: TextOverflow.fade,
                      softWrap: false,
                    ),
                  ),
                  if (score != null)
                    Padding(
                      padding: const EdgeInsetsDirectional.only(start: 16),
                      child: Text(score, style: _ui(15, 600, 1, tones.ink).copyWith(fontFeatures: _tabular)),
                    ),
                ],
              ),
            ),
          ),
        ),
        for (final note in group.notes)
          _NoteRow(
            note: note,
            lit: note.noteId != null && note.noteId == litNoteId,
            tones: tones,
            type: type,
            strings: strings,
            title: track.title,
            onTap: interactive ? () => onNote(note) : null,
          ),
      ],
    );
  }
}

class _NoteRow extends StatelessWidget {
  const _NoteRow({
    required this.note,
    required this.lit,
    required this.tones,
    required this.type,
    required this.strings,
    required this.title,
    required this.onTap,
  });

  final MemoryWriting note;
  final bool lit;
  final MemoryPaletteTones tones;
  final MemoriesType type;
  final MemoriesStrings strings;
  final String title;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final at = note.positionMs == null ? null : MemoryExcerpt.anchorTime(note.positionMs!);
    final line = type.noteLine;
    return Semantics(
      button: onTap != null,
      label: at != null ? strings.cdNoteAt(at, note.text) : strings.cdNote(note.text),
      value: lit ? strings.cdPlaying : null,
      onTapHint: at != null ? strings.cdPlayFrom(title, at) : strings.cdPlayTrack(title),
      excludeSemantics: true,
      child: _Bleed(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.only(top: 8, bottom: 10),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.baseline,
            textBaseline: TextBaseline.alphabetic,
            children: [
              SizedBox(
                width: type.linerColumn,
                child: _SpringText(
                  at ?? '',
                  style: _ui(
                    type.stamp,
                    500,
                    line / type.stamp,
                    colors.onSurfaceVariant,
                    spacing: 0.1,
                  ).copyWith(fontFeatures: _tabular),
                  color: lit ? tones.highlight : colors.onSurfaceVariant,
                ),
              ),
              Expanded(
                child: _SpringText(
                  note.text,
                  style: _ui(type.note, 400, line / type.note, colors.onSurface),
                  color: lit ? tones.highlight : colors.onSurface,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// A row whose press ground reaches 12 dp past the column on both sides
/// (the diary column is laid out 12 dp wider; text blocks pad back in).
class _Bleed extends StatelessWidget {
  const _Bleed({required this.child, this.onTap});

  final Widget child;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => Material(
    type: MaterialType.transparency,
    borderRadius: BorderRadius.circular(ShapeTokens.listRow),
    clipBehavior: Clip.antiAlias,
    child: InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: MemoriesTokens.diaryRowBleed),
        child: child,
      ),
    ),
  );
}

/// Text whose colour moves on the effects spring (the playing highlight).
class _SpringText extends StatefulWidget {
  const _SpringText(this.text, {required this.style, required this.color});

  final String text;
  final TextStyle style;
  final Color color;

  @override
  State<_SpringText> createState() => _SpringTextState();
}

class _SpringTextState extends State<_SpringText> with TickerProviderStateMixin {
  late Color _from = widget.color;
  late Color _to = widget.color;
  late final SpringValue _t = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);

  @override
  void didUpdateWidget(_SpringText old) {
    super.didUpdateWidget(old);
    if (widget.color != _to) {
      _from = Color.lerp(_from, _to, _t.value.clamp(0.0, 1.0))!;
      _to = widget.color;
      _t.snapTo(0);
      _t.animateTo(1);
    }
  }

  @override
  void dispose() {
    _t.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _t.animation,
    builder: (context, _) =>
        Text(widget.text, style: widget.style.copyWith(color: Color.lerp(_from, _to, _t.value.clamp(0.0, 1.0)))),
  );
}

class _DiaryFoot extends StatelessWidget {
  const _DiaryFoot({
    required this.memory,
    required this.tones,
    required this.type,
    required this.today,
    required this.strings,
    required this.interactive,
    required this.onOpenAlbum,
  });

  final MemoryEntry memory;
  final MemoryPaletteTones tones;
  final MemoriesType type;
  final MemoryDay today;
  final MemoriesStrings strings;
  final bool interactive;
  final VoidCallback onOpenAlbum;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final plays = memory.playsInYoin;
    final first = memory.firstHeardAt;
    final heard = plays != null && first != null;
    final days = heard ? MemoryDates.daysBetween(localMemoryDay(first), today) : 0;
    Widget stat(String numeral, String caption) => Semantics(
      label: strings.cdStat(numeral, caption),
      excludeSemantics: true,
      child: Column(
        children: [
          Text(
            numeral,
            style: gsf(
              type.stat,
              weight: 500,
              height: 1,
              letterSpacing: 0,
              round: 40,
            ).copyWith(color: colors.onSurface, fontFeatures: _tabular),
          ),
          const SizedBox(height: 8),
          Text(caption, style: _ui(12, 500, 1.3, colors.onSurfaceVariant, spacing: 0.1), maxLines: 1, softWrap: false),
        ],
      ),
    );
    return Column(
      children: [
        if (heard) ...[
          // The run-out groove: three hairline rings, only at the very end.
          Padding(
            padding: const EdgeInsets.only(bottom: MemoriesTokens.diaryRunOutBottom),
            child: ExcludeSemantics(
              child: CustomPaint(
                size: const Size.square(MemoriesTokens.diaryRunOut),
                painter: _RunOutPainter(tones.ink.withValues(alpha: 0.5)),
              ),
            ),
          ),
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              stat('$plays', strings.footerPlays(plays)),
              const SizedBox(width: MemoriesTokens.diaryStatGap),
              stat('$days', strings.footerDaysSince(days, strings.chromeDate(localMemoryDay(first), today))),
            ],
          ),
        ],
        Padding(
          padding: EdgeInsets.only(top: heard ? MemoriesTokens.diaryGoTop : 0),
          child: IgnorePointer(
            ignoring: !interactive,
            child: MemoryPillButton(
              label: strings.diaryGoAlbum,
              color: tones.button,
              onColor: tones.onButton,
              onTap: onOpenAlbum,
            ),
          ),
        ),
      ],
    );
  }
}

class _RunOutPainter extends CustomPainter {
  _RunOutPainter(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final k = size.shortestSide / 28;
    final paint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1
      ..color = color;
    for (final r in const [13.5, 9.5, 5.5]) {
      canvas.drawCircle(size.center(Offset.zero), r * k, paint);
    }
  }

  @override
  bool shouldRepaint(_RunOutPainter old) => old.color != color;
}
