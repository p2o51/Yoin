import 'package:flutter/material.dart';

import '../../theme/motion.dart';

/// Cross-fades between children keyed by [childKey] (Compose AnimatedContent
/// with fadeIn + fadeOut, contentKey = state class): a new key fades in
/// while the old child fades out, both on the effects spring; the same key
/// just rebuilds in place with no fade.
class SpringCrossFade extends StatefulWidget {
  const SpringCrossFade({super.key, required this.childKey, required this.child, this.fit = StackFit.expand});

  final Object childKey;
  final Widget child;
  final StackFit fit;

  @override
  State<SpringCrossFade> createState() => _SpringCrossFadeState();
}

class _Entry {
  _Entry(this.key, this.child, this.alpha);
  final Object key;
  Widget child;
  final SpringValue alpha;
}

class _SpringCrossFadeState extends State<SpringCrossFade> with TickerProviderStateMixin {
  final _entries = <_Entry>[];

  @override
  void initState() {
    super.initState();
    _entries.add(
      _Entry(widget.childKey, widget.child, SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects)),
    );
  }

  @override
  void didUpdateWidget(SpringCrossFade old) {
    super.didUpdateWidget(old);
    final current = _entries.last;
    if (current.key == widget.childKey) {
      current.child = widget.child;
      return;
    }
    for (final e in _entries) {
      e.alpha.animateTo(0);
    }
    final reduce = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    // Coming back to a key that is still fading out revives that child (its
    // state, scroll position included) instead of mounting a second copy.
    final revived = _entries.where((e) => e.key == widget.childKey).firstOrNull;
    if (revived != null) {
      _entries
        ..remove(revived)
        ..add(revived);
      revived.child = widget.child;
      reduce ? revived.alpha.snapTo(1) : revived.alpha.animateTo(1);
      for (final e in _entries.take(_entries.length - 1)) {
        e.alpha.animation.addStatusListener((status) => _prune());
      }
      if (reduce) _prune(force: true);
      return;
    }
    final entry = _Entry(
      widget.childKey,
      widget.child,
      SpringValue(vsync: this, initial: reduce ? 1 : 0, spring: YoinSprings.defaultEffects),
    );
    entry.alpha.animateTo(1);
    _entries.add(entry);
    for (final e in _entries.take(_entries.length - 1)) {
      e.alpha.animation.addStatusListener((status) => _prune());
    }
    if (reduce) _prune(force: true);
  }

  void _prune({bool force = false}) {
    if (!mounted) return;
    final dead = [
      for (final e in _entries.take(_entries.length - 1))
        if (force || e.alpha.value <= 0.001) e,
    ];
    if (dead.isEmpty) return;
    setState(() {
      for (final e in dead) {
        _entries.remove(e);
        e.alpha.dispose();
      }
    });
  }

  @override
  void dispose() {
    for (final e in _entries) {
      e.alpha.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Stack(
    fit: widget.fit,
    children: [
      for (final (i, e) in _entries.indexed)
        KeyedSubtree(
          key: ObjectKey(e),
          child: IgnorePointer(
            ignoring: i != _entries.length - 1,
            child: FadeTransition(opacity: e.alpha.animation.drive(Tween(begin: 0.0, end: 1.0)), child: e.child),
          ),
        ),
    ],
  );
}
