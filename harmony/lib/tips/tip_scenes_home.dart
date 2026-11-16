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
import '../home/home_strings.dart';
import '../landing/landing_mascot.dart';
import '../memories/ledger/memory_grid_view.dart';
import '../memories/ledger/memory_ledger_strings.dart';
import '../memories/memories_strings.dart';
import '../memories/memory_card_face.dart' show MemoryPillButton;
import '../settings/account_identity.dart';
import '../shell/bar_edit_pose.dart';
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
  const _DemoMemoryCard({required this.album, required this.onDiary});

  final DemoAlbum album;
  final VoidCallback onDiary;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final strings = MemoriesStrings.of(context);
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
            DemoTarget(
              'diary',
              child: MemoryPillButton(
                label: strings.cardDiary,
                color: colors.primary,
                onColor: colors.onPrimary,
                onTap: onDiary,
              ),
            ),
            MemoryPillButton(
              label: strings.cardGoAlbum,
              color: colors.secondaryContainer,
              onColor: colors.onSecondaryContainer,
              onTap: () {},
            ),
          ],
        ),
      ],
    );
  }
}

/// Memories as the phone shows it: the top bar, then the card ⇄ diary by
/// [diary] (0 card, 1 diary): the cover flies into the bar's leading slot and
/// the diary's lines rise behind the title.
class _DemoMemories extends StatelessWidget {
  const _DemoMemories({required this.album, required this.diary, required this.onDiary});

  final DemoAlbum album;
  final double diary;
  final VoidCallback onDiary;

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
    return ColoredBox(
      color: colors.surfaceContainer,
      child: Stack(
        children: [
          // The bar: "Memories", the album sliding in beside its cover once the diary is open.
          Positioned(
            left: 0,
            right: 0,
            top: 0,
            height: TipSceneTokens.barHeight,
            child: DemoTarget(
              'memoriesBar',
              child: Padding(
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
                child: _DemoMemoryCard(album: album, onDiary: onDiary),
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

/// All Memories: the real grid; a tap on a week shows just that stretch, and
/// the view switches between Timeline, Cards and List.
class MemoryLedgerDemo extends StatefulWidget {
  const MemoryLedgerDemo({super.key, required this.playing});
  final bool playing;

  @override
  State<MemoryLedgerDemo> createState() => _MemoryLedgerDemoState();
}

class _MemoryLedgerDemoState extends State<MemoryLedgerDemo> with DemoSceneState, TickerProviderStateMixin {
  late final LedgerColorBook _colors = LedgerColorBook(vsync: this);
  late final MemoryGrid _grid = _demoGrid();
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
  }

  @override
  void dispose() {
    _colors.dispose();
    super.dispose();
  }

  Future<void> _script(DemoFinger f) async {
    put(() {
      _selected = null;
      _view = 0;
    });
    await f.wait(600);
    await f.moveTo('grid', fx: 0.62, fy: 0.5);
    await f.tap(() => put(() => _selected = 15));
    await f.wait(1100);
    await f.moveTo('view');
    await f.tap(() => put(() => _view = 1));
    await f.wait(1000);
    await f.tap(() => put(() => _view = 2));
    await f.lift();
    await f.wait(1600);
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
      child: DemoSettingsPage(
        title: strings.title,
        children: [
          DemoTarget(
            'grid',
            child: MemoryGridView(
              grid: _grid,
              colors: _colors,
              placement: MemoryGridPlacement.page,
              selected: _selected,
              onCellClick: (i) => put(() => _selected = _selected == i ? null : i),
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
                          Text(album.name, style: theme.textTheme.titleMedium!.copyWith(color: colors.onSurface)),
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
