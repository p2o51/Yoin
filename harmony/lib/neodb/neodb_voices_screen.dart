/// "别人怎么说": what listeners on NeoDB wrote about an album (Android
/// NeoDbVoicesActivity + NeoDbVoicesScreen; owner 2026-11-09: a reference
/// with a bit of a social feel, never shown unasked). Opened only from the
/// small "What others say" button on the rate sheet's NeoDB line and on the
/// Memories diary's.
///
/// Back surface: PushPage. A full-window page with no shared chrome (Android
/// runs it as its own Activity), pushed with [YoinPushRoute] on the root
/// navigator; the top bar's back button pops through the same path as the
/// system back.
///
/// Top to bottom: the score spread (five columns), the average and how many
/// rated, other catalogues' scores, then what people wrote (long words across
/// the width, short ones two by two), and at the bottom the ways out: NeoDB,
/// Douban, RYM, AOTY. Nothing here compares anyone with the user.
library;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/neodb/neodb_voices.dart';
import '../detail/detail_page.dart' show DetailBackButton;
import '../detail/detail_palette.dart';
import '../home/widgets/spring_height.dart';
import '../nowplaying/haptics.dart';
import '../settings/settings_components.dart' show SpringReveal;
import '../shell/bar_suppression.dart';
import '../theme/color_scheme.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/artwork_image.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/component/yoin_loading_indicator.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'neodb_providers.dart';
import 'neodb_strings.dart';
import 'neodb_voices_view_model.dart';

/// Opens the page over everything (the rate sheet stays underneath, as the
/// Android Activity leaves it).
Future<void> openNeoDbVoices(BuildContext context, {required String albumName, String? artistName, String? coverUrl}) {
  final name = albumName.trim();
  if (name.isEmpty) return Future.value();
  final artist = artistName?.trim();
  return Navigator.of(context, rootNavigator: true).push(
    YoinPushRoute<void>(
      builder: (_) => NeoDbVoicesScreen(
        albumName: name,
        artistName: artist == null || artist.isEmpty ? null : artist,
        coverUrl: coverUrl,
      ),
    ),
  );
}

class NeoDbVoicesScreen extends ConsumerWidget {
  const NeoDbVoicesScreen({super.key, required this.albumName, this.artistName, this.coverUrl});

  final String albumName;
  final String? artistName;
  final String? coverUrl;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final query = (albumName: albumName, artistName: artistName);
    final state = ref.watch(neoDbVoicesViewModelProvider(query));
    final vm = ref.read(neoDbVoicesViewModelProvider(query).notifier);
    final openLink = ref.read(neoDbOpenLinkProvider);
    void open(String url) {
      // Only a web address is handed to the browser, never another scheme.
      if (!NeoDbVoicesParsing.isWebLink(url)) return;
      final uri = Uri.tryParse(url);
      if (uri != null) openLink(uri);
    }

    return ChromeBarSuppression(
      // The page wears the album's colours, like the album page.
      child: _CoverTheme(
        coverUrl: coverUrl,
        child: NeoDbVoicesView(
          state: state,
          albumName: albumName,
          artistName: artistName,
          onRetry: vm.load,
          onLoadMore: vm.loadMore,
          onOpenUrl: open,
        ),
      ),
    );
  }
}

/// The page itself, stateless over its [state] (previews and tests pump it).
class NeoDbVoicesView extends StatelessWidget {
  const NeoDbVoicesView({
    super.key,
    required this.state,
    required this.albumName,
    this.artistName,
    required this.onRetry,
    required this.onLoadMore,
    required this.onOpenUrl,
  });

