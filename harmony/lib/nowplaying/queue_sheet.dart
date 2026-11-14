import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/model/model.dart';
import '../playback/now_playing_state.dart';
import '../playback/playback_context.dart';
import '../playback/playback_controller.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/component_strings.dart';
import '../ui/component/cover_art.dart';
import '../ui/component/swipe_to_remove.dart';
import 'haptics.dart';
import 'nowplaying_strings.dart';

/// One queue entry in the sheet: its index in the player's list, its stable
/// entry id (the same song queued twice is two rows) and the song.
@immutable
final class QueueRow {
  const QueueRow(this.index, this.entryId, this.track);
  final int index;
  final String entryId;
  final Track track;

  @override
  bool operator ==(Object other) =>
      other is QueueRow && other.index == index && other.entryId == entryId && identical(other.track, track);

  @override
  int get hashCode => Object.hash(index, entryId, track);
}

/// The sheet's three parts, Spotify's queue (QueueSheet.kt QueueSections):
/// what plays now, what the user added (Play next / Add to queue), and the
/// rest of the album or playlist — both in the order they will play. Played
/// songs are left out.
@immutable
final class QueueSections {
  const QueueSections(this.now, this.queued, this.next);
  final QueueRow? now;
  final List<QueueRow> queued;
  final List<QueueRow> next;
}

/// [state]'s queue split into [QueueSections] (QueueSheet.kt queueSections).
/// [NowPlayingState.upcoming] is the play order after the current entry;
/// empty means list order.
QueueSections queueSectionsOf(NowPlayingState state) {
  final queue = state.queue;
  final current = state.index;
  String idAt(int i) => i < state.entryIds.length ? state.entryIds[i] : 'i$i';
  final now = current >= 0 && current < queue.length ? QueueRow(current, idAt(current), queue[current]) : null;
  final order = state.upcoming.isNotEmpty
      ? state.upcoming
      : [for (var i = current >= 0 ? current + 1 : 0; i < queue.length; i++) i];
  final queued = <QueueRow>[];
  final next = <QueueRow>[];
  for (final i in order) {
    if (i < 0 || i >= queue.length) continue;
    (state.userQueued.contains(i) ? queued : next).add(QueueRow(i, idAt(i), queue[i]));
  }
  return QueueSections(now, queued, next);
}

/// The context's name for the header and "Next from: …" (null = none).
String? playContextName(PlaybackContext context) => switch (context) {
  AlbumPlaybackContext(:final name) => name,
  PlaylistPlaybackContext(:final name) => name,
  ArtistPlaybackContext(:final name) => name,
  NoPlaybackContext() || FavoritesPlaybackContext() => null,
};

/// [rows] with a pending drop applied: the order the drop asked for, shown
/// until the player reports its next list, so the dropped row never flicks
/// back to its old slot.
List<QueueRow> rowsWithPendingOrder(List<QueueRow> rows, List<String>? pendingOrder) {
  if (pendingOrder == null) return rows;
  final byId = {for (final r in rows) r.entryId: r};
  final shown = [for (final id in pendingOrder) ?byId[id]];
  return shown.length == rows.length ? shown : rows;
}

/// The slot a held row hovers: where it started plus whole rows travelled.
int reorderSlot({required int from, required double offset, required int count}) =>
    (from + (offset / SheetTokens.queueRowHeight).round()).clamp(0, count - 1);

/// How far row [position] steps aside while row [dragging] hovers slot
/// [target]: a row's height toward the gap, or nothing. Null with no drag.
double? reorderShift({required int position, required int dragging, required int target}) {
  if (dragging < 0) return null;
  if (dragging == position) return 0;
  if (dragging < position && position <= target) return -SheetTokens.queueRowHeight;
  if (target <= position && position < dragging) return SheetTokens.queueRowHeight;
  return 0;
}

/// One part's drag (QueueSheet.kt QueueReorderState): which row is held, the
/// slot it hovers, how far it has travelled, and the order a drop asked for.
class _ReorderModel extends ChangeNotifier {
  int dragging = -1;
  int target = -1;
  double offset = 0;
  List<String>? pendingOrder;
  List<String> lastIds = const [];

  bool get active => dragging >= 0;

  void start(int position) {
    dragging = position;
    target = position;
    offset = 0;
    notifyListeners();
  }

  /// Returns true when the hovered slot changed.
  bool drag(double dy, int count) {
    offset += dy;
    final slot = reorderSlot(from: dragging, offset: offset, count: count);
    final moved = slot != target;
    target = slot;
    notifyListeners();
    return moved;
  }

  void end() {
    dragging = -1;
    target = -1;
    offset = 0;
    notifyListeners();
  }
}

