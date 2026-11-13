import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../theme/motion.dart';
import '../../theme/tokens.dart';
import 'equalizer_glyph.dart';
import 'media_artwork.dart';
import 'meta_line.dart';

/// Unfolds [child] horizontally from the start + fades it (Compose
/// expandHorizontally + fadeIn, Standard role): width on the standard
/// default spatial spring, alpha on the effects spring.
class HorizontalSpringReveal extends StatefulWidget {
  const HorizontalSpringReveal({super.key, required this.visible, required this.child});

  final bool visible;
  final Widget child;

  @override
  State<HorizontalSpringReveal> createState() => _HorizontalSpringRevealState();
}

class _HorizontalSpringRevealState extends State<HorizontalSpringReveal> with TickerProviderStateMixin {
  late final SpringValue _size = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.standardDefaultSpatial,
  );
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(HorizontalSpringReveal old) {
    super.didUpdateWidget(old);
    _size.animateTo(widget.visible ? 1 : 0);
    _alpha.animateTo(widget.visible ? 1 : 0);
  }

  @override
  void dispose() {
    _size.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: Listenable.merge([_size.animation, _alpha.animation]),
    child: widget.child,
    builder: (context, child) {
      final w = _size.value.clamp(0.0, 1.5);
      if (w <= 0.001 && !widget.visible) return const SizedBox.shrink();
      return ClipRect(
        child: Align(
          alignment: AlignmentDirectional.centerStart,
          widthFactor: w,
          child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
        ),
      );
    },
  );
}

/// The shared song row (component/SongListItem.kt): 48dp thumb, title +
/// meta line, duration. Current = title in primary (effects spring) with
/// an equalizer unfolding before it. No heart (Android rows have none).
class SongListItem extends StatelessWidget {
  const SongListItem({
    super.key,
    required this.title,
    required this.coverUrl,
    this.artist,
    this.album,
    this.durationSec,
    this.isCurrent = false,
    this.isPlaying = false,
    this.onTap,
    this.decodeWidth,
    this.nowPlayingLabel,
  });

  final String title;
  final String? coverUrl;
  final String? artist;
  final String? album;
  final int? durationSec;
  final bool isCurrent;
  final bool isPlaying;
  final VoidCallback? onTap;
  final int? decodeWidth;
  final String? nowPlayingLabel;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final text = theme.textTheme;
    final duration = formatTrackDuration(durationSec);
    return Material(
      type: MaterialType.transparency,
      child: InkWell(
        onTap: onTap,
        customBorder: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.listRow)),
        child: Padding(
          padding: const EdgeInsets.symmetric(
            horizontal: LibraryTokens.rowPaddingH,
            vertical: LibraryTokens.rowPaddingV,
          ),
          child: Row(
            children: [
              SizedBox.square(
                dimension: LibraryTokens.rowThumb,
                child: MediaArtwork(
                  url: coverUrl,
                  fallbackIcon: YoinSymbols.musicNote,
                  radius: ShapeTokens.thumb,
                  decodeWidth: decodeWidth,
                ),
              ),
              const SizedBox(width: LibraryTokens.rowGap),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      children: [
                        HorizontalSpringReveal(
                          visible: isCurrent,
                          child: Padding(
                            padding: const EdgeInsetsDirectional.only(end: 6),
                            child: EqualizerGlyph(playing: isPlaying, semanticsLabel: nowPlayingLabel),
                          ),
                        ),
                        Expanded(
                          child: SpringColorBuilder(
                            color: isCurrent ? colors.primary : colors.onSurface,
                            builder: (color) => Text(
                              title,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: text.titleSmall?.copyWith(color: color),
                            ),
                          ),
                        ),
                      ],
                    ),
                    if ((artist?.isNotEmpty ?? false) || (album?.isNotEmpty ?? false))
                      MetaLine(
                        groups: [
                          if (artist?.isNotEmpty ?? false) MetaPlain(artist!),
                          if (album?.isNotEmpty ?? false) MetaMuted(album!),
                        ],
                      ),
                  ],
                ),
              ),
              if (duration.isNotEmpty) ...[
                const SizedBox(width: 6),
                Text(
                  duration,
                  style: text.labelLarge?.copyWith(
                    color: colors.onSurfaceVariant,
                    fontFeatures: const [FontFeature.tabularFigures()],
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

/// A colour that springs to [color] on the effects spring (Compose
/// animateColorAsState with the effects spec), rebuilding only [builder].
class SpringColorBuilder extends StatefulWidget {
  const SpringColorBuilder({super.key, required this.color, required this.builder});

  final Color color;
  final Widget Function(Color color) builder;

  @override
  State<SpringColorBuilder> createState() => _SpringColorBuilderState();
}

class _SpringColorBuilderState extends State<SpringColorBuilder> with TickerProviderStateMixin {
  late Color _from = widget.color;
  late Color _to = widget.color;
  late final SpringValue _t = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);

  @override
  void didUpdateWidget(SpringColorBuilder old) {
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
    builder: (context, _) => widget.builder(Color.lerp(_from, _to, _t.value.clamp(0.0, 1.0))!),
  );
}
