/// Home, Memories and the home screen on the demo phone (Android ui/tips
/// TipHomeScenes.kt, TipMemoryLedgerScene.kt, TipWidgetsScene.kt): the real
/// avatar, covers, pill buttons, memory grid and the mark, over made-up
/// albums.
library;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/memory/memory_grid.dart';
import '../data/memory/memory_ledger.dart';
import '../data/model/model.dart';
import '../detail/detail_page.dart' show DetailBackButton;
import '../data/highlight/lyric_highlight.dart' show LyricHighlightBucket;
import '../home/home_edit.dart' show HomeEditPlate;
import '../home/home_feed_density.dart' show FeedFrameClass, HomeFeedDensity;
import '../home/home_lyric_count_handle.dart';
import '../home/home_metrics.dart' show HomeEditMetrics;
import '../home/home_section.dart' show HomeSection;
import '../home/home_selection.dart' show homeFeedFrame;
import '../home/home_strings.dart';
import '../home/lyric_highlight_section.dart';
import '../home/lyric_highlight_view_model.dart' show HomeLyricHighlight, lyricCardsFor;
import '../landing/landing_mascot.dart';
import '../memories/ledger/memory_grid_lift.dart';
import '../memories/ledger/memory_grid_view.dart';
import '../memories/ledger/memory_ledger_strings.dart';
import '../memories/memories_aurora.dart';
import '../memories/memories_strings.dart';
import '../memories/memories_ui_state.dart' show MemoryOtherProfile;
import '../memories/memory_card_face.dart' show MemoryPillButton;
import '../memories/memory_other_profile_line.dart';
import '../memories/other_profiles.dart' show AccountSwitchNoticeSheet, AccountSwitchPurpose;
import '../memories/palette/memory_palette.dart' show MemoryPalette, MemoryPaletteSamples, MemoryPaletteTones;
import '../settings/account_identity.dart';
import '../settings/settings_colors.dart';
import '../settings/settings_components.dart' show SettingsBar, SettingsHeadline;
import '../settings/settings_metrics.dart';
import '../shell/bar_edit_pose.dart';
import '../shell/bar_scroll_hide.dart';
import '../shell/yoin_bottom_bar.dart';
import '../shell/yoin_section.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/cover_art.dart';
import 'demo_data.dart';
import 'demo_phone.dart';
import 'tip_scene_parts.dart';
import 'tip_scenes_library.dart' show DemoSceneState;

/// One of Home's sections on the phone: its name, then a row of covers.
class _DemoSection extends StatelessWidget {
  const _DemoSection({required this.title, required this.covers, required this.editing, required this.lift});

  final String title;
  final List<DemoAlbum> covers;
  final bool editing;

  /// 0..1: the section is held up (edit mode's lift).
  final double lift;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Transform.scale(
      scale: 1 + (TipSceneTokens.liftScale - 1) * lift,
      child: DemoSpring(
        target: editing ? 1 : 0,
        spring: YoinSprings.defaultEffects,
        builder: (context, e) => DecoratedBox(
          decoration: ShapeDecoration(
            color: colors.surfaceContainer.withValues(alpha: e.clamp(0.0, 1.0)),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
          ),
          child: Padding(
            padding: const EdgeInsets.all(TipSceneTokens.sectionPadding),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(title, style: theme.textTheme.titleLarge!.copyWith(color: colors.onSurface)),
                    ),
                    Opacity(
                      opacity: e.clamp(0.0, 1.0),
                      child: Icon(YoinSymbols.dragHandle, color: colors.onSurfaceVariant),
                    ),
                  ],
                ),
                const SizedBox(height: TipSceneTokens.titleGap),
                Row(
                  children: [
                    for (final (i, album) in covers.indexed) ...[
                      if (i > 0) const SizedBox(width: TipSceneTokens.coverGap),
                      CoverArt(cover: album.coverRef, size: TipSceneTokens.sectionCover, radius: ShapeTokens.cover),
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

/// Home's top: the page's name and the account's avatar.
class _DemoHomeHeader extends StatelessWidget {
  const _DemoHomeHeader({required this.avatar});

  final Widget avatar;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        TipSceneTokens.pagePaddingH,
        TipSceneTokens.pageTop,
        TipSceneTokens.pagePaddingH,
        TipSceneTokens.titleGap,
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              HomeStrings.of(context).title,
              style: theme.textTheme.headlineMedium!.copyWith(color: theme.colorScheme.onSurface),
            ),
          ),
          avatar,
        ],
      ),
    );
  }
}

/// The made-up accounts: one Subsonic server and one Apple Music account, each its own shape.
const _accounts = [
  ('Mira', ServiceIdentity.subsonic, 0),
  ('Okto', ServiceIdentity.appleMusic, 1),
  ('Halden', ServiceIdentity.subsonic, 2),
];

Widget _avatar(BuildContext context, int account, {double size = TipSceneTokens.avatar}) {
  final (name, identity, shape) = _accounts[account];
  return AccountAvatar(
    monogram: monogramOf(name),
    shapeIndex: shape,
    identity: identity,
    ringColor: Theme.of(context).colorScheme.primary,
    size: size,
  );
}

const _sectionAlbums = [
  [0, 1, 2, 3],
  [4, 5, 0, 1],
  [2, 3, 4, 5],
];

/// Home in the phone, still: its header and three sections (a scene that
/// goes to Home and back draws the bar over it).
class DemoHomeFeed extends StatelessWidget {
  const DemoHomeFeed({super.key});

  @override
  Widget build(BuildContext context) {
    final strings = HomeStrings.of(context);
    final titles = [strings.sectionJumpBackIn, strings.sectionRecentlyAdded, strings.sectionRecentlyPlayed];
    return ColoredBox(
      color: Theme.of(context).colorScheme.surface,
      child: ListView(
        physics: const NeverScrollableScrollPhysics(),
        padding: EdgeInsets.zero,
        children: [
          _DemoHomeHeader(avatar: _avatar(context, 0)),
          for (final (i, title) in titles.indexed)
            Padding(
              padding: const EdgeInsets.fromLTRB(
                TipSceneTokens.pagePaddingH,
                0,
                TipSceneTokens.pagePaddingH,
                TipSceneTokens.titleGap,
              ),
              child: _DemoSection(
                title: title,
                covers: [for (final a in _sectionAlbums[i]) demoAlbums[a]],
                editing: false,
                lift: 0,
              ),
            ),
        ],
      ),
    );
  }
}

/// Long-press Home → edit mode; a section held up and dragged moves; Done leaves.
class EditHomeDemo extends StatefulWidget {
  const EditHomeDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<EditHomeDemo> createState() => _EditHomeDemoState();
}

class _EditHomeDemoState extends State<EditHomeDemo> with DemoSceneState {
  bool _editing = false;
  List<int> _order = [0, 1, 2];
  int? _held;
  double _dragDy = 0;

  Future<void> _script(DemoFinger f) async {
    put(() {
      _editing = false;
      _order = [0, 1, 2];
      _held = null;
      _dragDy = 0.0;
    });
    await f.wait(400);
    await f.moveTo('slot1', fy: 0.35);
    await f.hold(
      () => put(() {
        _editing = true;
        _held = 1;
      }),
    );
    final to = f.at('slot0', fy: 0.35);
    await f.drag(to.dx, to.dy, 900, onProgress: (p) => put(() => _dragDy = -TipSceneTokens.sectionSlot * p));
    put(() {
      _order = [1, 0, 2];
      _held = null;
      _dragDy = 0.0;
    });
    await f.wait(700);
    // Done is the bar's right half in the edit pose (BarEditPose).
    await f.moveTo('bar', fx: 0.75);
    await f.tap(() => put(() => _editing = false));
    await f.lift();
    await f.wait(1200);
  }

