import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';

import '../data/memory/memory_store.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import 'note_line.dart';
import 'note_logic.dart';
import 'nowplaying_strings.dart';
import 'playhead_clock.dart';

typedef _N = NowPlayingNoteTokens;

/// The Compact window's Note page (Android compact/NoteCompactPane): the
/// song's notes read-only, in timeline order, at glance emphasis; the note the
/// playhead is inside lit, the list gliding to keep it ~22 % down (the lyric
/// window's anchor). No cards, no controls: a tap on the window expands the
/// stage, where notes become editable. Rebuilds only when the lit note changes.
class NoteCompactPane extends StatefulWidget {
  const NoteCompactPane({super.key, required this.notes, required this.clock});

  final List<SongNote> notes;
  final PlayheadClock clock;

  @override
  State<NoteCompactPane> createState() => _NoteCompactPaneState();
}

class _NoteCompactPaneState extends State<NoteCompactPane> with TickerProviderStateMixin {
  final ScrollController _scroll = ScrollController();
  late final AnimationController _glide;
  final GlobalKey _content = GlobalKey();
  final Map<String, GlobalKey> _rowKeys = {};
  late List<SongNote> _sorted = sortNotes(widget.notes, NoteSortMode.timeline);
  String? _lit;
  bool _seated = false;
  double _viewport = 0;

  @override
  void initState() {
    super.initState();
    _glide = AnimationController.unbounded(vsync: this)..addListener(_onGlide);
    _lit = currentAnchoredNoteId(_sorted, widget.clock.positionMs());
    widget.clock.addListener(_onTick);
  }

  @override
  void didUpdateWidget(NoteCompactPane oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.clock != widget.clock) {
      oldWidget.clock.removeListener(_onTick);
      widget.clock.addListener(_onTick);
    }
    if (!identical(oldWidget.notes, widget.notes)) {
      _sorted = sortNotes(widget.notes, NoteSortMode.timeline);
      _lit = currentAnchoredNoteId(_sorted, widget.clock.positionMs());
      WidgetsBinding.instance.addPostFrameCallback((_) => _seat(animate: true));
    }
  }

  @override
  void dispose() {
    widget.clock.removeListener(_onTick);
    _glide.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _onTick() {
    final next = currentAnchoredNoteId(_sorted, widget.clock.positionMs());
    if (next == _lit) return;
    setState(() => _lit = next);
    WidgetsBinding.instance.addPostFrameCallback((_) => _seat(animate: true));
  }

  void _onGlide() {
    if (_scroll.hasClients) _scroll.jumpTo(_glide.value);
  }

  void _seat({required bool animate}) {
    if (!mounted || !_scroll.hasClients) return;
    final lit = _lit;
    final box = lit == null ? null : _rowKeys[lit]?.currentContext?.findRenderObject() as RenderBox?;
    final content = _content.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || content == null || !box.attached || !content.attached) return;
    final top = box.localToGlobal(Offset.zero, ancestor: content).dy;
    final position = _scroll.position;
    final target = (top - _viewport * noteFollowAnchorFraction).clamp(
      position.minScrollExtent,
      position.maxScrollExtent,
    );
    if (!animate || !_seated || MediaQuery.disableAnimationsOf(context)) {
      _glide.stop();
      _scroll.jumpTo(target);
      _seated = true;
      return;
    }
    _glide.value = _scroll.offset;
    _glide.animateWith(SpringSimulation(YoinSprings.standardDefaultSpatial, _scroll.offset, target, 0));
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    if (_sorted.isEmpty) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: NowPlayingTokens.columnPadding),
        child: Align(
          alignment: AlignmentDirectional.centerStart,
          child: Text(
            NowPlayingStrings.of(context).noteTapToWrite,
            style: Theme.of(context).textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
          ),
        ),
      );
    }
    final litIndex = _sorted.indexWhere((n) => n.id == _lit);
    return LayoutBuilder(
      builder: (context, constraints) {
        final viewport = constraints.maxHeight;
        if (viewport > 0 && viewport != _viewport) {
          _viewport = viewport;
          // Resized in place by the stage reshape: re-anchor at once.
          WidgetsBinding.instance.addPostFrameCallback((_) => _seat(animate: false));
        }
        return ShaderMask(
          blendMode: BlendMode.dstIn,
          shaderCallback: (rect) => LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: const [Color(0x00000000), Color(0xFF000000), Color(0xFF000000), Color(0x00000000)],
            stops: [
              0,
              (_N.compactFadeTop / rect.height).clamp(0.0, 0.5),
              1 - (_N.compactFadeBottom / rect.height).clamp(0.0, 0.5),
              1,
            ],
          ).createShader(rect),
          child: SingleChildScrollView(
            controller: _scroll,
            physics: const NeverScrollableScrollPhysics(),
            padding: const EdgeInsets.symmetric(
              horizontal: NowPlayingTokens.columnPadding,
              vertical: _N.compactListPadV,
            ),
            child: Column(
              key: _content,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                for (var i = 0; i < _sorted.length; i++) ...[
                  if (i > 0) const SizedBox(height: _N.compactLineGap),
                  NoteLine(
                    key: _rowKeys.putIfAbsent(_sorted[i].id, GlobalKey.new),
                    stamp: _sorted[i].positionMs == null ? null : formatNotePosition(_sorted[i].positionMs!),
                    text: _sorted[i].content,
                    active: i == litIndex,
                    emphasis: NoteLineEmphasis.glance,
                    distance: litIndex >= 0 ? (i - litIndex).abs() : null,
                  ),
                ],
              ],
            ),
          ),
        );
      },
    );
  }
}