/// The play queue (QueueSheet.kt; owner Q1, 2026-10-06): Now playing, Next
/// in queue (what the user added, clearable), Next from the album or
/// playlist. Tap a song to play it. Drag a row's handle to move it within its
/// part and swipe it left to take it out; nothing is reordered while shuffle
/// plays (the list isn't the play order), and a line under the header says so.
class QueueSheet extends ConsumerStatefulWidget {
  const QueueSheet({super.key, required this.onSkip});

  final ValueChanged<int> onSkip;

  @override
  ConsumerState<QueueSheet> createState() => _QueueSheetState();
}

class _QueueSheetState extends ConsumerState<QueueSheet> {
  final _queuedModel = _ReorderModel();
  final _nextModel = _ReorderModel();

  @override
  void dispose() {
    _queuedModel.dispose();
    _nextModel.dispose();
    super.dispose();
  }

  /// The player answered (with the dropped order or anything newer): its list wins.
  void _reconcile(_ReorderModel model, List<QueueRow> rows) {
    final ids = [for (final r in rows) r.entryId];
    if (!_sameIds(ids, model.lastIds) && !model.active) model.pendingOrder = null;
    model.lastIds = ids;
  }

  static bool _sameIds(List<String> a, List<String> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(playbackControllerProvider);
    final controller = ref.read(playbackControllerProvider.notifier);
    final strings = NowPlayingStrings.of(context);
    final cstrings = ComponentStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    if (!state.hasQueue) return const SizedBox(height: 120);
    final name = playContextName(state.context);
    final sections = queueSectionsOf(state);
    _reconcile(_queuedModel, sections.queued);
    _reconcile(_nextModel, sections.next);
    final canMove = !state.shuffle;
    final navBottom = MediaQuery.paddingOf(context).bottom;

    List<Widget> section(String label, List<QueueRow> rows, _ReorderModel model, {VoidCallback? onClear}) {
      final shown = rowsWithPendingOrder(rows, model.pendingOrder);
      return [
        SliverToBoxAdapter(
          child: _SectionLabel(text: label, actionLabel: cstrings.queueClear, onAction: onClear),
        ),
        SliverList.builder(
          itemCount: shown.length,
          itemBuilder: (context, i) {
            final row = shown[i];
            return _EditableQueueRow(
              key: ValueKey(row.entryId),
              row: row,
              position: i,
              count: shown.length,
              model: model,
              canMove: canMove,
              onTap: () => widget.onSkip(row.index),
              onRemove: () => controller.removeQueueItem(row.index),
              onDrop: (from, to) {
                final order = [for (final r in shown) r.entryId];
                order.insert(to, order.removeAt(from));
                model.pendingOrder = order;
                controller.moveQueueItem(shown[from].index, shown[to].index);
              },
            );
          },
        ),
      ];
    }

    return CustomScrollView(
      shrinkWrap: true,
      slivers: [
        SliverToBoxAdapter(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(24, 4, 24, 4),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (name == null)
                  Text(strings.queueTitle, style: text.titleLarge, maxLines: 1, overflow: TextOverflow.ellipsis)
                else ...[
                  Text(strings.queuePlayingFrom, style: text.labelMedium!.copyWith(color: colors.onSurfaceVariant)),
                  Text(name, style: text.titleLarge, maxLines: 1, overflow: TextOverflow.ellipsis),
                ],
                // Shuffle can flip while the sheet is open: the line comes and goes with it.
                _SpringReveal(
                  visible: state.shuffle,
                  child: Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text(
                      cstrings.queueHintShuffle,
                      style: text.bodySmall!.copyWith(color: colors.onSurfaceVariant),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
        if (sections.now case final now?) ...[
          SliverToBoxAdapter(child: _SectionLabel(text: strings.queueSectionNow)),
          SliverToBoxAdapter(
            child: _QueueRowContent(track: now.track, current: true, playing: state.playWhenReady, onTap: null),
          ),
        ],
        if (sections.queued.isNotEmpty)
          ...section(
            strings.queueSectionQueued,
            sections.queued,
            _queuedModel,
            onClear: () {
              YoinHaptics.confirm();
              controller.clearUserQueue();
            },
          ),
        if (sections.next.isNotEmpty)
          ...section(name == null ? strings.queueNextUp : strings.queueNextFrom(name), sections.next, _nextModel),
        SliverToBoxAdapter(child: SizedBox(height: 16 + navBottom)),
      ],
    );
  }
}

/// Grows from nothing / folds away on the spatial spring, fading on the effects spring.
class _SpringReveal extends StatefulWidget {
  const _SpringReveal({required this.visible, required this.child});
  final bool visible;
  final Widget child;

  @override
  State<_SpringReveal> createState() => _SpringRevealState();
}

class _SpringRevealState extends State<_SpringReveal> with TickerProviderStateMixin {
  late final SpringValue _size = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_SpringReveal old) {
    super.didUpdateWidget(old);
    final target = widget.visible ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _size.snapTo(target);
    } else {
      _size.animateTo(target);
    }
    _alpha.animateTo(target);
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
    builder: (context, child) {
      final size = _size.value.clamp(0.0, 1.0);
      if (size == 0 && !widget.visible) return const SizedBox.shrink();
      return ClipRect(
        child: Align(
          alignment: Alignment.topLeft,
          heightFactor: size,
          child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
        ),
      );
    },
    child: widget.child,
  );
}

class _SectionLabel extends StatelessWidget {
  const _SectionLabel({required this.text, this.actionLabel, this.onAction});
  final String text;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.titleSmall!;
    return Padding(
      padding: EdgeInsets.fromLTRB(24, 18, 12, onAction == null ? 2 : 0),
      child: Row(
        children: [
          Expanded(
            child: Text(
              text,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: style.copyWith(color: colors.onSurface, fontWeight: FontWeight.w600),
            ),
          ),
          if (onAction != null && actionLabel != null) TextButton(onPressed: onAction, child: Text(actionLabel!)),
        ],
      ),
    );
  }
}

