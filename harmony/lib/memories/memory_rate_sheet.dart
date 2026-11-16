/// The album's rate and review sheet (Android AlbumDetailComponents
/// AlbumRateSheet, owner R1–R4 2026-10-06): "Rate" and the album name at the
/// top left beside a 120 dp emblem; the horizontal rating bar (0.1 a step,
/// the cover's colours) — the emblem follows the score live, lifts 6% and
/// leans 1.4°/point while held, and stamps once on release; then the one
/// review field: a 56 dp capsule at rest, a 20 dp card while written,
/// growing with its words. No Save: closing the sheet saves, then hands the
/// album to NeoDB when signed in and something changed (owner R3); NeoDB's
/// state is the sheet's quiet last line.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../detail/detail_cache.dart';
import '../neodb/neodb_lines.dart';
import '../neodb/neodb_providers.dart';
import '../neodb/neodb_strings.dart';
import '../neodb/neodb_voices_screen.dart';
import '../nowplaying/haptics.dart';
import '../shell/bar_suppression.dart';
import '../theme/motion.dart';
import '../theme/theme.dart';
import 'album_score.dart';
import 'copy/memory_copy.dart';
import 'emblem/groove_award.dart';
import 'emblem/groove_emblem.dart';
import 'emblem/groove_geometry.dart';
import 'palette/memory_palette.dart';

/// Detail strings the sheet uses, from Android strings_detail.xml (key in the comment).
class _RateStrings {
  const _RateStrings(this.zh);
  final bool zh;
  String get rate => zh ? '评分' : 'Rate'; // detail_album_rate
  String get reviewHint => zh ? '评论' : 'Review'; // detail_album_review_hint
  String get review => zh ? '评论' : 'Review'; // detail_album_review
  String get shortComment => zh ? '短评' : 'Short comment'; // detail_album_short_comment
  String count(int n, int max) => '$n / $max'; // detail_album_review_count
}

/// A short comment on NeoDB holds this many characters (NeoDbShortCommentMax).
const int _shortCommentMax = 360;

/// Opens the sheet for [albumId]; [focusReview] puts the caret in the review.
Future<void> showAlbumRateSheet(
  BuildContext context, {
  required MediaId albumId,
  required String albumName,
  required MemoryPalette palette,
  bool focusReview = false,
  String? coverUrl,
}) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    showDragHandle: true,
    backgroundColor: Theme.of(context).colorScheme.surfaceContainerLow,
    builder: (_) => ChromeBarSuppression(
      child: AlbumRateSheet(
        albumId: albumId,
        albumName: albumName,
        palette: palette,
        focusReview: focusReview,
        coverUrl: coverUrl,
      ),
    ),
  );
}

class AlbumRateSheet extends ConsumerStatefulWidget {
  const AlbumRateSheet({
    super.key,
    required this.albumId,
    required this.albumName,
    required this.palette,
    this.focusReview = false,
    this.coverUrl,
  });

  final MediaId albumId;
  final String albumName;
  final MemoryPalette palette;
  final bool focusReview;

  /// The cover, for "What others say" (the page wears the album's colours).
  final String? coverUrl;

  @override
  ConsumerState<AlbumRateSheet> createState() => _AlbumRateSheetState();
}

class _AlbumRateSheetState extends ConsumerState<AlbumRateSheet> with TickerProviderStateMixin {
  late final TextEditingController _text = TextEditingController(
    text: ref.read(albumScoreProvider(widget.albumId)).review ?? '',
  );
  final FocusNode _focus = FocusNode();
  final GrooveAwardController _award = GrooveAwardController();
  late final AlbumScoreNotifier _scores = ref.read(albumScoreProvider(widget.albumId).notifier);

  /// The stored review the field started from (dispose compares against it).
  String _initialReview = '';
  bool _seeding = false;
  bool _edited = false;

  /// The score under the finger (null = the stored one).
  double? _live;
  late final SpringValue _lift = SpringValue(vsync: this, initial: 0, spring: YoinSprings.fastSpatial);
  late final SpringValue _corner = SpringValue(vsync: this, initial: 28, spring: YoinSprings.defaultSpatial);

  /// The account's store, held from the start: closing the sheet saves after its providers may be gone.
  MemoryStore? _store;

  /// NeoDB's pushes outlive the sheet (the push starts as it closes).
  late final NeoDbPushController _neoDb = ref.read(neoDbPushProvider.notifier);
  String _neoDbFailed = const NeoDbStrings.forLanguage(chinese: false).albumSyncFailed;

