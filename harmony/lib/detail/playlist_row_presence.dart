import 'package:flutter/widgets.dart';

import '../theme/motion.dart';

/// One keyed child of [RowPresenceBook.layOut].
@immutable
final class PresenceRow {
  const PresenceRow(this.key, this.child);
  final Key key;
  final Widget child;
}

/// The Playlist list's rows coming and going (Android animateItem on
/// PlaylistTrackList): a row removed from the playlist stays where it was,
/// fading on the effects spring while its height folds away on the spatial
/// one, so the rows under it move up; one that comes back (the service
/// refused) or is new on a read opens in its place the same way. The rows
/// the page first shows are simply there.
///
/// Held by the page's State; [layOut] runs in build and gives the list's
/// children, the rows on their way out included. [onChanged] asks for a
/// build once one has gone.
final class RowPresenceBook {
  RowPresenceBook({required this.onChanged});

  final VoidCallback onChanged;

  List<_Placed> _placed = const [];
  final _gone = <Key>{};
  bool _first = true;

  /// The children for [rows] (in order), with the rows that left since the
  /// last call still in place while they go.
  List<Widget> layOut(List<PresenceRow> rows) {
    final current = {for (final r in rows) r.key};
    // A row back on the list is no longer gone.
    _gone.removeAll(current);
    final before = {for (final p in _placed) p.row.key};
    // Leaving rows, each after the nearest row before it that is still listed.
    final leavingAfter = <Key?, List<_Placed>>{};
    Key? anchor;
    for (final p in _placed) {
      if (current.contains(p.row.key)) {
        anchor = p.row.key;
      } else if (!_gone.contains(p.row.key)) {
        (leavingAfter[anchor] ??= []).add(_Placed(p.row, present: false, appear: false));
      }
    }
    final placed = <_Placed>[...?leavingAfter[null]];
    for (final r in rows) {
      placed.add(_Placed(r, present: true, appear: !_first && !before.contains(r.key)));
      placed.addAll(leavingAfter[r.key] ?? const []);
    }
    _placed = placed;
    _first = false;
    _gone.retainAll({for (final p in placed) p.row.key});
    return [
      for (final p in placed)
        _RowPresence(
          key: p.row.key,
          present: p.present,
          appear: p.appear,
          onGone: () {
            _gone.add(p.row.key);
            onChanged();
          },
          child: p.row.child,
        ),
    ];
  }
}

final class _Placed {
  const _Placed(this.row, {required this.present, required this.appear});
  final PresenceRow row;
  final bool present;
  final bool appear;
}

class _RowPresence extends StatefulWidget {
  const _RowPresence({
    super.key,
    required this.present,
    required this.appear,
    required this.onGone,
    required this.child,
  });

  final bool present;

  /// Opens from nothing as it first builds (a row new since the last layout).
  final bool appear;
  final VoidCallback onGone;
  final Widget child;

  @override
  State<_RowPresence> createState() => _RowPresenceState();
}

class _RowPresenceState extends State<_RowPresence> with TickerProviderStateMixin {
  late final SpringValue _size = SpringValue(
    vsync: this,
    initial: widget.appear ? 0 : 1,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.appear ? 0 : 1,
    spring: YoinSprings.defaultEffects,
  );
  bool _reportedGone = false;

  @override
  void initState() {
    super.initState();
    _size.animation.addStatusListener(_onStatus);
    _alpha.animation.addStatusListener(_onStatus);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _settleTowards(widget.present);
  }

  @override
  void didUpdateWidget(_RowPresence old) {
    super.didUpdateWidget(old);
    if (old.present != widget.present) {
      _reportedGone = false;
      _settleTowards(widget.present);
    }
  }

  bool get _reduced => MediaQuery.disableAnimationsOf(context);

  void _settleTowards(bool present) {
    final target = present ? 1.0 : 0.0;
    if (_reduced) {
      // Reduced motion: the row fades in place and the rows around it don't travel.
      _size.snapTo(1);
    } else {
      _size.animateTo(target);
    }
    _alpha.animateTo(target);
  }

  // Gone once what moves it has settled: the height, or under reduced motion the fade.
  void _onStatus(AnimationStatus status) {
    if (status != AnimationStatus.completed || widget.present) return;
    final settled = _reduced ? _alpha.value <= 0.001 : _size.value <= 0.001;
    if (settled) _reportGone();
  }

  void _reportGone() {
    if (!mounted || widget.present || _reportedGone) return;
    _reportedGone = true;
    widget.onGone();
  }

  @override
  void dispose() {
    _size.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => IgnorePointer(
    ignoring: !widget.present,
    child: AnimatedBuilder(
      animation: Listenable.merge([_size.animation, _alpha.animation]),
      child: widget.child,
      builder: (context, child) {
        final size = _size.value;
        final alpha = _alpha.value.clamp(0.0, 1.0);
        final settled = size >= 1 && alpha >= 1;
        // One tree shape throughout, so the row never remounts.
        return ClipRect(
          clipBehavior: settled ? Clip.none : Clip.hardEdge,
          child: Align(
            alignment: Alignment.topCenter,
            heightFactor: settled ? 1 : size.clamp(0.0, 1.0),
            child: Opacity(opacity: alpha, child: child),
          ),
        );
      },
    ),
  );
}
