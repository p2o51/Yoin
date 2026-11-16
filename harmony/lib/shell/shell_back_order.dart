import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The window-level owners of system back in the shell (Android
/// `ShellBackOwner`).
enum ShellBackOwner {
  none,
  memories,

  /// The detail column beside the shell on a Wide window (its stack pops
  /// first, then it closes).
  detailColumn,

  /// Home's edit mode: back is a discrete Done.
  homeEdit,
  nowPlaying,
}

/// Who answers system back in the shell window (Android
/// `resolveShellBackOwner`, ShellBackResolver.kt).
///
/// A full-window Now Playing covers everything and always answers first;
/// Home edit mode comes next (the cards are disabled while editing, and
/// closing a column would re-tier the shell mid-edit).
///
/// Surfaces that sit SIDE BY SIDE — the Now Playing side panel, the detail
/// column, Memories over the shell column — close in reverse order of
/// opening (owner 2026-11-12: 「返回是进入的反序」): [recency] lists them
/// oldest first as the shell saw them open, and the latest one still open
/// answers. Going further inside an open one — another page shown in the
/// column, the player's lyrics or focus — counts as entering it again: it
/// is noted as the newest, so back undoes that step first. One missing from
/// [recency] ranks oldest, ties falling back to Now Playing > detail column
/// > Memories.
ShellBackOwner resolveShellBackOwner({
  required bool nowPlayingOpen,
  required bool homeSelected,
  bool nowPlayingIsPanel = false,
  bool homeEditing = false,
  bool memoriesOpen = false,
  bool detailColumnOpen = false,
  List<ShellBackOwner> recency = const [],
}) {
  if (nowPlayingOpen && !nowPlayingIsPanel) return ShellBackOwner.nowPlaying;
  if (homeSelected && homeEditing) {
    return nowPlayingOpen ? ShellBackOwner.nowPlaying : ShellBackOwner.homeEdit;
  }
  final open = [
    if (nowPlayingOpen) ShellBackOwner.nowPlaying,
    if (detailColumnOpen) ShellBackOwner.detailColumn,
    if (homeSelected && memoriesOpen) ShellBackOwner.memories,
  ];
  // Stable: equal recency keeps the default order above.
  var owner = ShellBackOwner.none;
  var newest = -2;
  for (final surface in open) {
    final at = recency.lastIndexOf(surface);
    if (at > newest) {
      newest = at;
      owner = surface;
    }
  }
  return owner;
}

/// The side-by-side surfaces' opening order, oldest first (Android
/// `noteShellSurface`).
extension ShellBackRecency on List<ShellBackOwner> {
  /// [owner] opened or was entered further (moves to the end), or closed (leaves).
  void noteShellSurface(ShellBackOwner owner, {required bool open}) {
    remove(owner);
    if (open) add(owner);
  }
}

/// The window's one back ledger: the side-by-side surfaces' recency, and
/// each owner's live state, read when a back arrives. Owners note
/// themselves where they open, close or go deeper (Now Playing in the
/// chrome, the column and Memories in the shell, edit mode in Home); their
/// [ChromeBackEntry]s each own back exactly while [owner] names them, so
/// one back reaches one surface and each surface keeps its own close
/// motion and controller.
class ShellBackLedger {
  final List<ShellBackOwner> _recency = [];

  /// Oldest first.
  List<ShellBackOwner> get recency => List.unmodifiable(_recency);

  bool Function() _nowPlayingOpen = _closed;
  bool Function() _nowPlayingIsPanel = _closed;
  bool Function() _homeSelected = _open;
  bool Function() _homeEditing = _closed;
  bool Function() _memoriesOpen = _closed;
  bool Function() _detailColumnOpen = _closed;

  static bool _closed() => false;
  static bool _open() => true;

  /// [owner] opened or was entered further (it becomes the newest), or closed.
  void note(ShellBackOwner owner, {required bool open}) => _recency.noteShellSurface(owner, open: open);

  void bindNowPlaying({required bool Function() open, required bool Function() isPanel}) {
    _nowPlayingOpen = open;
    _nowPlayingIsPanel = isPanel;
  }

  void unbindNowPlaying() {
    _nowPlayingOpen = _closed;
    _nowPlayingIsPanel = _closed;
    note(ShellBackOwner.nowPlaying, open: false);
  }

  void bindShell({
    required bool Function() homeSelected,
    required bool Function() memoriesOpen,
    required bool Function() detailColumnOpen,
  }) {
    _homeSelected = homeSelected;
    _memoriesOpen = memoriesOpen;
    _detailColumnOpen = detailColumnOpen;
  }

  void unbindShell() {
    _homeSelected = _open;
    _memoriesOpen = _closed;
    _detailColumnOpen = _closed;
    note(ShellBackOwner.memories, open: false);
    note(ShellBackOwner.detailColumn, open: false);
  }

  void bindHomeEdit(bool Function() editing) => _homeEditing = editing;

  void unbindHomeEdit() => _homeEditing = _closed;

  /// Who answers a back arriving now.
  ShellBackOwner get owner => resolveShellBackOwner(
    nowPlayingOpen: _nowPlayingOpen(),
    nowPlayingIsPanel: _nowPlayingIsPanel(),
    homeSelected: _homeSelected(),
    homeEditing: _homeEditing(),
    memoriesOpen: _memoriesOpen(),
    detailColumnOpen: _detailColumnOpen(),
    recency: _recency,
  );

  bool owns(ShellBackOwner surface) => owner == surface;
}

/// One ledger per window (the app's one shell).
final shellBackLedgerProvider = Provider<ShellBackLedger>((ref) => ShellBackLedger());
