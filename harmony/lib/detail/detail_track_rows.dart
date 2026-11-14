import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/equalizer_glyph.dart';
import '../ui/component/media_artwork.dart';
import '../ui/component/meta_line.dart';
import '../ui/component/song_list_item.dart';
import 'detail_palette.dart';
import 'detail_strings.dart';
import 'playback_projection.dart';

const _tabular = [FontFeature.tabularFigures()];

/// "n tracks" + total duration stats (AlbumTrackCountLabel), labelMedium.
class TrackCountLine extends StatelessWidget {
  const TrackCountLine({super.key, required this.count, required this.totalSec, this.showDuration = true});

  final int count;
  final int totalSec;
  final bool showDuration;

  @override
  Widget build(BuildContext context) {
    final s = DetailStrings.of(context);
    return MetaLine(
      style: Theme.of(context).textTheme.labelMedium,
      groups: [
        MetaStat('$count', s.unitTrack(count)),
        if (showDuration) ...durationStatGroups(totalSec, hourUnit: s.unitHour, minuteUnit: s.unitMinute),
      ],
    );
  }
}

/// "Disc %d" before a disc's first row.
class DiscHeading extends StatelessWidget {
  const DiscHeading(this.disc, {super.key, this.first = false});

  final int disc;
  final bool first;

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsetsDirectional.only(start: 8, top: first ? 0 : 16, bottom: 6),
    child: Text(
      DetailStrings.of(context).discHeading(disc),
      style: Theme.of(context).textTheme.labelLarge?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
    ),
  );
}

/// AlbumTrackRow: [index / equalizer][title + artist][duration][heart].
class AlbumTrackRow extends ConsumerWidget {
  const AlbumTrackRow({
    super.key,
    required this.track,
    required this.index,
    required this.featArtist,
    required this.onTap,
    this.onLongPress,
    this.showHeart = true,
  });

  final Track track;
  final int index;
  final String? featArtist;
  final VoidCallback onTap;