/// A row that can move and leave. Its neighbours make way (spatial spring)
/// while a row of its part is held, and snap at the drop so the new order
/// lands in the same frame. The held row rides above the list in an overlay
/// (it must paint over the rows below it), lifted onto the brighter container.
class _EditableQueueRow extends StatefulWidget {
  const _EditableQueueRow({
    super.key,
    required this.row,
    required this.position,
    required this.count,
    required this.model,
    required this.canMove,
    required this.onTap,
    required this.onRemove,
    required this.onDrop,
  });

  final QueueRow row;
  final int position;
  final int count;
  final _ReorderModel model;
  final bool canMove;
  final VoidCallback onTap;
  final VoidCallback onRemove;
  final void Function(int from, int to) onDrop;

  @override
  State<_EditableQueueRow> createState() => _EditableQueueRowState();
}

class _EditableQueueRowState extends State<_EditableQueueRow> with TickerProviderStateMixin {
  late final SpringValue _shift = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);
  final _link = LayerLink();
  final _portal = OverlayPortalController();
  double _width = 0;

  @override
  void initState() {
    super.initState();
    widget.model.addListener(_onModel);
  }

  @override
  void didUpdateWidget(_EditableQueueRow old) {
    super.didUpdateWidget(old);
    if (old.model != widget.model) {
      old.model.removeListener(_onModel);
      widget.model.addListener(_onModel);
    }
  }

  @override
  void dispose() {
    widget.model.removeListener(_onModel);
    _shift.dispose();
    super.dispose();
  }

  bool get _held => widget.model.dragging == widget.position;

  void _onModel() {
    final m = widget.model;
    final shift = reorderShift(position: widget.position, dragging: m.dragging, target: m.target);
    // The drop (no drag any more) snaps: the new order lands in the same frame.
    if (shift == null || MediaQuery.disableAnimationsOf(context)) {
      _shift.snapTo(shift ?? 0);
    } else {
      _shift.animateTo(shift);
    }
    if (_held && !_portal.isShowing) {
      _portal.show();
    } else if (!_held && _portal.isShowing) {
      _portal.hide();
    }
    setState(() {});
  }

  void _onDragStart(DragStartDetails _) {
    YoinHaptics.tick();
    widget.model.start(widget.position);
  }

  void _onDragUpdate(DragUpdateDetails d) {
    if (!_held) return;
    if (widget.model.drag(d.delta.dy, widget.count)) YoinHaptics.tick();
  }

  void _onDragEnd({required bool commit}) {
    final m = widget.model;
    final from = m.dragging;
    final to = m.target;
    if (commit && from >= 0 && to >= 0 && from != to) widget.onDrop(from, to);
    m.end();
  }

  @override
  Widget build(BuildContext context) {
    final strings = ComponentStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final title = widget.row.track.title ?? '';
    final handle = widget.canMove
        ? GestureDetector(
            behavior: HitTestBehavior.opaque,
            onVerticalDragStart: _onDragStart,
            onVerticalDragUpdate: _onDragUpdate,
            onVerticalDragEnd: (_) => _onDragEnd(commit: true),
            onVerticalDragCancel: () => _onDragEnd(commit: false),
            child: SizedBox.square(
              dimension: SheetTokens.queueHandleBox,
              child: Icon(
                YoinSymbols.dragHandle,
                color: colors.onSurfaceVariant,
                semanticLabel: strings.queueCdMove(title),
              ),
            ),
          )
        : null;
    Widget content({required bool lifted}) => _QueueRowContent(
      track: widget.row.track,
      current: false,
      playing: false,
      onTap: lifted ? null : widget.onTap,
      handle: handle,
      lifted: lifted,
    );
    return LayoutBuilder(
      builder: (context, constraints) {
        _width = constraints.maxWidth;
        return AnimatedBuilder(
          animation: _shift.animation,
          builder: (context, child) => Transform.translate(offset: Offset(0, _shift.value), child: child),
          child: OverlayPortal(
            controller: _portal,
            overlayChildBuilder: (context) => CompositedTransformFollower(
              link: _link,
              showWhenUnlinked: false,
              child: ListenableBuilder(
                listenable: widget.model,
                builder: (context, child) => Transform.translate(offset: Offset(0, widget.model.offset), child: child),
                child: Align(
                  alignment: Alignment.topLeft,
                  child: SizedBox(
                    width: _width,
                    height: SheetTokens.queueRowHeight,
                    child: Material(
                      elevation: SheetTokens.queueLiftElevation,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(ShapeTokens.panel)),
                      clipBehavior: Clip.antiAlias,
                      color: colors.surfaceContainerHighest,
                      child: content(lifted: true),
                    ),
                  ),
                ),
              ),
            ),
            child: CompositedTransformTarget(
              link: _link,
              child: Opacity(
                opacity: _held ? 0 : 1,
                child: SwipeToRemove(
                  enabled: !widget.model.active,
                  semanticLabel: strings.queueCdRemove,
                  onRemove: widget.onRemove,
                  child: content(lifted: false),
                ),
              ),
            ),
          ),
        );
      },
    );
  }
}

