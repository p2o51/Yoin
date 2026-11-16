/// The emblem as the deck places it, and the award lifecycle of one opening
/// (Android award/MemoriesAwardLifecycle):
///  · each card awards once per opening, the first time it is fully shown —
///    the reveal ≥ 85% open, the deck at rest on it with no finger down, the
///    diary closed; the first beat at least 120 ms after the finger lifts;
///  · a card not yet awarded waits at frame 0 (nothing engraved);
///  · any drag pauses the beats (the picture plays on); leaving the card
///    before the climax revokes the award, so it plays in full next time;
///  · the diary never awards: its 48 dp emblem only nods, the full award
///    waits for the card.
/// Memories unmounts when closed, so every opening awards again.
///
/// The tilt (Android MemoriesShowcase `tiltActive`): one sensor for the deck,
/// read only by the current card's emblem, and only at rest in the card state
/// — the reveal fully open, the diary closed (a spread has none), the pager
/// still and no finger down. Every other emblem draws at rest.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../theme/tokens.dart';
import 'copy/memory_copy.dart';
import 'emblem/groove_award.dart';
import 'emblem/groove_emblem.dart';
import 'emblem/groove_geometry.dart';
import 'emblem/groove_tilt.dart';
import 'memories_ui_state.dart';
import 'memory_palettes.dart';

class MemoryEmblemSlot extends StatelessWidget {
  const MemoryEmblemSlot({
    super.key,
    required this.memory,
    required this.size,
    required this.awards,
    required this.diary,
    this.captionAlpha,
  });

  final MemoryEntry memory;
  final double size;
  final MemoryAwards awards;

  /// The diary's native 48 (bar surface), else the card's.
  final bool diary;

  /// The "Avg." caption's own alpha (the card ⇄ diary morph fades it first).
  final ValueListenable<double>? captionAlpha;

  @override
  Widget build(BuildContext context) {
    final controller = diary ? awards.diaryController(memory) : awards.cardController(memory);
    return GrooveEmblem(
      size: size,
      ratedTracks: [for (final t in memory.tracks) t.rating != null],
      kind: switch (memory.scoreKind) {
        MemoryScoreKind.albumRating => GrooveScoreKind.album,
        MemoryScoreKind.averageTrackRating => GrooveScoreKind.average,
        MemoryScoreKind.none => GrooveScoreKind.none,
      },
      scoreText: memory.score == null ? null : MemoryScores.text(memory.score!),
      palette: memoryPaletteFor(context, memory),
      surface: diary ? GrooveSurface.bar : GrooveSurface.cover,
      award: controller,
      captionAlpha: captionAlpha,
      tilt: diary ? null : awards.tiltFor(memory),
    );
  }
}

class MemoryAwards {
  final Map<int, GrooveAwardController> _cards = {};
  final Map<int, GrooveAwardController> _diaries = {};
  final Set<int> _nodded = {};

  /// Diaries the Diary button opened on a card still due its award: their 48 waits full size and uncut.
  final Set<int> _uncut = {};

  List<MemoryEntry> _deck = const [];
  double _q = 1;
  bool _qSettling = false;
  double _p = 0;
  double _page = 0;
  bool _dragging = false;
  int? _active;
  Timer? _lift;
  bool _liftHeld = false;

  bool _reducedMotion = false;
  bool get reducedMotion => _reducedMotion;
  set reducedMotion(bool value) {
    if (value == _reducedMotion) return;
    _reducedMotion = value;
    _updateTilt();
  }

  /// The deck's one tilt sensor (the surface owns and disposes it).
  GrooveTiltController? _tilt;
  final Map<int, _CardTilt> _tilts = {};

  set tilt(GrooveTiltController? controller) {
    _tilt = controller;
    _updateTilt();
  }

  /// What the card emblem of [m] reads: the deck's tilt while [m] is the
  /// resting card, else rest.
  ValueListenable<Offset>? tiltFor(MemoryEntry m) {
    final t = _tilt;
    if (t == null) return null;
    return _tilts.putIfAbsent(m.sessionId, () => _CardTilt(t, () => _restingId == m.sessionId));
  }

  /// The tilt runs only on the resting card at rest in the card state.
  @visibleForTesting
  bool get tiltActive {
    final page = _page;
    return _restingId != null &&
        (page - page.round()).abs() < 0.001 &&
        _q <= 0.001 &&
        !_qSettling &&
        _p <= 0.001 &&
        !_dragging;
  }

  void _updateTilt() => _tilt?.update(active: tiltActive, reducedMotion: reducedMotion);