  /// Opens the song sheet (TrackMenuSheet).
  final VoidCallback? onLongPress;
  final bool showHeart;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final play = ref.watch(trackPlayStateProvider(track.id));
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final text = theme.textTheme;
    final strings = DetailStrings.of(context);
    final duration = formatTrackDuration(track.durationSec);
    return DetailColorsBuilder(
      builder: (context, page) => Material(
        type: MaterialType.transparency,
        child: InkWell(
          onTap: onTap,
          onLongPress: onLongPress,
          customBorder: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.listRow)),
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 56),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
              child: Row(
                children: [
                  ConstrainedBox(
                    constraints: const BoxConstraints(minWidth: 18),
                    child: Align(
                      alignment: AlignmentDirectional.centerEnd,
                      widthFactor: 1,
                      child: play.current
                          ? EqualizerGlyph(
                              playing: play.playing,
                              color: page.accent,
                              semanticsLabel: strings.cdNowPlaying,
                            )
                          : Text(
                              '${track.trackNumber ?? index + 1}',
                              style: text.labelMedium?.copyWith(color: colors.onSurfaceVariant, fontFeatures: _tabular),
                            ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        SpringColorBuilder(
                          color: play.current ? page.accent : colors.onSurface,
                          builder: (color) => Text(
                            track.title ?? '',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: text.titleMedium?.copyWith(color: color),
                          ),
                        ),
                        if (featArtist != null) ...[
                          const SizedBox(height: 2),
                          Text(
                            featArtist!,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: text.bodySmall?.copyWith(color: colors.onSurfaceVariant),
                          ),
                        ],
                      ],
                    ),
                  ),
                  if (duration.isNotEmpty) ...[
                    const SizedBox(width: 12),
                    Text(
                      duration,
                      style: text.labelLarge?.copyWith(color: colors.onSurfaceVariant, fontFeatures: _tabular),
                    ),
                  ],
                  if (showHeart) ...[
                    const SizedBox(width: 4),
                    TrackHeartToggle(track: track, accent: page.accent, onAccent: page.onAccent),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// The song heart (AlbumCircleToggle): optimistic, reverted on failure,
/// shared through [favoriteOverridesProvider].
class TrackHeartToggle extends ConsumerWidget {
  const TrackHeartToggle({super.key, required this.track, required this.accent, required this.onAccent});

  final Track track;
  final Color accent;
  final Color onAccent;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final on = ref.watch(favoriteOverridesProvider.select((m) => m[track.id] ?? track.isStarred));
    final colors = Theme.of(context).colorScheme;
    final strings = DetailStrings.of(context);
    return PopToggle(
      on: on,
      tooltip: on ? strings.cdRemoveFavorite : strings.cdAddFavorite,
      onToggle: () => ref.read(favoriteOverridesProvider.notifier).setFavorite(track.id, target: !on, previous: on),
      builder: (t) => Container(
        width: 28,
        height: 28,
        decoration: ShapeDecoration(
          color: Color.lerp(Colors.transparent, accent, t),
          shape: CircleBorder(
            side: BorderSide(
              width: 1.5,
              color: Color.lerp(colors.outline.withValues(alpha: 0.5), Colors.transparent, t)!,
            ),
          ),
        ),
        alignment: Alignment.center,
        child: Icon(
          t > 0.5 ? YoinSymbols.favoriteFilled : YoinSymbols.favorite,
          size: 16,
          color: Color.lerp(colors.onSurfaceVariant, onAccent, t),
        ),
      ),
    );
  }
}

/// A toggle that pops when tapped: 90 ms up to 1.25 (on) / 1.15 (off), then
/// settles on the standard default spatial spring; its colours follow [on]
/// on the effects spring ([builder] gets 0..1).
class PopToggle extends StatefulWidget {
  const PopToggle({
    super.key,
    required this.on,
    required this.onToggle,
    required this.builder,
    this.tooltip,
    this.size = 36,
  });

  final bool on;
  final VoidCallback onToggle;
  final Widget Function(double t) builder;
  final String? tooltip;
  final double size;

  @override
  State<PopToggle> createState() => _PopToggleState();
}

class _PopToggleState extends State<PopToggle> with TickerProviderStateMixin {
  late final SpringValue _on = SpringValue(vsync: this, initial: widget.on ? 1 : 0, spring: YoinSprings.defaultEffects);
  late final AnimationController _scale = AnimationController.unbounded(vsync: this, value: 1);

  @override
  void didUpdateWidget(PopToggle old) {
    super.didUpdateWidget(old);
    _on.animateTo(widget.on ? 1 : 0);
  }

  @override
  void dispose() {
    _on.dispose();
    _scale.dispose();
    super.dispose();
  }

  Future<void> _tap() async {
    final target = !widget.on;
    widget.onToggle();
    // The pop is the one place a short linear tween is allowed.
    await _scale.animateTo(target ? 1.25 : 1.15, duration: const Duration(milliseconds: 90), curve: Curves.linear);
    if (!mounted) return;
    await _scale.animateWith(SpringSimulation(YoinSprings.standardDefaultSpatial, _scale.value, 1, _scale.velocity));
  }

  @override
  Widget build(BuildContext context) => IconButton(
    tooltip: widget.tooltip,
    isSelected: widget.on,
    constraints: BoxConstraints.tightFor(width: widget.size, height: widget.size),
    padding: EdgeInsets.zero,
    style: IconButton.styleFrom(tapTargetSize: MaterialTapTargetSize.padded),
    onPressed: _tap,
    icon: ScaleTransition(
      scale: _scale,
      child: AnimatedBuilder(
        animation: _on.animation,
        builder: (context, _) => widget.builder(_on.value.clamp(0.0, 1.0)),
      ),
    ),
  );
}

/// PlaylistTrackRow: [44dp album thumb][title + artist / album][duration].
class PlaylistTrackRow extends ConsumerWidget {
  const PlaylistTrackRow({
    super.key,
    required this.track,
    required this.coverUrl,
    required this.onTap,
    this.onLongPress,
  });

  final Track track;
  final String? coverUrl;
  final VoidCallback onTap;

  /// Opens the song sheet (TrackMenuSheet).
  final VoidCallback? onLongPress;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final play = ref.watch(trackPlayStateProvider(track.id));
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final text = theme.textTheme;
    final duration = formatTrackDuration(track.durationSec);
    final dpr = MediaQuery.devicePixelRatioOf(context);
    return DetailColorsBuilder(
      builder: (context, page) => Material(
        type: MaterialType.transparency,
        child: InkWell(
          onTap: onTap,
          onLongPress: onLongPress,
          customBorder: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.listRow)),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
            child: Row(
              children: [
                SizedBox.square(
                  dimension: 44,
                  child: MediaArtwork(
                    url: coverUrl,
                    fallbackIcon: YoinSymbols.musicNote,
                    radius: ShapeTokens.thumb,
                    decodeWidth: (44 * dpr).round(),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Row(
                        children: [
                          HorizontalSpringReveal(
                            visible: play.current,
                            child: Padding(
                              padding: const EdgeInsetsDirectional.only(end: 6),
                              child: EqualizerGlyph(playing: play.playing, color: page.accent),
                            ),
                          ),
                          Expanded(
                            child: SpringColorBuilder(
                              color: play.current ? page.accent : colors.onSurface,
                              builder: (color) => Text(
                                track.title ?? '',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: text.titleMedium?.copyWith(color: color),
                              ),
                            ),
                          ),
                        ],
                      ),
                      MetaLine(
                        groups: [
                          if (track.artist?.isNotEmpty ?? false) MetaPlain(track.artist!),
                          if (track.album?.isNotEmpty ?? false) MetaMuted(track.album!),
                        ],
                      ),
                    ],
                  ),
                ),
                if (duration.isNotEmpty) ...[
                  const SizedBox(width: 12),
                  Text(
                    duration,
                    style: text.labelLarge?.copyWith(color: colors.onSurfaceVariant, fontFeatures: _tabular),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