  @override
  Widget build(BuildContext context) {
    final strings = HomeStrings.of(context);
    final titles = [strings.sectionJumpBackIn, strings.sectionRecentlyAdded, strings.sectionRecentlyPlayed];
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: GestureDetector(
        onLongPress: () => put(() => _editing = true),
        child: ColoredBox(
          color: Theme.of(context).colorScheme.surface,
          child: Stack(
            fit: StackFit.expand,
            children: [
              Column(
                children: [
                  _DemoHomeHeader(avatar: _avatar(context, 0)),
                  Expanded(
                    child: Stack(
                      children: [
                        for (var slot = 0; slot < 3; slot++)
                          Positioned(
                            left: TipSceneTokens.pagePaddingH,
                            right: TipSceneTokens.pagePaddingH,
                            top: slot * TipSceneTokens.sectionSlot,
                            height: TipSceneTokens.sectionSlot,
                            child: IgnorePointer(child: DemoTarget('slot$slot', child: const SizedBox.expand())),
                          ),
                        for (final id in [..._order.where((id) => id != _held), ?_held])
                          DemoSpring(
                            key: ValueKey('section$id'),
                            target: _order.indexOf(id) * TipSceneTokens.sectionSlot,
                            builder: (context, top) => Positioned(
                              left: TipSceneTokens.pagePaddingH,
                              right: TipSceneTokens.pagePaddingH,
                              top: top + (id == _held ? _dragDy : 0),
                              child: _DemoSection(
                                title: titles[id],
                                covers: [for (final i in _sectionAlbums[id]) demoAlbums[i]],
                                editing: _editing,
                                lift: id == _held ? 1 : 0,
                              ),
                            ),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
              // The real bar: editing, its destinations become [Undo] [Done] (nothing to undo in the demo).
              Positioned(
                left: 0,
                right: 0,
                bottom: TipSceneTokens.barBottom,
                child: DemoTarget(
                  'bar',
                  child: YoinBottomBar(
                    selected: YoinSection.home,
                    onSelect: (_) {},
                    editPose: BarEditPose(
                      editing: _editing,
                      leftSlot: BarEditLeftSlot.undoDisabled,
                      onLeftSlot: () {},
                      onDone: () => put(() => _editing = false),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// A memory's card in the phone: the cover, the album, a line written about
/// it, and the real Diary and Go to album pills.
class _DemoMemoryCard extends StatelessWidget {
  const _DemoMemoryCard({
    required this.album,
    required this.onDiary,
    this.owner,
    this.targets = true,
    this.onGoAlbum,
    this.goTarget,
  });

  /// False for a second card on the same screen (a target names one part).
  final bool targets;

  final DemoAlbum album;
  final VoidCallback onDiary;

  /// Another account's memory: its "From …" line under the buttons.
  final MemoryOtherProfile? owner;

  /// Go to album's tap (in the phone), and the demo target it is when named.
  final VoidCallback? onGoAlbum;
  final String? goTarget;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = MemoriesStrings.of(context);
    final goTarget = this.goTarget;
    final go = MemoryPillButton(
      label: strings.cardGoAlbum,
      color: colors.secondaryContainer,
      onColor: colors.onSecondaryContainer,
      onTap: onGoAlbum ?? () {},
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(album.name, style: theme.textTheme.headlineMedium!.copyWith(color: colors.onSurface)),
        Text(album.artist, style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurfaceVariant)),
        const SizedBox(height: TipSceneTokens.titleGap),
        Text(
          strings.excerptQuote(demoMemoryExcerpt),
          style: theme.textTheme.bodyLarge!.copyWith(color: colors.onSurfaceVariant),
        ),
        const SizedBox(height: TipSceneTokens.albumTitleGap),
        Wrap(
          spacing: TipSceneTokens.coverGap,
          runSpacing: TipSceneTokens.coverGap,
          children: [
            _target(
              targets,
              'diary',
              MemoryPillButton(
                label: strings.cardDiary,
                color: colors.primary,
                onColor: colors.onPrimary,
                onTap: onDiary,
              ),
            ),
            _target(goTarget != null, goTarget ?? '', go),
          ],
        ),
        if (owner case final owner?) ...[
          const SizedBox(height: OtherProfileTokens.lineCardGap),
          _target(
            targets,
            'otherProfileLine',
            MemoryOtherProfileLine(
              owner: owner,
              tones: MemoryPaletteTones(
                ink: colors.onSurface,
                highlight: colors.primary,
                button: colors.secondaryContainer,
                onButton: colors.onSecondaryContainer,
                dot: colors.primary,
                tint: colors.surfaceContainer,
              ),
            ),
          ),
        ],
      ],
    );
  }
}

/// Memories as the phone shows it: the deck's aurora, the top bar, then the
/// card ⇄ diary by [diary] (0 card, 1 diary): the cover flies into the bar's
/// leading slot and the diary's lines rise behind the title.
class _DemoMemories extends StatelessWidget {
  const _DemoMemories({
    required this.album,
    required this.diary,
    required this.onDiary,
    this.owner,
    this.targets = true,
    this.onGoAlbum,
    this.goTarget,
    this.aurora = true,
  });

  /// False for a second deck page on the same screen (a target names one part).
  final bool targets;

  /// The page paints the deck's ground and aurora under itself; false where
  /// one aurora runs under several pages (the page is then see-through).
  final bool aurora;

  final DemoAlbum album;
  final double diary;
  final VoidCallback onDiary;

  /// Another account's memory (the card's "From …" line).
  final MemoryOtherProfile? owner;

  /// The card's Go to album (see [_DemoMemoryCard]).
  final VoidCallback? onGoAlbum;
  final String? goTarget;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = MemoriesStrings.of(context);
    final p = diary.clamp(0.0, 1.0);
    final cover = TipSceneTokens.memoryCover + (TipSceneTokens.barCover - TipSceneTokens.memoryCover) * p;
    final coverLeft =
        TipSceneTokens.pagePaddingH + (TipTokens.screenWidth - 2 * TipSceneTokens.pagePaddingH - cover) / 2 * (1 - p);
    final coverTop =
        (TipSceneTokens.barHeight + TipSceneTokens.titleGap) * (1 - p) +
        (TipSceneTokens.barHeight - TipSceneTokens.barCover) / 2 * p;
    return _DemoDeckGround(
      palettes: aurora ? (owner == null ? _ownAurora : _otherAurora) : null,
      child: Stack(
        children: [
          // The bar: "Memories", the album sliding in beside its cover once the diary is open.
          Positioned(
            left: 0,
            right: 0,
            top: 0,
            height: TipSceneTokens.barHeight,
            child: _target(
              targets,
              'memoriesBar',
              Padding(
                padding: EdgeInsets.only(
                  left: TipSceneTokens.pagePaddingH + (TipSceneTokens.barCover + TipSceneTokens.titleGap) * p,
                ),
                child: Align(
                  alignment: AlignmentDirectional.centerStart,
                  child: Text(
                    p > 0.5 ? album.name : strings.topbarMemories,
                    style: theme.textTheme.titleLarge!.copyWith(color: colors.onSurface),
                  ),
                ),
              ),
            ),
          ),
          // The card's text, giving way as the diary comes.
          Positioned(
            left: TipSceneTokens.pagePaddingH,
            right: TipSceneTokens.pagePaddingH,
            top:
                TipSceneTokens.barHeight +
                TipSceneTokens.titleGap +
                TipSceneTokens.memoryCover +
                TipSceneTokens.albumTitleGap,
            child: Opacity(
              opacity: (1 - p * 2).clamp(0.0, 1.0),
              child: Transform.translate(
                offset: Offset(0, TipSceneTokens.diaryRise * p),
                child: _DemoMemoryCard(
                  album: album,
                  onDiary: onDiary,
                  owner: owner,
                  targets: targets,
                  onGoAlbum: onGoAlbum,
                  goTarget: goTarget,
                ),
              ),
            ),
          ),
          // The diary's lines, rising behind the title.
          Positioned(
            left: TipSceneTokens.pagePaddingH,
            right: TipSceneTokens.pagePaddingH,
            top: TipSceneTokens.barHeight + TipSceneTokens.titleGap,
            child: Opacity(
              opacity: ((p - 0.4) / 0.6).clamp(0.0, 1.0),
              child: Transform.translate(
                offset: Offset(0, TipSceneTokens.diaryRise * (1 - p)),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(demoMemoryTitle, style: theme.textTheme.headlineSmall!.copyWith(color: colors.onSurface)),
                    const SizedBox(height: TipSceneTokens.titleGap),
                    for (final i in [1, 2, 3])
                      Padding(
                        padding: const EdgeInsets.only(bottom: TipSceneTokens.titleGap),
                        child: Text(
                          '${formatTrackStamp(i * 47)}  ${demoTrackTitles[i]}',
                          style: theme.textTheme.bodyLarge!.copyWith(color: colors.onSurfaceVariant),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
          Positioned(
            left: coverLeft,
            top: coverTop,
            child: CoverArt(
              cover: album.coverRef,
              size: cover,
              radius: ShapeTokens.hero + (ShapeTokens.thumb - ShapeTokens.hero) * p,
            ),
          ),
        ],
      ),
    );
  }
}

/// The deck's palettes in the phone (Android's demo: M1 for this account's
/// memory, M3 for another account's).
const _ownAurora = [MemoryPaletteSamples.m1];
const _otherAurora = [MemoryPaletteSamples.m3];

/// The two cards of the all-accounts deck, in order.
const _deckAurora = [MemoryPaletteSamples.m1, MemoryPaletteSamples.m3];

/// Under a demo Memories page: the deck's ground and aurora in [palettes],
/// or nothing (see-through) when null.
class _DemoDeckGround extends StatelessWidget {
  const _DemoDeckGround({required this.palettes, required this.child});

  final List<MemoryPalette>? palettes;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    final palettes = this.palettes;
    if (palettes == null) return child;
    return Stack(
      children: [
        Positioned.fill(child: _DemoAurora(palettes: palettes)),
        child,
      ],
    );
  }
}

/// The Memories deck's ground in the phone: the real aurora
/// ([MemoriesAurora]) over the surface, as Android's demo wears
/// memoriesAuroraBackground. [position] is the pager's page across
/// [palettes] (still at the first when null).
class _DemoAurora extends StatelessWidget {
  const _DemoAurora({required this.palettes, this.position});

  final List<MemoryPalette> palettes;
  final SpringValue? position;

  static double _first() => 0;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final position = this.position;
    return MemoriesAurora(
      palettes: palettes,
      position: position == null ? _first : () => position.value,
      repaint: position?.animation ?? kAlwaysCompleteAnimation,
      visible: true,
      dark: theme.brightness == Brightness.dark,
      ground: theme.colorScheme.surface,
    );
  }
}

/// [child] as the demo target [name], or as is when [on] is false.
Widget _target(bool on, String name, Widget child) => on ? DemoTarget(name, child: child) : child;

/// "m:ss" for a demo stamp.
String formatTrackStamp(int seconds) => '${seconds ~/ 60}:${(seconds % 60).toString().padLeft(2, '0')}';

/// Pull Home down → Memories comes down over it; a swipe up goes back.
class PullForMemoriesDemo extends StatefulWidget {
  const PullForMemoriesDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<PullForMemoriesDemo> createState() => _PullForMemoriesDemoState();
}

class _PullForMemoriesDemoState extends State<PullForMemoriesDemo> with DemoSceneState, TickerProviderStateMixin {
  late final SpringValue _open = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  @override
  void dispose() {
    _open.dispose();
    super.dispose();
  }

  Future<void> _script(DemoFinger f) async {
    _open.snapTo(0);
    await f.wait(500);
    await f.moveToPoint(0.5, 0.3);
    await f.drag(0.5, 0.55, 700, release: false, onProgress: (p) => _open.snapTo(p * TipSceneTokens.pullShare));
    _open.animateTo(1);
    await f.release();
    await f.lift();
    await f.wait(1800);
    await f.moveToPoint(0.5, 0.7);
    await f.drag(0.5, 0.35, 500, onProgress: (p) => _open.snapTo(1 - p * TipSceneTokens.pullShare));
    _open.animateTo(0);
    await f.lift();
    await f.wait(900);
  }

  @override
  Widget build(BuildContext context) {
    final strings = HomeStrings.of(context);
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: GestureDetector(
        onVerticalDragUpdate: (d) =>
            _open.snapTo((_open.value + d.delta.dy / TipSceneTokens.pullDistance).clamp(0.0, 1.0)),
        onVerticalDragEnd: (_) => _open.animateTo(_open.value > TipSceneTokens.pullShare / 2 ? 1 : 0),
        child: Stack(
          children: [
            ColoredBox(
              color: Theme.of(context).colorScheme.surface,
              child: Column(
                children: [
                  _DemoHomeHeader(avatar: _avatar(context, 0)),
                  for (final id in [0, 1])
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: TipSceneTokens.pagePaddingH),
                      child: _DemoSection(
                        title: [strings.sectionJumpBackIn, strings.sectionRecentlyAdded][id],
                        covers: [for (final i in _sectionAlbums[id]) demoAlbums[i]],
                        editing: false,
                        lift: 0,
                      ),
                    ),
                ],
              ),
            ),
            AnimatedBuilder(
              animation: _open.animation,
              builder: (context, child) =>
                  FractionalTranslation(translation: Offset(0, _open.value.clamp(0.0, 1.0) - 1), child: child),
              child: _DemoMemories(album: demoAlbums[0], diary: 0, onDiary: () {}),
            ),
          ],
        ),
      ),
    );
  }
}

/// Memories deals every account's memories (Android MemoriesAllAccountsDemo):
/// a swipe brings the next card, one from another account, which says whose
/// it is under its buttons (the real "From …" line). Its Go to album asks
/// first with the real one-off switch notice (All Memories opens another
/// account's album the same way since 2026-11-17); the phone's answer only
/// closes it.
class MemoriesAllAccountsDemo extends StatefulWidget {
  const MemoriesAllAccountsDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<MemoriesAllAccountsDemo> createState() => _MemoriesAllAccountsDemoState();
}

class _MemoriesAllAccountsDemoState extends State<MemoriesAllAccountsDemo>
    with DemoSceneState, TickerProviderStateMixin {
  /// 0 = this account's card, 1 = the next one, from another account.
  late final SpringValue _shift = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  /// The switch notice is up.
  bool _notice = false;

  @override
  void dispose() {
    _shift.dispose();
    super.dispose();
  }

  Future<void> _script(DemoFinger f) async {
    final confirm = MemoriesStrings.of(context).switchConfirmAlbum;
    _shift.snapTo(0);
    put(() => _notice = false);
    await f.wait(900);
    await f.moveToPoint(0.8, 0.45);
    await f.drag(0.25, 0.45, 600, release: false, onProgress: (p) => _shift.snapTo(p * TipSceneTokens.swipeTravel));
    _shift.animateTo(1);
    await f.release();
    await f.lift();
    await f.wait(1600);
    // Go to album on the other account's card: Yoin asks before it switches.
    await f.moveTo('otherGoAlbum');
    await f.tap(() => put(() => _notice = true));
    await f.lift();
    await f.wait(1800);
    await f.moveToText(confirm);
    await f.tap(() => put(() => _notice = false));
    await f.lift();
    await f.wait(1600);
  }

  @override
  Widget build(BuildContext context) {
    final (name, identity, shape) = _accounts[1];
    final owner = MemoryOtherProfile(
      id: 'demo-other',
      name: name,
      provider: identity == ServiceIdentity.appleMusic ? MediaId.providerAppleMusic : MediaId.providerSubsonic,
      shape: shape,
    );
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: GestureDetector(
        onHorizontalDragUpdate: (d) =>
            _shift.snapTo((_shift.value - d.delta.dx / TipTokens.screenWidth).clamp(0.0, 1.0)),
        onHorizontalDragEnd: (_) => _shift.animateTo(_shift.value > 0.5 ? 1 : 0),
        child: AnimatedBuilder(
          animation: _shift.animation,
          builder: (context, _) => ClipRect(
            child: Stack(
              children: [
                // One aurora under the deck, turned and re-tinted by the swipe as the pager does it.
                Positioned.fill(
                  child: _DemoAurora(palettes: _deckAurora, position: _shift),
                ),
                FractionalTranslation(
                  translation: Offset(-_shift.value, 0),
                  child: _DemoMemories(album: demoAlbums[0], diary: 0, onDiary: () {}, aurora: false),
                ),
                FractionalTranslation(
                  translation: Offset(1 - _shift.value, 0),
                  child: _DemoMemories(
                    album: demoAlbums[2],
                    diary: 0,
                    onDiary: () {},
                    owner: owner,
                    targets: false,
                    aurora: false,
                    onGoAlbum: () => put(() => _notice = true),
                    goTarget: 'otherGoAlbum',
                  ),
                ),
                DemoSheet(
                  open: _notice,
                  onDismiss: () => put(() => _notice = false),
                  child: AccountSwitchNoticeSheet(
                    owner: owner,
                    subject: demoAlbums[2].name,
                    purpose: AccountSwitchPurpose.openAlbum,
                    onAnswer: (_) => put(() => _notice = false),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// In Memories, Diary turns the card into its diary; dragging the bar down
/// scrubs it back to the card.
class MemoriesDiaryDemo extends StatefulWidget {
  const MemoriesDiaryDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<MemoriesDiaryDemo> createState() => _MemoriesDiaryDemoState();
}

class _MemoriesDiaryDemoState extends State<MemoriesDiaryDemo> with DemoSceneState, TickerProviderStateMixin {
  late final SpringValue _p = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);

  @override
  void dispose() {
    _p.dispose();
    super.dispose();
  }

  Future<void> _script(DemoFinger f) async {
    _p.snapTo(0);
    await f.wait(600);
    await f.moveTo('diary');
    await f.tap(() => _p.animateTo(1));
    await f.lift();
    await f.wait(1800);
    await f.moveTo('memoriesBar', fx: 0.6);
    await f.drag(
      f.at('memoriesBar', fx: 0.6).dx,
      0.45,
      800,
      release: false,
      onProgress: (p) => _p.snapTo(1 - p * TipSceneTokens.diaryDragShare),
    );
    _p.animateTo(0);
    await f.release();
    await f.lift();
    await f.wait(1200);
  }

  @override
  Widget build(BuildContext context) => DemoPhone(
    playing: widget.playing,
    script: _script,
    child: GestureDetector(
      onVerticalDragUpdate: (d) => _p.snapTo((_p.value - d.delta.dy / TipSceneTokens.pullDistance).clamp(0.0, 1.0)),
      onVerticalDragEnd: (_) => _p.animateTo(_p.value > 0.5 ? 1 : 0),
      child: AnimatedBuilder(
        animation: _p.animation,
        builder: (context, _) => _DemoMemories(album: demoAlbums[3], diary: _p.value, onDiary: () => _p.animateTo(1)),
      ),
    ),
  );
}

/// Long-press the avatar: the other accounts fan out; sliding onto one and
/// letting go switches to it.
class AccountQuickSwitchDemo extends StatefulWidget {
  const AccountQuickSwitchDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<AccountQuickSwitchDemo> createState() => _AccountQuickSwitchDemoState();
}

class _AccountQuickSwitchDemoState extends State<AccountQuickSwitchDemo> with DemoSceneState {
  int _active = 0;
  bool _fanned = false;
  int? _over;

  List<int> get _others => [
    for (var i = 0; i < _accounts.length; i++)
      if (i != _active) i,
  ];

  Future<void> _script(DemoFinger f) async {
    put(() {
      _active = 0;
      _fanned = false;
      _over = null;
    });
    await f.wait(500);
    await f.moveTo('avatar');
    await f.hold(() => put(() => _fanned = true));
    final to = f.at('fan1');
    await f.drag(
      to.dx,
      to.dy,
      600,
      release: false,
      onProgress: (p) {
        if (p > 0.8) put(() => _over = 1);
      },
    );
    put(() {
      _active = _others[1];
      _fanned = false;
      _over = null;
    });
    await f.release();
    await f.lift();
    await f.wait(2000);
  }

  @override
  Widget build(BuildContext context) {
    final strings = HomeStrings.of(context);
    final others = _others;
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: ColoredBox(
        color: Theme.of(context).colorScheme.surface,
        child: Stack(
          children: [
            Column(
              children: [
                _DemoHomeHeader(
                  avatar: DemoTarget(
                    'avatar',
                    child: GestureDetector(
                      onLongPress: () => put(() => _fanned = true),
                      child: _avatar(context, _active),
                    ),
                  ),
                ),
                for (final id in [0, 1])
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: TipSceneTokens.pagePaddingH),
                    child: _DemoSection(
                      title: [strings.sectionJumpBackIn, strings.sectionRecentlyAdded][id],
                      covers: [for (final i in _sectionAlbums[(id + _active) % 3]) demoAlbums[i]],
                      editing: false,
                      lift: 0,
                    ),
                  ),
              ],
            ),
            for (final (slot, account) in others.indexed)
              DemoSpring(
                key: ValueKey('fan$slot'),
                target: _fanned ? 1 : 0,
                builder: (context, p) => Positioned(
                  right: TipSceneTokens.pagePaddingH,
                  top: TipSceneTokens.pageTop + (slot + 1) * TipSceneTokens.fanStep * p,
                  child: Opacity(
                    opacity: p.clamp(0.0, 1.0),
                    child: DemoTarget(
                      'fan$slot',
                      child: GestureDetector(
                        onTap: () => put(() {
                          _active = account;
                          _fanned = false;
                        }),
                        child: Transform.scale(
                          scale: _over == slot ? TipSceneTokens.fanOverScale : 1,
                          child: _avatar(context, account),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// Lyric Cards (Android TipHomeScenes LyricHighlightDemo): the real Home
/// shelf under the header. The finger swipes on to the next song's line and
/// back, then taps a card to play from it. Then, as the tip says, Edit Home:
/// held, the section stands on its plate; the real count handle at its
/// corner, pulled down, opens its dots, and sliding back up one step takes a
/// card away; Done leaves. The lines are made up for the demo (Android's);
/// every card is "Your pick", the only kind HarmonyOS keeps.
class LyricHighlightDemo extends StatefulWidget {
  const LyricHighlightDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<LyricHighlightDemo> createState() => _LyricHighlightDemoState();
}

class _LyricHighlightDemoState extends State<LyricHighlightDemo> with DemoSceneState, TickerProviderStateMixin {
  final ScrollController _shelf = ScrollController();
  bool _editing = false;
  int _count = _restCount;

  /// The cards the section starts each run with.
  static const _restCount = 3;

  /// The real handle's engine, saving into [_count]; quiet, since nobody's finger is on the phone.
  late final HomeLyricCountEngine _handle = HomeLyricCountEngine(
    vsync: this,
    editing: () => _editing,
    blocked: () => false,
    saved: () => _count,
    commit: (preset) {
      final next = lyricCardsFor(preset);
      if (next == _count) return false;
      put(() => _count = next);
      return true;
    },
    reduced: () => mounted && MediaQuery.disableAnimationsOf(context),
    feedback: const _QuietCountFeedback(),
  );

  static final List<HomeLyricHighlight> _cards = [
    for (final (i, text) in _demoLyricLines.indexed)
      HomeLyricHighlight(
        songId: MediaId.subsonic('tip-lyric-$i'),
        title: demoTrackTitles[i],
        artist: demoAlbums[i].artist,
        album: demoAlbums[i].name,
        albumId: null,
        coverKey: demoAlbums[i].coverUrl,
        durationMs: 200000,
        text: text,
        startMs: null,
        kind: LyricHighlightBucket.user,
      ),
  ];

  @override
  void initState() {
    super.initState();
    // Made now: a lazy field first read in dispose() would ask a dead element for a ticker.
    _handle;
  }

  @override
  void dispose() {
    _handle.dispose();
    _shelf.dispose();
    super.dispose();
  }

  /// Where the shelf rests on card [page] (the last stops where the shelf runs out of room).
  double _offsetOf(int page) {
    if (!_shelf.hasClients) return 0;
    final p = _shelf.position;
    final content = homeFeedFrame(p.viewportDimension, _demoFeedDensity).content;
    final pitch = lyricCardWidth(content, single: false) + HomeLyricCardTokens.gap;
    return (page * pitch).clamp(p.minScrollExtent, p.maxScrollExtent);
  }

  Future<void> _script(DemoFinger f) async {
    if (_handle.held) _handle.release(cancelled: true);
    put(() {
      _editing = false;
      _count = _restCount;
    });
    if (_shelf.hasClients) _shelf.jumpTo(0);
    await f.wait(900);
    var page = 0;
    for (final target in const [1, 0]) {
      await f.moveTo('lyric', fx: 0.8, fy: 0.6);
      await f.wait(200);
      final from = _offsetOf(page);
      final to = _offsetOf(target);
      final end = f.at('lyric', fx: 0.3, fy: 0.6);
      // The shelf follows the finger across, as a swipe moves it.
      await f.drag(
        end.dx,
        end.dy,
        420,
        onProgress: (p) {
          if (_shelf.hasClients) _shelf.jumpTo(from + (to - from) * p);
        },
      );
      page = target;
      await f.wait(900);
    }
    await f.moveTo('lyric', fx: 0.4, fy: 0.5);
    await f.wait(250);
    await f.tap();
    await f.wait(900);
    // Edit Home: a hold on the section.
    await f.moveTo('lyric', fx: 0.6, fy: 0.4);
    await f.hold(() => put(() => _editing = true));
    await f.wait(600);
    // The corner handle: down past the dots' opening pull (the count holds there)…
    await f.moveTo('count', fx: 0.35, fy: 0.35);
    await f.wait(250);
    const down = HomeLyricCardTokens.countReveal + TipSceneTokens.lyricCountSlack;
    final low = f.at('count', fx: 0.35, fy: 0.35, dy: down);
    _handle.grab(0);
    try {
      await f.drag(low.dx, low.dy, 520, onProgress: (p) => _handle.dragTo(down * p), release: false);
      await f.wait(500);
      // …then up one step: a card fewer.
      const up = HomeLyricCardTokens.countStep + TipSceneTokens.lyricCountSlack;
      final high = Offset(low.dx, f.at('count', fx: 0.35, fy: 0.35, dy: down - up).dy);
      await f.drag(high.dx, high.dy, 480, onProgress: (p) => _handle.dragTo(down - up * p), release: false);
      await f.wait(400);
      _handle.release();
    } finally {
      // Stopped mid-pull (the user took the phone): the dots close and the count stays.
      if (_handle.held) _handle.release(cancelled: true);
    }
    await f.release();
    await f.wait(700);
    // Done is the bar's right half in the edit pose (BarEditPose).
    await f.moveTo('bar', fx: 0.75);
    await f.tap(() => put(() => _editing = false));
    await f.lift();
    await f.wait(1000);
  }

  @override
  Widget build(BuildContext context) => DemoPhone(
    playing: widget.playing,
    script: _script,
    child: GestureDetector(
      onLongPress: () => put(() => _editing = true),
      child: ColoredBox(
        color: Theme.of(context).colorScheme.surface,
        child: Stack(
          fit: StackFit.expand,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _DemoHomeHeader(avatar: _avatar(context, 0)),
                const SizedBox(height: HomeEditMetrics.plateOutsetV),
                DemoSpring(
                  target: _editing ? 1 : 0,
                  builder: (context, e) => HomeEditPlate(
                    progress: e,
                    section: HomeSection.lyricHighlight,
                    start: TipSceneTokens.pagePaddingH,
                    end: TipSceneTokens.pagePaddingH,
                    onHide: () {},
                    resizeHandle: DemoTarget(
                      'count',
                      child: HomeLyricCountHandle(engine: _handle, progress: e),
                    ),
                    // Full-bleed in the page's frame, as on Home: editing cuts the shelf back to its plate.
                    child: LayoutBuilder(
                      builder: (context, constraints) => DemoTarget(
                        'lyric',
                        child: LyricHighlightSection(
                          cards: _cards,
                          count: _count,
                          frame: homeFeedFrame(constraints.maxWidth, _demoFeedDensity),
                          controller: _shelf,
                          scrollEnabled: false,
                          onPlay: (_) {},
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
            // The real bar: editing, its destinations become [Undo] [Done].
            Positioned(
              left: 0,
              right: 0,
              bottom: TipSceneTokens.barBottom,
              child: DemoTarget(
                'bar',
                child: YoinBottomBar(
                  selected: YoinSection.home,
                  onSelect: (_) {},
                  editPose: BarEditPose(
                    editing: _editing,
                    leftSlot: _count == _restCount ? BarEditLeftSlot.undoDisabled : BarEditLeftSlot.undo,
                    onLeftSlot: () => put(() => _count = _restCount),
                    onDone: () => put(() => _editing = false),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

/// The phone's feed: a handset's, 16 margins (the Lyric Cards shelf reads only the frame's edges).
const _demoFeedDensity = HomeFeedDensity(frameClass: FeedFrameClass.capped, units: 1, coverColumns: 3);

/// The demo's handle plays no haptics: the finger on the phone is the demo's.
final class _QuietCountFeedback implements HomeLyricCountFeedback {
  const _QuietCountFeedback();
  @override
  void segmentTick() {}
  @override
  void confirm() {}
}

/// The demo's made-up lines (Android DemoLyricLines).
const _demoLyricLines = [
  'We left the porch light on all winter,\nso the summer would know the way',
  'Some songs wait in a drawer for years,\nthen fit you like a coat',
  "I hum the parts I don't know yet",
];

/// All Memories: the real grid; a tap on a week shows just that stretch;
/// scrolling on, the grid's squares fly up into the bar as a pixel mosaic
/// (the real lift layer), and the view switches between Timeline, Cards and
/// List. Nothing opens: the back button stays in the phone.
class MemoryLedgerDemo extends StatefulWidget {
  const MemoryLedgerDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<MemoryLedgerDemo> createState() => _MemoryLedgerDemoState();
}

class _MemoryLedgerDemoState extends State<MemoryLedgerDemo> with DemoSceneState, TickerProviderStateMixin {
  late final LedgerColorBook _colors = LedgerColorBook(vsync: this);
  late final MemoryGrid _grid = _demoGrid();
  late final List<LiftPixel> _pixels = liftPixels(_grid);
  final ScrollController _scroll = ScrollController();
  final MemoryGridLift _lift = MemoryGridLift();
  late final MemoryGridLiftProgress _liftProgress = MemoryGridLiftProgress(_lift, _scroll);
  final GlobalKey _layer = GlobalKey();
  final GlobalKey _gridKey = GlobalKey();

  /// The real grid steps aside the moment its copies take off.
  final ValueNotifier<bool> _lifted = ValueNotifier(false);

  /// 0 → 1 as the bar forms: its title and back button turn white.
  late final SpringValue _white = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  bool _formed = false;
  int? _selected;
  int _view = 0;

  static final _albums = [
    for (final (i, a) in demoAlbums.indexed)
      MemoryLedgerAlbum(albumId: MediaId.subsonic('tip-album-$i'), name: a.name, artist: a.artist, cover: a.coverRef),
  ];

  /// 26 weeks, oldest first; a few written in, the rest quiet.
  MemoryGrid _demoGrid() {
    final start = DateTime.utc(2026, 5, 18);
    return MemoryGrid(MemoryGridScale.week, [
      for (var w = 0; w < 26; w++)
        MemoryGridCell(
          start: start.add(Duration(days: w * 7)),
          end: start.add(Duration(days: w * 7 + 6)),
          marks: w % 3 == 0 || w % 5 == 1
              ? [
                  MemoryGridMark(album: _albums[w % _albums.length], wrote: w.isEven, count: 1 + w % 3),
                  if (w % 4 == 0) MemoryGridMark(album: _albums[(w + 2) % _albums.length], wrote: true, count: 1),
                ]
              : const [],
          writings: w % 3 == 0 ? 2 : 0,
          entries: w % 3 == 0 || w % 5 == 1 ? 2 : 0,
          future: false,
        ),
    ]);
  }

  @override
  void initState() {
    super.initState();
    for (final album in _albums) {
      final cover = album.cover;
      if (cover is CoverUrl) _colors.request(album, cover.url);
    }
    _scroll.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scroll.removeListener(_onScroll);
    _scroll.dispose();
    _colors.dispose();
    _lifted.dispose();
    _white.dispose();
    super.dispose();
  }

  RenderBox? _layerBox() {
    final box = _layer.currentContext?.findRenderObject();
    return box is RenderBox && box.attached && box.hasSize ? box : null;
  }

  /// As the page follows its lift (MemoryLedgerScreen): the grid steps aside, the bar turns white once formed.
  void _onScroll() {
    final box = _layerBox();
    final p = box == null ? 0.0 : _liftProgress.of(box, kToolbarHeight, measure: false);
    _lifted.value = p > 0.001;
    final formed = p > MemoryLedgerTokens.liftFormed;
    if (formed == _formed) return;
    put(() => _formed = formed);
    if (MediaQuery.disableAnimationsOf(context)) {
      _white.snapTo(formed ? 1 : 0);
    } else {
      _white.animateTo(formed ? 1 : 0);
    }
  }

  /// How far the list scrolls for the grid to be wholly under the bar.
  double _liftOffset() {
    final grid = _gridKey.currentContext?.findRenderObject();
    final layer = _layerBox();
    if (grid is! RenderBox || layer == null || !grid.attached || !_scroll.hasClients) return 0;
    final top = grid.localToGlobal(Offset.zero, ancestor: layer).dy - kToolbarHeight + _scroll.offset;
    return (top + grid.size.height).clamp(0.0, _scroll.position.maxScrollExtent);
  }

  Future<void> _script(DemoFinger f) async {
    put(() {
      _selected = null;
      _view = 0;
    });
    if (_scroll.hasClients) _scroll.jumpTo(0);
    await f.wait(600);
    await f.moveTo('grid', fx: 0.62, fy: 0.5);
    await f.tap(() => put(() => _selected = 15));
    await f.wait(1100);
    // Scrolling on: the squares rise into the bar.
    final lift = _liftOffset();
    await f.moveToPoint(0.5, 0.8);
    await f.drag(
      0.5,
      0.45,
      1200,
      onProgress: (p) {
        if (_scroll.hasClients) _scroll.jumpTo(lift * p);
      },
    );
    await f.wait(900);
    await f.moveTo('view');
    await f.tap(() => put(() => _view = 1));
    await f.wait(1000);
    await f.tap(() => put(() => _view = 2));
    await f.lift();
    await f.wait(1400);
    if (_scroll.hasClients) {
      await _scroll.animateTo(0, duration: const Duration(milliseconds: 900), curve: Curves.easeInOut);
    }
    await f.wait(500);
  }

  @override
  Widget build(BuildContext context) {
    final strings = MemoryLedgerStrings.of(context);
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final views = [
      (strings.viewTimeline, YoinSymbols.viewTimeline),
      (strings.viewCards, YoinSymbols.viewCards),
      (strings.viewList, YoinSymbols.viewList),
    ];
    final cell = _selected == null ? null : _grid.cells[_selected!];
    final shown = [
      for (final c in cell == null ? _grid.cells.reversed : [cell])
        for (final m in c.marks) m.album,
    ].take(4).toList();
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: ColoredBox(
        color: settingsPageColor(colors),
        child: Stack(
          children: [
            // The list starts under the bar, as on the page: only the lift's squares fly up into it.
            Padding(
              padding: const EdgeInsets.only(top: kToolbarHeight),
              child: ListView(
                controller: _scroll,
                padding: const EdgeInsets.fromLTRB(
                  MemoryLedgerTokens.pageSide,
                  MemoryLedgerTokens.pageTop,
                  MemoryLedgerTokens.pageSide,
                  TipSceneTokens.ledgerRoom,
                ),
                children: [
                  SettingsHeadline(title: strings.title),
                  const SizedBox(height: MemoryLedgerTokens.gridTop),
                  ValueListenableBuilder<bool>(
                    valueListenable: _lifted,
                    builder: (context, lifted, child) => Opacity(opacity: lifted ? 0 : 1, child: child),
                    child: KeyedSubtree(
                      key: _gridKey,
                      child: DemoTarget(
                        'grid',
                        child: MemoryGridView(
                          grid: _grid,
                          colors: _colors,
                          placement: MemoryGridPlacement.page,
                          selected: _selected,
                          lift: _lift,
                          onCellClick: (i) => put(() => _selected = _selected == i ? null : i),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: TipSceneTokens.titleGap),
                  Align(
                    alignment: AlignmentDirectional.centerEnd,
                    child: DemoTarget(
                      'view',
                      child: OutlinedButton.icon(
                        onPressed: () => put(() => _view = (_view + 1) % 3),
                        icon: Icon(views[_view].$2),
                        label: Text(views[_view].$1),
                      ),
                    ),
                  ),
                  const SizedBox(height: TipSceneTokens.titleGap),
                  if (_view == 1)
                    Wrap(
                      spacing: TipSceneTokens.coverGap,
                      runSpacing: TipSceneTokens.coverGap,
                      children: [
                        for (final album in shown)
                          CoverArt(cover: album.cover, size: TipSceneTokens.ledgerCard, radius: ShapeTokens.cover),
                      ],
                    )
                  else
                    for (final album in shown)
                      Padding(
                        padding: const EdgeInsets.only(bottom: TipSceneTokens.coverGap),
                        child: Row(
                          children: [
                            CoverArt(cover: album.cover, size: LibraryTokens.rowThumb, radius: ShapeTokens.thumb),
                            const SizedBox(width: TipSceneTokens.titleGap),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    album.name,
                                    style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurface),
                                  ),
                                  Text(
                                    _view == 0 ? strings.kindSongNote : strings.kindAlbumRating,
                                    style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                ],
              ),
            ),
            Positioned.fill(
              child: IgnorePointer(
                child: CustomPaint(
                  key: _layer,
                  painter: MemoryGridLiftPainter(
                    lift: _lift,
                    progress: _liftProgress,
                    pixels: _pixels,
                    colors: _colors,
                    faint: colors.onSurface.withValues(alpha: 0.07),
                    barBottom: kToolbarHeight,
                    into: _layerBox,
                    reduced: MediaQuery.disableAnimationsOf(context),
                    rtl: Directionality.of(context) == TextDirection.rtl,
                    repaint: Listenable.merge([_scroll, _colors]),
                  ),
                ),
              ),
            ),
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              height: kToolbarHeight,
              child: _DemoLedgerBar(title: strings.title, scroll: _scroll, white: _white.animation),
            ),
          ],
        ),
      ),
    );
  }
}

/// All Memories' bar in the phone (MemoryLedgerScreen's bar without its
/// status-bar style): the back button, and the title rising in as the
/// headline goes under; both turn white as the mosaic forms.
class _DemoLedgerBar extends StatelessWidget {
  const _DemoLedgerBar({required this.title, required this.scroll, required this.white});

  final String title;
  final ScrollController scroll;
  final Animation<double> white;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.titleLarge!;
    final titleHeight = SettingsBar.lineHeight(context, Theme.of(context).textTheme.displaySmall!);
    return AnimatedBuilder(
      animation: Listenable.merge([scroll, white]),
      builder: (context, _) {
        final w = white.value.clamp(0.0, 1.0);
        final ink = Color.lerp(colors.onSurface, Colors.white, w)!;
        final offset = scroll.hasClients ? scroll.offset : 0.0;
        final p = ((offset - SettingsBar.headlineOffset) / titleHeight.clamp(1.0, double.infinity)).clamp(0.0, 1.0);
        return Row(
          children: [
            Padding(
              padding: const EdgeInsetsDirectional.only(start: SettingsMetrics.backStart, end: SettingsMetrics.backEnd),
              child: DetailBackButton(container: colors.surfaceContainerHighest, content: ink, onBack: () {}),
            ),
            Expanded(
              child: Opacity(
                opacity: p,
                child: FractionalTranslation(
                  translation: Offset(0, (1 - p) * 0.5),
                  child: Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: style.copyWith(color: ink),
                  ),
                ),
              ),
            ),
          ],
        );
      },
    );
  }
}

/// The mark, as the app's icon and the mascot draw it: still, in its own colours.
class DemoMark extends StatefulWidget {
  const DemoMark({super.key});

  @override
  State<DemoMark> createState() => _DemoMarkState();
}

class _DemoMarkState extends State<DemoMark> with TickerProviderStateMixin {
  late final LandingMascotState _mark = LandingMascotState(this, reduced: true, haptics: false);

  @override
  void dispose() {
    _mark.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AspectRatio(
    aspectRatio: mascotAspect,
    child: LandingMascot(state: _mark),
  );
}

/// The phone's home screen: Yoin's icon among others; a long-press offers
/// Service widgets, the picker shows Play and Memory, and one lands on the
/// home screen.
class HomeScreenWidgetsDemo extends StatefulWidget {
  const HomeScreenWidgetsDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<HomeScreenWidgetsDemo> createState() => _HomeScreenWidgetsDemoState();
}

class _HomeScreenWidgetsDemoState extends State<HomeScreenWidgetsDemo> with DemoSceneState {
  bool _menu = false;
  bool _picker = false;
  bool _placed = false;

  Future<void> _script(DemoFinger f) async {
    put(() {
      _menu = false;
      _picker = false;
      _placed = false;
    });
    await f.wait(500);
    await f.moveTo('yoinIcon');
    await f.hold(() => put(() => _menu = true));
    await f.wait(300);
    await f.moveTo('widgetsRow');
    await f.tap(
      () => put(() {
        _menu = false;
        _picker = true;
      }),
    );
    await f.wait(900);
    await f.moveTo('playCard');
    await f.tap(
      () => put(() {
        _picker = false;
        _placed = true;
      }),
    );
    await f.lift();
    await f.wait(2200);
  }

  @override
  Widget build(BuildContext context) {
    final zh = Localizations.maybeLocaleOf(context)?.languageCode == 'zh';
    final colors = Theme.of(context).colorScheme;
    final album = demoAlbums[0];
    // The cards' own names (entry/src/main/resources card_play_label / card_memory_label).
    final playLabel = zh ? '播放' : 'Play';
    final memoryLabel = zh ? 'Memories' : 'Memory';
    Widget icon(Color color) => Container(
      width: TipSceneTokens.appIcon,
      height: TipSceneTokens.appIcon,
      decoration: ShapeDecoration(
        color: color,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(TipSceneTokens.appIconCorner)),
      ),
    );
    final playCard = _DemoPlayCard(album: album, label: playLabel);
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [colors.primaryContainer, colors.tertiaryContainer],
          ),
        ),
        child: Stack(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(
                TipSceneTokens.desktopPadding,
                TipSceneTokens.desktopTop,
                TipSceneTokens.desktopPadding,
                0,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // The widget lands: its slot opens (spatial) as it fades in (effects), never squeezed.
                  DemoSpring(
                    target: _placed ? 1 : 0,
                    builder: (context, p) => ClipRect(
                      child: Align(
                        alignment: Alignment.topCenter,
                        heightFactor: p.clamp(0.0, 1.0),
                        child: Opacity(opacity: p.clamp(0.0, 1.0), child: playCard),
                      ),
                    ),
                  ),
                  const SizedBox(height: TipSceneTokens.desktopPadding),
                  Wrap(
                    spacing: TipSceneTokens.appGap,
                    runSpacing: TipSceneTokens.appGap,
                    children: [
                      for (final c in [colors.secondary, colors.tertiary, colors.primary, colors.secondaryFixedDim])
                        icon(c),
                      DemoTarget(
                        'yoinIcon',
                        child: GestureDetector(
                          onLongPress: () => put(() => _menu = true),
                          child: Container(
                            width: TipSceneTokens.appIcon,
                            height: TipSceneTokens.appIcon,
                            padding: const EdgeInsets.all(TipSceneTokens.appIconInset),
                            decoration: ShapeDecoration(
                              color: Colors.white,
                              shape: RoundedSuperellipseBorder(
                                borderRadius: BorderRadius.circular(TipSceneTokens.appIconCorner),
                              ),
                            ),
                            child: const DemoMark(),
                          ),
                        ),
                      ),
                      for (final c in [colors.tertiaryFixedDim, colors.primaryFixedDim, colors.outline]) icon(c),
                    ],
                  ),
                ],
              ),
            ),
            // The icon's menu: Service widgets.
            DemoSpring(
              target: _menu ? 1 : 0,
              builder: (context, p) => p <= 0.001 && !_menu
                  ? const SizedBox.shrink()
                  : Positioned(
                      left: TipSceneTokens.desktopPadding,
                      right: TipSceneTokens.desktopPadding,
                      top: TipSceneTokens.menuTop,
                      child: Opacity(
                        opacity: p.clamp(0.0, 1.0),
                        child: Transform.scale(
                          scale: 0.9 + 0.1 * p,
                          child: DemoTarget(
                            'widgetsRow',
                            child: Material(
                              color: colors.surfaceContainerHigh,
                              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
                              child: ListTile(
                                leading: const Icon(YoinSymbols.library),
                                title: Text(zh ? '服务卡片' : 'Service widgets'),
                                onTap: () => put(() {
                                  _menu = false;
                                  _picker = true;
                                }),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
            ),
            DemoSheet(
              open: _picker,
              onDismiss: () => put(() => _picker = false),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(
                  TipSceneTokens.pagePaddingH,
                  0,
                  TipSceneTokens.pagePaddingH,
                  TipSceneTokens.pageTop,
                ),
                child: Column(
                  children: [
                    DemoTarget(
                      'playCard',
                      child: GestureDetector(
                        onTap: () => put(() {
                          _picker = false;
                          _placed = true;
                        }),
                        child: playCard,
                      ),
                    ),
                    const SizedBox(height: TipSceneTokens.titleGap),
                    _DemoMemoryCardWidget(album: demoAlbums[3], label: memoryLabel),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The Play service widget as the home screen shows it (2×4): the last song's cover, its name, play.
class _DemoPlayCard extends StatelessWidget {
  const _DemoPlayCard({required this.album, required this.label});

  final DemoAlbum album;
  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Container(
      height: TipSceneTokens.cardSlot,
      padding: const EdgeInsets.all(TipSceneTokens.cardPadding),
      decoration: ShapeDecoration(
        color: colors.surfaceContainerLowest,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.panel)),
      ),
      child: Row(
        children: [
          CoverArt(cover: album.coverRef, size: TipSceneTokens.cardCover, radius: ShapeTokens.hero),
          const SizedBox(width: TipSceneTokens.titleGap),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: theme.textTheme.labelLarge!.copyWith(color: colors.primary)),
                Text(demoTrackTitles[0], maxLines: 1, style: theme.textTheme.titleMedium),
                Text(
                  album.artist,
                  maxLines: 1,
                  style: theme.textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
                ),
              ],
            ),
          ),
          Icon(YoinSymbols.playFilled, color: colors.primary, size: TipSceneTokens.cardPlay),
        ],
      ),
    );
  }
}

/// The Memory service widget (2×4): a memory's cover and its album.
class _DemoMemoryCardWidget extends StatelessWidget {
  const _DemoMemoryCardWidget({required this.album, required this.label});

  final DemoAlbum album;
  final String label;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Container(
      height: TipSceneTokens.cardSlot,
      padding: const EdgeInsets.all(TipSceneTokens.cardPadding),
      decoration: ShapeDecoration(
        color: colors.secondaryContainer,
        shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.panel)),
      ),
      child: Row(
        children: [
          CoverArt(cover: album.coverRef, size: TipSceneTokens.cardCover, radius: ShapeTokens.hero),
          const SizedBox(width: TipSceneTokens.titleGap),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: theme.textTheme.labelLarge!.copyWith(color: colors.onSecondaryContainer)),
                Text(album.name, maxLines: 1, style: theme.textTheme.titleMedium),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Scrolling Home hides the bar: the feed scrolls down and the real bar
/// leaves with it (the app's own scroll-hide, [ShellBarScrollHide], on the
/// Memories pose it rides in the shell); scrolling back up brings it back.
/// The user's own scroll on the phone drives the same controller.
class BarScrollHideDemo extends StatefulWidget {
  const BarScrollHideDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<BarScrollHideDemo> createState() => _BarScrollHideDemoState();
}

class _BarScrollHideDemoState extends State<BarScrollHideDemo> with DemoSceneState, TickerProviderStateMixin {
  final ScrollController _feed = ScrollController();
  final ShellBarScrollHide _hide = ShellBarScrollHide();

  @override
  void initState() {
    super.initState();
    _hide.attach(this);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _hide.reduced = MediaQuery.disableAnimationsOf(context);
  }

  @override
  void dispose() {
    _hide.dispose();
    _feed.dispose();
    super.dispose();
  }

  /// The dot's drag scrolls the feed to [offset]; what the list took moves the bar, as a finger's would.
  void _scrollTo(double offset) {
    if (!_feed.hasClients) return;
    final position = _feed.position;
    final to = offset.clamp(position.minScrollExtent, position.maxScrollExtent);
    final delta = to - position.pixels;
    _feed.jumpTo(to);
    _hide.scrolled(delta);
  }

  Future<void> _script(DemoFinger f) async {
    if (_feed.hasClients && _feed.offset != 0) _feed.jumpTo(0);
    _hide.show();
    await f.wait(500);
    // Up the screen: the feed scrolls down and the bar goes with it.
    await f.moveToPoint(0.5, 0.72);
    await f.drag(0.5, 0.36, 800, onProgress: (p) => _scrollTo(p * TipSceneTokens.feedScrollTravel));
    _hide.released(0);
    await f.lift();
    await f.wait(1400);
    // Back down: the feed scrolls up and the bar comes back.
    await f.moveToPoint(0.5, 0.36);
    await f.drag(0.5, 0.72, 800, onProgress: (p) => _scrollTo((1 - p) * TipSceneTokens.feedScrollTravel));
    _hide.released(0);
    await f.lift();
    await f.wait(1200);
  }

  @override
  Widget build(BuildContext context) {
    final strings = HomeStrings.of(context);
    final titles = [
      strings.sectionJumpBackIn,
      strings.sectionRecentlyAdded,
      strings.sectionRecentlyPlayed,
      strings.sectionMadeForYou,
      strings.sectionRediscover,
    ];
    final dpr = MediaQuery.devicePixelRatioOf(context);
    return DemoPhone(
      playing: widget.playing,
      script: _script,
      child: ColoredBox(
        color: Theme.of(context).colorScheme.surface,
        child: Stack(
          children: [
            NotificationListener<ScrollNotification>(
              onNotification: (n) {
                _hide.onScrollNotification(n, devicePixelRatio: dpr);
                return false;
              },
              child: ListView(
                controller: _feed,
                padding: const EdgeInsets.only(bottom: TipSceneTokens.gridBottom),
                children: [
                  _DemoHomeHeader(avatar: _avatar(context, 0)),
                  for (final (i, title) in titles.indexed)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(
                        TipSceneTokens.pagePaddingH,
                        0,
                        TipSceneTokens.pagePaddingH,
                        TipSceneTokens.titleGap,
                      ),
                      child: _DemoSection(
                        title: title,
                        covers: [for (final a in _sectionAlbums[i % _sectionAlbums.length]) demoAlbums[a]],
                        editing: false,
                        lift: 0,
                      ),
                    ),
                ],
              ),
            ),
            Positioned(
              left: 0,
              right: 0,
              bottom: TipSceneTokens.barBottom,
              child: ValueListenableBuilder<double>(
                valueListenable: _hide.fraction,
                // The shell's pose for it: down and out, fading a little faster than it moves.
                builder: (context, hide, child) => IgnorePointer(
                  ignoring: hide > 0.01,
                  child: Opacity(
                    opacity: (1 - hide * MemoriesTokens.barHideAlphaGain).clamp(0.0, 1.0),
                    child: Transform.translate(offset: Offset(0, hide * MemoriesTokens.barHideTravel), child: child),
                  ),
                ),
                child: DemoTarget(
                  'bar',
                  child: YoinBottomBar(selected: YoinSection.home, onSelect: (_) {}),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