  final NeoDbVoicesUiState state;
  final String albumName;
  final String? artistName;
  final VoidCallback onRetry;
  final VoidCallback onLoadMore;
  final ValueChanged<String> onOpenUrl;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = NeoDbStrings.of(context);
    final padding = MediaQuery.paddingOf(context);
    final current = state;
    final content = current is NeoDbVoicesContent ? current : null;
    return Material(
      color: colors.surface,
      // A landscape phone: the camera cutout and a side bar stay clear of the page.
      child: SafeArea(
        top: false,
        bottom: false,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: EdgeInsets.only(top: padding.top),
              child: _TopBar(title: strings.voicesTitle, albumName: albumName),
            ),
            Expanded(
              child: SpringCrossFade(
                childKey: current.runtimeType,
                child: switch (current) {
                  NeoDbVoicesLoading() => const Center(child: YoinLoadingIndicator()),
                  NeoDbVoicesFailed() => _Message(text: strings.voicesFailed, action: strings.retry, onAction: onRetry),
                  NeoDbVoicesNotFound(:final searchUrl) => _Message(
                    text: strings.voicesNotFound,
                    action: strings.voicesSearch,
                    onAction: () => onOpenUrl(searchUrl),
                  ),
                  NeoDbVoicesContent() => _VoicesList(state: current, onLoadMore: onLoadMore, onOpenUrl: onOpenUrl),
                },
              ),
            ),
            _PageColumn(
              child: Padding(
                padding: EdgeInsets.fromLTRB(
                  NeoDbTokens.sidePadding,
                  NeoDbTokens.waysOutTop,
                  NeoDbTokens.sidePadding,
                  NeoDbTokens.waysOutBottom + padding.bottom,
                ),
                child: _WaysOut(
                  neoDbUrl: content?.album.itemUrl,
                  doubanUrl: content?.album.doubanUrl,
                  rymUrl: NeoDbVoicesParsing.rymSearchUrl(albumName, artistName),
                  aotyUrl: NeoDbVoicesParsing.aotySearchUrl(albumName, artistName),
                  onOpenUrl: onOpenUrl,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The album's cover scheme over the page (rememberCoverColorScheme), the app
/// theme until the cover's seed is known.
class _CoverTheme extends StatefulWidget {
  const _CoverTheme({required this.coverUrl, required this.child});

  final String? coverUrl;
  final Widget child;

  @override
  State<_CoverTheme> createState() => _CoverThemeState();
}

class _CoverThemeState extends State<_CoverTheme> {
  Color? _seed;

  /// One scheme per seed and brightness: a new ColorScheme instance rebuilds
  /// every Theme reader, and the page rebuilds on each state change.
  ColorScheme? _scheme;
  Brightness? _schemeBrightness;

  @override
  void initState() {
    super.initState();
    final url = widget.coverUrl;
    if (url == null) return;
    // The album page read it already: the page opens in its colours.
    _seed = cachedCoverSeed(url);
    if (_seed != null) return;
    coverSeedColor(url).then((seed) {
      if (mounted && seed != null) setState(() => _seed = seed);
    });
  }

  @override
  Widget build(BuildContext context) {
    final seed = _seed;
    if (seed == null) return widget.child;
    final theme = Theme.of(context);
    if (_scheme == null || _schemeBrightness != theme.brightness) {
      _scheme = yoinExpressiveScheme(seed, theme.brightness);
      _schemeBrightness = theme.brightness;
    }
    return Theme(
      data: theme.copyWith(colorScheme: _scheme),
      child: widget.child,
    );
  }
}

/// A tablet doesn't stretch the page across the window: its column, centred.
class _PageColumn extends StatelessWidget {
  const _PageColumn({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => Align(
    alignment: Alignment.topCenter,
    child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: NeoDbTokens.pageMaxWidth),
      child: child,
    ),
  );
}

class _TopBar extends StatelessWidget {
  const _TopBar({required this.title, required this.albumName});

  final String title;
  final String albumName;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: NeoDbTokens.topBarPaddingH, vertical: NeoDbTokens.topBarPaddingV),
      child: Row(
        children: [
          const DetailBackButton(),
          const SizedBox(width: NeoDbTokens.topBarGap),
          Expanded(
            child: Semantics(
              header: true,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(title, style: text.labelMedium?.copyWith(color: colors.onSurfaceVariant)),
                  Text(
                    albumName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: text.titleMedium?.copyWith(color: colors.onSurface, fontWeight: FontWeight.w600),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message({required this.text, required this.action, required this.onAction});

  final String text;
  final String action;
  final VoidCallback onAction;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.all(NeoDbTokens.messagePadding),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              text,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: colors.onSurfaceVariant),
            ),
            TextButton(onPressed: onAction, child: Text(action)),
          ],
        ),
      ),
    );
  }
}

/// One stretch of the list: a full-width block, or short voices two by two.
sealed class _Segment {
  const _Segment();
}

final class _Full extends _Segment {
  const _Full(this.key, this.builder);
  final String key;
  final WidgetBuilder builder;
}

final class _Pairs extends _Segment {
  const _Pairs(this.indexes);
  final List<int> indexes;
}

class _VoicesList extends StatefulWidget {
  const _VoicesList({required this.state, required this.onLoadMore, required this.onOpenUrl});

  final NeoDbVoicesContent state;
  final VoidCallback onLoadMore;
  final ValueChanged<String> onOpenUrl;

  @override
  State<_VoicesList> createState() => _VoicesListState();
}

class _VoicesListState extends State<_VoicesList> {
  final _scroll = ScrollController();
  final _unfolded = <String>{};

  /// The voices that were there when the list first showed: later pages fade in, these don't.
  late final Set<String> _initial = {for (final v in widget.state.voices) v.id};

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  /// The next page once the list's end comes near (is built).
  void _nearEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) widget.onLoadMore();
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    final strings = NeoDbStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final voices = state.voices;
    final segments = <_Segment>[
      _Full('spread', (_) => _Spread(album: state.album)),
      // The other catalogues come in after the words: they unfold, the cards give way on a spring.
      _Full(
        'references',
        (_) => SpringReveal(
          visible: state.references.isNotEmpty,
          child: state.references.isEmpty
              ? null
              : Padding(
                  padding: const EdgeInsets.only(top: NeoDbTokens.gridGap),
                  child: _References(references: state.references, onOpenUrl: widget.onOpenUrl),
                ),
        ),
      ),
      if (voices.isEmpty)
        _Full(
          'empty',
          (_) => Padding(
            padding: const EdgeInsets.symmetric(vertical: NeoDbTokens.emptyPaddingV),
            child: Text(
              strings.voicesEmpty,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: colors.onSurfaceVariant),
            ),
          ),
        ),
    ];
    var run = <int>[];
    void flush() {
      if (run.isNotEmpty) segments.add(_Pairs(run));
      run = <int>[];
    }