  GrooveAwardController cardController(MemoryEntry m) => _cards.putIfAbsent(m.sessionId, () {
    final c = GrooveAwardController();
    c.setPending(true);
    scheduleMicrotask(_evaluate);
    return c;
  });

  GrooveAwardController diaryController(MemoryEntry m) => _diaries.putIfAbsent(m.sessionId, () {
    final c = GrooveAwardController();
    final card = _cards[m.sessionId];
    if (_uncut.contains(m.sessionId) && !(card?.isAwarded ?? false)) {
      c.setUncut();
    } else {
      c.setPending(!(card?.isAwarded ?? false));
    }
    return c;
  });

  void sync(List<MemoryEntry> deck) => _deck = deck;

  void revealChanged(double q, {required bool settling}) {
    _q = q;
    _qSettling = settling;
    _evaluate();
  }

  void diaryChanged(double p) {
    _p = p;
    _evaluate();
  }

  /// The Diary button opened the diary on [m] (Android onDiaryOpening): a running award stops, and a card
  /// still due its award skips its nod — the 96 the morph flies in lands as a full-size, uncut 48, and the
  /// card's award cuts both later.
  void diaryOpening(MemoryEntry m) {
    final active = _active;
    if (active != null) {
      // As when leaving the card: before its climax it is cut short and plays again in full next time.
      final c = _cards[active];
      if (c != null && c.revoke()) c.setPending(true);
      _active = null;
    }
    final id = m.sessionId;
    if (_cards[id]?.isAwarded ?? false) return;
    _nodded.add(id);
    _uncut.add(id);
    // The diary is composed once p moves: a 48 not built yet starts uncut ([diaryController]).
    _diaries[id]?.setUncut();
  }

  void pageChanged(double page) {
    _page = page;
    _evaluate();
  }

  void dragStarted() {
    _dragging = true;
    _lift?.cancel();
    final active = _active == null ? null : _cards[_active];
    active?.pause();
    _evaluate();
  }

  void dragEnded() {
    _dragging = false;
    final active = _active == null ? null : _cards[_active];
    active?.resume();
    // The first beat lands at least 120 ms after the finger lifts.
    _liftHeld = true;
    _lift?.cancel();
    _lift = Timer(const Duration(milliseconds: 120), () {
      _liftHeld = false;
      _evaluate();
    });
  }

  int? get _restingId {
    if (_deck.isEmpty) return null;
    final i = _page.round();
    if ((_page - i).abs() > 0.15 || i < 0 || i >= _deck.length) return null;
    return _deck[i].sessionId;
  }

  void _evaluate() {
    _updateTilt();
    final resting = _restingId;
    // Left a card mid-award: revoke (before the climax it plays again in full).
    final active = _active;
    if (active != null && active != resting) {
      final c = _cards[active];
      if (c != null && c.isAnimating && c.revoke()) c.setPending(true);
      _active = null;
    }
    if (resting == null) return;

    // Diary: the 48 nods once the diary is open on this page; never awards.
    if (_p >= 0.99) {
      final d = _diaries[resting];
      final card = _cards[resting];
      // An uncut 48 shows the finished emblem once the card has had its award.
      if (d != null && (card?.isAwarded ?? false) && _uncut.remove(resting)) d.finishInstantly();
      if (d != null && !_nodded.contains(resting)) {
        _nodded.add(resting);
        if (card?.isAwarded ?? false) {
          d.finishInstantly();
        } else {
          d.nod();
        }
      }
    }

    final shown = _q <= MemoriesTokens.awardRevealGate && !(_qSettling && _q > 0.5) && _p <= 0.001;
    if (!shown || _dragging || _liftHeld) return;
    final c = _cards[resting];
    if (c == null || c.isAwarded || c.isAnimating) return;
    _active = resting;
    c.play();
  }

  void dispose() {
    _lift?.cancel();
    for (final t in _tilts.values) {
      t.dispose();
    }
    for (final c in [..._cards.values, ..._diaries.values]) {
      c.dispose();
    }
  }
}

/// One card's view of the deck tilt: the tilt while the card is current, else rest.
final class _CardTilt extends ChangeNotifier implements ValueListenable<Offset> {
  _CardTilt(this._tilt, this._current) {
    _tilt.addListener(notifyListeners);
  }

  final GrooveTiltController _tilt;
  final bool Function() _current;

  @override
  Offset get value => _current() ? _tilt.value : Offset.zero;

  @override
  void dispose() {
    _tilt.removeListener(notifyListeners);
    super.dispose();
  }
}