  @override
  void initState() {
    super.initState();
    _initialReview = _text.text;
    // Read now: ref can't be used once the sheet is disposed.
    _neoDb;
    ref.read(memoryStoreProvider.future).then((store) => _store = store);
    _focus.addListener(_syncCorner);
    _text.addListener(() {
      // Seeded during build: that build already shows it.
      if (_seeding) return;
      _edited = true;
      setState(_syncCorner);
    });
    if (widget.focusReview) WidgetsBinding.instance.addPostFrameCallback((_) => _focus.requestFocus());
  }

  void _syncCorner() => _corner.animateTo(_focus.hasFocus || _text.text.isNotEmpty ? 20 : 28);

  @override
  void dispose() {
    // Closing the sheet saves the words, then hands the album to NeoDB (a no-op signed out or unchanged).
    final store = _store;
    final save = _text.text.trim() != _initialReview.trim() && store != null
        ? store.setAlbumReview(widget.albumId, _text.text, nowMs: DateTime.now().millisecondsSinceEpoch)
        : Future<void>.value();
    final neoDb = _neoDb;
    final albumId = widget.albumId;
    final failed = _neoDbFailed;
    save.then((_) => neoDb.push(albumId, failureMessage: failed), onError: (Object _) {});
    _text.dispose();
    _focus.dispose();
    _award.dispose();
    _lift.dispose();
    _corner.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    final strings = _RateStrings(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');
    _neoDbFailed = NeoDbStrings.of(context).albumSyncFailed;
    final score = ref.watch(albumScoreProvider(widget.albumId));
    // The stored review lands after the sheet opens: seed the field until the user types.
    final stored = score.review ?? '';
    if (!_edited && stored != _initialReview) {
      _initialReview = stored;
      _seeding = true;
      _text.text = stored;
      _seeding = false;
    }
    final storedScore = score.albumRating;
    final live = _live ?? storedScore ?? 0;
    final tones = widget.palette.tones(dark);
    final kind = live > 0
        ? GrooveScoreKind.album
        : (score.average != null ? GrooveScoreKind.average : GrooveScoreKind.none);
    final shown = live > 0 ? live : score.average;
    final words = _text.text;
    final count = words.runes.length;
    final short = count <= _shortCommentMax;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 14, 24, 32),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Semantics(
                      header: true,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            strings.rate,
                            style: Theme.of(context).textTheme.labelLarge?.copyWith(color: colors.onSurfaceVariant),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            widget.albumName,
                            style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w600),
                            maxLines: 3,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
                AnimatedBuilder(
                  animation: _lift.animation,
                  builder: (context, child) => Transform.rotate(
                    angle: (live - 5) * 1.4 * _lift.value * 3.1415926 / 180,
                    child: Transform.scale(scale: 1 + 0.06 * _lift.value, child: child),
                  ),
                  child: GrooveEmblem(
                    size: 120,
                    ratedTracks: score.ratedTracks.isEmpty ? const [false, false, false, false] : score.ratedTracks,
                    kind: kind,
                    scoreText: shown == null ? null : MemoryScores.text(shown),
                    palette: widget.palette,
                    award: _award,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            _HorizontalRating(
              rating: live > 0 ? live : null,
              fill: tones.button,
              onFill: tones.onButton,
              onChanged: (v) => setState(() => _live = v),
              onActive: (active) {
                _lift.animateTo(active ? 1 : 0);
                if (!active) {
                  final value = _live ?? 0;
                  _scores.setRating(value);
                  if (value > 0) _award.play(mode: GrooveAwardMode.replay);
                  setState(() => _live = null);
                }
              },
            ),
            const SizedBox(height: 16),
            AnimatedBuilder(
              animation: _corner.animation,
              builder: (context, child) => Container(
                decoration: BoxDecoration(
                  color: colors.surfaceContainerHigh,
                  borderRadius: BorderRadius.circular(_corner.value.clamp(0, 28)),
                ),
                padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                child: child,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  TextField(
                    controller: _text,
                    focusNode: _focus,
                    maxLines: null,
                    style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: colors.onSurface),
                    decoration: InputDecoration.collapsed(
                      hintText: strings.reviewHint,
                      hintStyle: Theme.of(context).textTheme.bodyLarge?.copyWith(color: colors.onSurfaceVariant),
                    ),
                  ),
                  if (words.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          short ? strings.shortComment : strings.review,
                          style: Theme.of(context).textTheme.labelMedium?.copyWith(color: colors.onSurfaceVariant),
                        ),
                        Text(
                          short ? strings.count(count, _shortCommentMax) : '$count',
                          style: Theme.of(context).textTheme.labelMedium?.copyWith(
                            color: colors.onSurfaceVariant,
                            fontFeatures: const [FontFeature.tabularFigures()],
                          ),
                        ),
                      ],
                    ),
                  ],
                ],
              ),
            ),
            // The line stays for "What others say" even with no state to say (owner, 2026-11-09).
            Padding(
              padding: const EdgeInsets.only(top: 20),
              child: AlbumNeoDbLine(
                albumId: widget.albumId,
                unsaved: _edited && _text.text.trim() != _initialReview.trim(),
                onVoices: () {
                  final album = ref.read(detailCacheProvider).cachedAlbum(widget.albumId);
                  openNeoDbVoices(
                    context,
                    albumName: album?.name ?? widget.albumName,
                    artistName: album?.artist,
                    coverUrl: widget.coverUrl,
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The horizontal rating bar (RatingSlider, Orientation.Horizontal): a 48 dp
/// stadium, filled to the score in the cover's colour, 0.1 a step; a tap
/// rates too. A vertical swipe that starts on it belongs to the sheet.
class _HorizontalRating extends StatelessWidget {
  const _HorizontalRating({
    required this.rating,
    required this.fill,
    required this.onFill,
    required this.onChanged,
    required this.onActive,
  });

  final double? rating;
  final Color fill;
  final Color onFill;
  final ValueChanged<double> onChanged;
  final ValueChanged<bool> onActive;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return LayoutBuilder(
      builder: (context, c) {
        double valueAt(double x) => ((x / c.maxWidth).clamp(0.0, 1.0) * 100).round() / 10;
        final f = ((rating ?? 0) / 10).clamp(0.0, 1.0);
        return Semantics(
          slider: true,
          value: rating == null ? '' : MemoryScores.text(rating!),
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTapDown: (d) {
              onActive(true);
              YoinHaptics.tick();
              onChanged(valueAt(d.localPosition.dx));
            },
            onTapUp: (_) => onActive(false),
            onTapCancel: () => onActive(false),
            onHorizontalDragStart: (d) {
              onActive(true);
              onChanged(valueAt(d.localPosition.dx));
            },
            onHorizontalDragUpdate: (d) {
              final v = valueAt(d.localPosition.dx);
              if (v != rating && v * 10 % 10 == 0) YoinHaptics.lightTick();
              onChanged(v);
            },
            onHorizontalDragEnd: (_) => onActive(false),
            onHorizontalDragCancel: () => onActive(false),
            child: Container(
              height: 48,
              decoration: ShapeDecoration(shape: const StadiumBorder(), color: colors.surfaceContainerHighest),
              clipBehavior: Clip.antiAlias,
              child: Stack(
                children: [
                  FractionallySizedBox(
                    widthFactor: f,
                    heightFactor: 1,
                    child: DecoratedBox(
                      decoration: ShapeDecoration(shape: const StadiumBorder(), color: fill),
                    ),
                  ),
                  if (rating != null)
                    Align(
                      alignment: Alignment.centerRight,
                      child: Padding(
                        padding: const EdgeInsets.only(right: 18),
                        child: Text(
                          MemoryScores.text(rating!),
                          style: gsf(16, weight: 600, height: 1, letterSpacing: 0).copyWith(
                            color: f > 0.88 ? onFill : colors.onSurface,
                            fontFeatures: const [FontFeature.tabularFigures()],
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

/// The album page's score emblem (64 dp), live, tappable: opens the sheet.
class AlbumScoreEmblem extends ConsumerWidget {
  const AlbumScoreEmblem({
    super.key,
    required this.albumId,
    required this.palette,
    required this.onTap,
    this.size = 64,
  });

  final MediaId albumId;
  final MemoryPalette palette;
  final VoidCallback onTap;
  final double size;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final score = ref.watch(albumScoreProvider(albumId));
    final shown = score.shown;
    return GestureDetector(
      onTap: () {
        YoinHaptics.click();
        onTap();
      },
      child: GrooveEmblem(
        size: size,
        ratedTracks: score.ratedTracks.isEmpty ? const [false, false, false, false] : score.ratedTracks,
        kind: score.albumRating != null
            ? GrooveScoreKind.album
            : (score.average != null ? GrooveScoreKind.average : GrooveScoreKind.none),
        scoreText: shown == null ? null : MemoryScores.text(shown),
        palette: palette,
      ),
    );
  }
}