    for (var i = 0; i < voices.length; i++) {
      if (voices[i].isLong) {
        flush();
        final index = i;
        segments.add(_Full('voice:${voices[i].id}', (_) => _card(index)));
      } else {
        run.add(i);
      }
    }
    flush();
    segments.add(_Full('tail', (_) => _tail(context)));

    return _PageColumn(
      child: ListView.builder(
        controller: _scroll,
        padding: const EdgeInsets.fromLTRB(
          NeoDbTokens.sidePadding,
          NeoDbTokens.listTop,
          NeoDbTokens.sidePadding,
          NeoDbTokens.listBottom,
        ),
        itemCount: segments.length,
        itemBuilder: (context, index) {
          final segment = segments[index];
          final gap = index == 0 || (segment is _Full && segment.key == 'references') ? 0.0 : NeoDbTokens.gridGap;
          final Widget child = switch (segment) {
            _Full(:final builder) => builder(context),
            _Pairs(:final indexes) => _TwoLanes(
              gap: NeoDbTokens.gridGap,
              children: [for (final i in indexes) _card(i)],
            ),
          };
          return Padding(
            key: segment is _Full
                ? ValueKey(segment.key)
                : ValueKey('pairs:${voices[segment is _Pairs ? segment.indexes.first : 0].id}'),
            padding: EdgeInsets.only(top: gap),
            child: child,
          );
        },
      ),
    );
  }

  Widget _card(int index) {
    final voice = widget.state.voices[index];
    final card = _VoiceCard(
      key: ValueKey('voice:${voice.id}'),
      voice: voice,
      lifted: index % 3 == 0,
      unfolded: _unfolded.contains(voice.id),
      onUnfold: () => setState(() => _unfolded.add(voice.id)),
      onOpen: () {
        final url = voice.url;
        if (url != null) widget.onOpenUrl(url);
      },
    );
    // Keyed like the card: a lane's children keep their state as later pages append.
    return _initial.contains(voice.id) ? card : _FadeIn(key: ValueKey('fade:${voice.id}'), child: card);
  }

  Widget _tail(BuildContext context) {
    final state = widget.state;
    final colors = Theme.of(context).colorScheme;
    final strings = NeoDbStrings.of(context);
    if (!state.loadingMore && !state.endReached && !state.moreFailed) _nearEnd();
    final Widget child;
    if (state.loadingMore) {
      child = const YoinLoadingIndicator(size: NeoDbTokens.tailIndicator);
    } else if (state.moreFailed) {
      child = TextButton(onPressed: widget.onLoadMore, child: Text(strings.retry));
    } else if (state.voices.isNotEmpty) {
      child = Text(
        strings.voicesCount(state.count),
        style: Theme.of(context).textTheme.labelMedium?.copyWith(color: colors.onSurfaceVariant),
      );
    } else {
      child = const SizedBox.shrink();
    }
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: NeoDbTokens.tailPaddingV),
      child: Center(
        child: SpringCrossFade(childKey: child.runtimeType, fit: StackFit.loose, child: child),
      ),
    );
  }
}