/// One song: cover, title over artist, and — where it can move — a drag
/// handle. The current song reads in the primary colour with the equalizer.
class _QueueRowContent extends StatelessWidget {
  const _QueueRowContent({
    required this.track,
    required this.current,
    required this.playing,
    required this.onTap,
    this.handle,
    this.lifted = false,
  });

  final Track track;
  final bool current;
  final bool playing;
  final VoidCallback? onTap;
  final Widget? handle;
  final bool lifted;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    return Material(
      color: lifted ? colors.surfaceContainerHighest : colors.surfaceContainer,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          height: SheetTokens.queueRowHeight,
          child: Padding(
            padding: EdgeInsetsDirectional.only(
              start: SheetTokens.queueRowPaddingH,
              end: handle != null ? SheetTokens.queueHandlePaddingEnd : SheetTokens.queueRowPaddingH,
            ),
            child: Row(
              children: [
                track.coverArt == null
                    ? Container(
                        width: SheetTokens.queueCover,
                        height: SheetTokens.queueCover,
                        decoration: BoxDecoration(
                          color: colors.surfaceContainerHighest,
                          borderRadius: BorderRadius.circular(ShapeTokens.thumb),
                        ),
                        child: Icon(YoinSymbols.musicNote, color: colors.onSurfaceVariant),
                      )
                    : CoverArt(cover: track.coverArt, size: SheetTokens.queueCover, radius: ShapeTokens.thumb),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        track.title ?? '',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: text.bodyLarge!.copyWith(
                          fontWeight: current ? FontWeight.w600 : FontWeight.w500,
                          color: current ? colors.primary : colors.onSurface,
                        ),
                      ),
                      if (track.artist != null)
                        Text(
                          track.artist!,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
                        ),
                    ],
                  ),
                ),
                if (current)
                  Semantics(
                    label: playing ? strings.queueCdPlaying : strings.queueCdPaused,
                    child: Icon(YoinSymbols.equalizer, size: 22, color: colors.primary),
                  ),
                ?handle,
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Opens the queue sheet (Standard-role modal sheet, container
/// surfaceContainer) on [navigatorContext] — Now Playing's own navigator, so
/// the sheet sits above the player and system back reaches it first.
Future<void> showQueueSheet(BuildContext navigatorContext, {required ValueChanged<int> onSkip}) =>
    showModalBottomSheet<void>(
      context: navigatorContext,
      useSafeArea: false,
      isScrollControlled: true,
      showDragHandle: true,
      backgroundColor: Theme.of(navigatorContext).colorScheme.surfaceContainer,
      builder: (sheetContext) => QueueSheet(
        onSkip: (index) {
          onSkip(index);
          Navigator.of(sheetContext).pop();
        },
      ),
    );