/// Fades a card in once on the effects spring (a later page's cards arriving).
class _FadeIn extends StatefulWidget {
  const _FadeIn({super.key, required this.child});

  final Widget child;

  @override
  State<_FadeIn> createState() => _FadeInState();
}

class _FadeInState extends State<_FadeIn> with SingleTickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  @override
  void initState() {
    super.initState();
    _alpha.animateTo(1);
  }

  @override
  void dispose() {
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (MediaQuery.disableAnimationsOf(context)) return widget.child;
    return FadeTransition(opacity: _alpha.animation.drive(Tween(begin: 0.0, end: 1.0)), child: widget.child);
  }
}

/// Five columns, one per star count; the tallest in the accent. Then the average, small.
class _Spread extends StatefulWidget {
  const _Spread({required this.album});

  final NeoDbAlbumVoices album;

  @override
  State<_Spread> createState() => _SpreadState();
}

class _SpreadState extends State<_Spread> with SingleTickerProviderStateMixin {
  late final SpringValue _grow = SpringValue(vsync: this, initial: 0, spring: YoinSprings.slowSpatial);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (MediaQuery.disableAnimationsOf(context)) {
        _grow.snapTo(1);
      } else {
        _grow.animateTo(1);
      }
    });
  }

  @override
  void dispose() {
    _grow.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final album = widget.album;
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NeoDbStrings.of(context);
    final distribution = album.distribution;
    final top = distribution.isEmpty ? 1 : distribution.reduce((a, b) => a > b ? a : b).clamp(1, 1 << 30);
    final rating = album.rating;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: NeoDbTokens.spreadPaddingV),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (distribution.isNotEmpty) ...[
            Row(
              children: [
                for (var i = 0; i < distribution.length; i++) ...[
                  if (i > 0) const SizedBox(width: NeoDbTokens.spreadColumnGap),
                  Expanded(
                    child: Semantics(
                      label: strings.voicesSpreadCd(i + 1, distribution[i]),
                      excludeSemantics: true,
                      child: Column(
                        children: [
                          // The bar stands in a slot of its own height, so the labels under
                          // it sit on one line in every column at any font size.
                          SizedBox(
                            height: NeoDbTokens.spreadBarMax,
                            child: Align(
                              alignment: Alignment.bottomCenter,
                              child: AnimatedBuilder(
                                animation: _grow.animation,
                                builder: (context, _) {
                                  final fraction = distribution[i] / top * _grow.value.clamp(0.0, 1.2);
                                  final h = (NeoDbTokens.spreadBarMax * fraction).clamp(
                                    NeoDbTokens.spreadBarMin,
                                    NeoDbTokens.spreadBarMax * 1.2,
                                  );
                                  return DecoratedBox(
                                    decoration: BoxDecoration(
                                      color: distribution[i] == top ? colors.primary : colors.secondaryContainer,
                                      borderRadius: const BorderRadius.vertical(
                                        top: Radius.circular(NeoDbTokens.spreadBarTopCorner),
                                        bottom: Radius.circular(NeoDbTokens.spreadBarBottomCorner),
                                      ),
                                    ),
                                    child: SizedBox(width: double.infinity, height: h.toDouble()),
                                  );
                                },
                              ),
                            ),
                          ),
                          const SizedBox(height: NeoDbTokens.spreadLabelGap),
                          NeoDbGrade(rating: (i + 1) * 2, color: colors.tertiary),
                          const SizedBox(height: NeoDbTokens.spreadLabelGap),
                          Text(
                            strings.voicesPercent(distribution[i]),
                            style: text.labelSmall?.copyWith(color: colors.onSurfaceVariant),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ],
            ),
            const SizedBox(height: NeoDbTokens.spreadGap),
          ],
          Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              if (rating != null) ...[
                Text(
                  rating.toStringAsFixed(1),
                  style: text.titleLarge?.copyWith(color: colors.onSurface, fontWeight: FontWeight.w600),
                ),
                const SizedBox(width: NeoDbTokens.spreadAverageGap),
              ],
              Padding(
                padding: const EdgeInsets.only(bottom: NeoDbTokens.spreadRatedBottom),
                child: Text(
                  strings.voicesRated(album.ratingCount),
                  style: text.bodySmall?.copyWith(color: colors.onSurfaceVariant),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _References extends StatelessWidget {
  const _References({required this.references, required this.onOpenUrl});

  final List<NeoDbReferenceScore> references;
  final ValueChanged<String> onOpenUrl;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NeoDbStrings.of(context);
    const shape = RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(NeoDbTokens.referenceCorner)));
    return Wrap(
      spacing: NeoDbTokens.gridGap,
      runSpacing: NeoDbTokens.gridGap,
      children: [
        for (final ref in references)
          Material(
            color: colors.surfaceContainerHigh,
            shape: shape,
            child: InkWell(
              customBorder: shape,
              onTap: () => onOpenUrl(ref.url),
              child: ConstrainedBox(
                constraints: const BoxConstraints(minWidth: NeoDbTokens.referenceMinWidth),
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: NeoDbTokens.referencePaddingH,
                    vertical: NeoDbTokens.referencePaddingV,
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(
                            ref.value.toStringAsFixed(2),
                            style: text.titleMedium?.copyWith(fontWeight: FontWeight.w600, color: colors.onSurface),
                          ),
                          Padding(
                            padding: const EdgeInsets.only(bottom: NeoDbTokens.referenceScaleBottom),
                            child: Text(
                              strings.voicesScale(ref.scale),
                              style: text.labelSmall?.copyWith(color: colors.onSurfaceVariant),
                            ),
                          ),
                        ],
                      ),
                      Text(ref.source, style: text.labelSmall?.copyWith(color: colors.onSurfaceVariant)),
                    ],
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _VoiceCard extends StatelessWidget {
  const _VoiceCard({
    super.key,
    required this.voice,
    required this.lifted,
    required this.unfolded,
    required this.onUnfold,
    required this.onOpen,
  });

  final NeoDbVoice voice;
  final bool lifted;
  final bool unfolded;
  final VoidCallback onUnfold;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NeoDbStrings.of(context);
    final folded = voice.folded && !unfolded;
    const shape = RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(NeoDbTokens.cardCorner)));
    final avatar = voice.avatarUrl;
    return Material(
      color: lifted ? colors.surfaceContainerHigh : colors.surfaceContainer,
      shape: shape,
      child: InkWell(
        customBorder: shape,
        onTap: () {
          YoinHaptics.click();
          folded ? onUnfold() : onOpen();
        },
        child: SpringHeight(
          heightKey: folded,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(
              NeoDbTokens.cardPaddingH,
              NeoDbTokens.cardPaddingTop,
              NeoDbTokens.cardPaddingH,
              NeoDbTokens.cardPaddingBottom,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (folded)
                  Text(
                    voice.foldNote ?? strings.voicesFolded,
                    style: text.bodyMedium?.copyWith(color: colors.onSurfaceVariant),
                  )
                else ...[
                  if (voice.reviewTitle != null)
                    Text(
                      voice.reviewTitle!,
                      style: text.titleMedium?.copyWith(fontWeight: FontWeight.w600, color: colors.onSurface),
                    ),
                  if (voice.reviewTitle != null && voice.text.isNotEmpty) const SizedBox(height: NeoDbTokens.cardGap),
                  if (voice.text.isNotEmpty)
                    Text(
                      voice.text,
                      style: (voice.isLong ? text.bodyLarge : text.bodyMedium)?.copyWith(color: colors.onSurface),
                    ),
                ],
                const SizedBox(height: NeoDbTokens.cardGap),
                Row(
                  children: [
                    // A circle painted with the picture (no clip layer).
                    Container(
                      width: NeoDbTokens.avatar,
                      height: NeoDbTokens.avatar,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: colors.secondaryContainer,
                        image: avatar == null || !NeoDbVoicesParsing.isWebLink(avatar)
                            ? null
                            : DecorationImage(
                                image: artworkImageProvider(avatar, cacheWidth: 64, cacheHeight: 64),
                                fit: BoxFit.cover,
                                onError: (_, _) {},
                              ),
                      ),
                    ),
                    const SizedBox(width: NeoDbTokens.bylineGap),
                    Expanded(
                      child: Text(
                        voice.byline,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: text.labelSmall?.copyWith(color: colors.onSurfaceVariant),
                      ),
                    ),
                    if (voice.reviewTitle != null) ...[
                      const SizedBox(width: NeoDbTokens.bylineGap),
                      Text(strings.voicesReview, style: text.labelSmall?.copyWith(color: colors.onSurfaceVariant)),
                    ],
                    if (voice.rating != null) ...[
                      const SizedBox(width: NeoDbTokens.bylineGap),
                      NeoDbGrade(rating: voice.rating!, color: colors.tertiary),
                    ],
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// A NeoDB grade (1–10) as stars out of five: a filled star beside the number.
class NeoDbGrade extends StatelessWidget {
  const NeoDbGrade({super.key, required this.rating, required this.color});

  final int rating;
  final Color color;

  /// 8 → "4", 7 → "3.5".
  static String label(int rating) => rating.isEven ? '${rating ~/ 2}' : (rating / 2).toStringAsFixed(1);

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Icon(YoinSymbols.starFilled, size: NeoDbTokens.grade, color: color),
      const SizedBox(width: NeoDbTokens.gradeGap),
      Text(label(rating), style: Theme.of(context).textTheme.labelSmall?.copyWith(color: color)),
    ],
  );
}

/// NeoDB filled (the one final action), the other catalogues tonal beside
/// each other. NeoDB's and Douban's links come with the item, after the
/// page is up: NeoDB's button unfolds on a spring and Douban's grows into
/// the row while the two beside it slide over to thirds.
class _WaysOut extends StatefulWidget {
  const _WaysOut({
    required this.neoDbUrl,
    required this.doubanUrl,
    required this.rymUrl,
    required this.aotyUrl,
    required this.onOpenUrl,
  });

  final String? neoDbUrl;
  final String? doubanUrl;
  final String rymUrl;
  final String aotyUrl;
  final ValueChanged<String> onOpenUrl;

  @override
  State<_WaysOut> createState() => _WaysOutState();
}

class _WaysOutState extends State<_WaysOut> with TickerProviderStateMixin {
  late final SpringValue _doubanShare = SpringValue(
    vsync: this,
    initial: widget.doubanUrl != null ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _doubanAlpha = SpringValue(
    vsync: this,
    initial: widget.doubanUrl != null ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  /// Kept while Douban's button folds away.
  String? _lastDouban;

  @override
  void didUpdateWidget(_WaysOut old) {
    super.didUpdateWidget(old);
    final target = widget.doubanUrl != null ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _doubanShare.snapTo(target);
      _doubanAlpha.snapTo(target);
    } else {
      _doubanShare.animateTo(target);
      _doubanAlpha.animateTo(target);
    }
  }

  @override
  void dispose() {
    _doubanShare.dispose();
    _doubanAlpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = NeoDbStrings.of(context);
    final neoDb = widget.neoDbUrl;
    _lastDouban = widget.doubanUrl ?? _lastDouban;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        SpringReveal(
          visible: neoDb != null,
          child: neoDb == null
              ? null
              : Padding(
                  // The gap to the row below is the button's own, so it opens and closes with it.
                  padding: const EdgeInsets.only(bottom: NeoDbTokens.waysOutGap),
                  child: SizedBox(
                    height: NeoDbTokens.openNeoDbHeight,
                    child: FilledButton(
                      onPressed: () {
                        YoinHaptics.click();
                        widget.onOpenUrl(neoDb);
                      },
                      child: Text(strings.voicesOpenNeoDb),
                    ),
                  ),
                ),
        ),
        LayoutBuilder(
          builder: (context, constraints) => AnimatedBuilder(
            animation: Listenable.merge([_doubanShare.animation, _doubanAlpha.animation]),
            builder: (context, _) {
              // Douban's share of the row, 0 to 1: its button and its gap grow from nothing.
              final share = _doubanShare.value.clamp(0.0, 1.0);
              const gap = NeoDbTokens.waysOutGap;
              final unit = (constraints.maxWidth - gap - gap * share) / (2 + share);
              final douban = _lastDouban;
              return Row(
                children: [
                  if (share > 0.001 && douban != null) ...[
                    SizedBox(
                      width: unit * share,
                      child: Opacity(
                        opacity: _doubanAlpha.value.clamp(0.0, 1.0),
                        child: _OtherWayOut(label: strings.voicesDouban, onTap: () => widget.onOpenUrl(douban)),
                      ),
                    ),
                    SizedBox(width: gap * share),
                  ],
                  SizedBox(
                    width: unit,
                    child: _OtherWayOut(label: strings.voicesRym, onTap: () => widget.onOpenUrl(widget.rymUrl)),
                  ),
                  const SizedBox(width: gap),
                  SizedBox(
                    width: unit,
                    child: _OtherWayOut(label: strings.voicesAoty, onTap: () => widget.onOpenUrl(widget.aotyUrl)),
                  ),
                ],
              );
            },
          ),
        ),
      ],
    );
  }
}

class _OtherWayOut extends StatelessWidget {
  const _OtherWayOut({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => SizedBox(
    height: NeoDbTokens.otherWayOutHeight,
    child: FilledButton.tonal(
      style: FilledButton.styleFrom(padding: EdgeInsets.zero, minimumSize: Size.zero),
      onPressed: () {
        YoinHaptics.click();
        onTap();
      },
      child: Text(label, maxLines: 1, softWrap: false, overflow: TextOverflow.fade),
    ),
  );
}

/// Short voices in two lanes, each laid into the shorter lane (Compose's
/// staggered grid): the lanes stay even however long each card is.
class _TwoLanes extends MultiChildRenderObjectWidget {
  const _TwoLanes({required this.gap, required super.children});

  final double gap;

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderTwoLanes(gap);

  @override
  void updateRenderObject(BuildContext context, _RenderTwoLanes renderObject) => renderObject.gap = gap;
}

class _LaneData extends ContainerBoxParentData<RenderBox> {}

class _RenderTwoLanes extends RenderBox
    with ContainerRenderObjectMixin<RenderBox, _LaneData>, RenderBoxContainerDefaultsMixin<RenderBox, _LaneData> {
  _RenderTwoLanes(this._gap);

  double _gap;
  set gap(double value) {
    if (value == _gap) return;
    _gap = value;
    markNeedsLayout();
  }

  @override
  void setupParentData(RenderBox child) {
    if (child.parentData is! _LaneData) child.parentData = _LaneData();
  }

  @override
  void performLayout() {
    final width = constraints.maxWidth;
    final lane = ((width - _gap) / 2).clamp(0.0, double.infinity);
    final heights = [0.0, 0.0];
    final used = [false, false];
    var child = firstChild;
    while (child != null) {
      child.layout(BoxConstraints.tightFor(width: lane), parentUsesSize: true);
      // The shorter lane takes the next card; equal lanes go left first.
      final l = heights[1] < heights[0] ? 1 : 0;
      final data = child.parentData! as _LaneData;
      data.offset = Offset(l == 0 ? 0 : lane + _gap, used[l] ? heights[l] + _gap : 0);
      heights[l] = data.offset.dy + child.size.height;
      used[l] = true;
      child = data.nextSibling;
    }
    size = constraints.constrain(Size(width, heights[0] > heights[1] ? heights[0] : heights[1]));
  }

  @override
  void paint(PaintingContext context, Offset offset) => defaultPaint(context, offset);

  @override
  bool hitTestChildren(BoxHitTestResult result, {required Offset position}) =>
      defaultHitTestChildren(result, position: position);
}
