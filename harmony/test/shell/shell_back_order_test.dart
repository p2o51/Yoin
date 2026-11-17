import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/shell/shell_back_order.dart';

/// Android ShellBackResolverTest, one for one, plus the ledger that feeds it.
void main() {
  group('resolveShellBackOwner', () {
    test('should_prioritizeNowPlaying_when_memoriesAlsoOpen', () {
      final owner = resolveShellBackOwner(nowPlayingOpen: true, homeSelected: true, memoriesOpen: true);
      expect(owner, ShellBackOwner.nowPlaying);
    });

    test('should_returnMemories_when_homeOverlayIsActive', () {
      final owner = resolveShellBackOwner(nowPlayingOpen: false, homeSelected: true, memoriesOpen: true);
      expect(owner, ShellBackOwner.memories);
    });

    test('should_giveTheDetailColumnBack_when_itIsOpenOverMemories', () {
      final owner = resolveShellBackOwner(
        nowPlayingOpen: false,
        homeSelected: true,
        memoriesOpen: true,
        detailColumnOpen: true,
      );
      expect(owner, ShellBackOwner.detailColumn);
    });

    test('should_keepNowPlayingFirst_when_theDetailColumnIsAlsoOpen', () {
      final owner = resolveShellBackOwner(nowPlayingOpen: true, homeSelected: false, detailColumnOpen: true);
      expect(owner, ShellBackOwner.nowPlaying);
    });

    test('should_returnHomeEdit_when_editingOnHome', () {
      final owner = resolveShellBackOwner(nowPlayingOpen: false, homeSelected: true, homeEditing: true);
      expect(owner, ShellBackOwner.homeEdit);
    });

    test('should_keepNowPlayingFirst_when_homeEditingBesideThePanel', () {
      final owner = resolveShellBackOwner(
        nowPlayingOpen: true,
        nowPlayingIsPanel: true,
        homeSelected: true,
        homeEditing: true,
        detailColumnOpen: true,
        recency: const [ShellBackOwner.nowPlaying, ShellBackOwner.detailColumn],
      );
      expect(owner, ShellBackOwner.nowPlaying);
    });

    test('should_giveHomeEditBack_when_theDetailColumnIsAlsoOpen', () {
      final owner = resolveShellBackOwner(
        nowPlayingOpen: false,
        homeSelected: true,
        homeEditing: true,
        detailColumnOpen: true,
        recency: const [ShellBackOwner.detailColumn],
      );
      expect(owner, ShellBackOwner.homeEdit);
    });

    test('should_ignoreEditing_when_libraryIsSelected', () {
      expect(resolveShellBackOwner(nowPlayingOpen: false, homeSelected: false, homeEditing: true), ShellBackOwner.none);
      expect(
        resolveShellBackOwner(nowPlayingOpen: false, homeSelected: false, homeEditing: true, detailColumnOpen: true),
        ShellBackOwner.detailColumn,
      );
    });

    test('should_returnNone_when_noShellOverlayOwnsBack', () {
      final owner = resolveShellBackOwner(nowPlayingOpen: false, homeSelected: false, memoriesOpen: true);
      expect(owner, ShellBackOwner.none);
    });

    test('should_closeTheLastOpenedFirst_when_panelColumnAndMemoriesAreOpen', () {
      // Opened: column, Now Playing panel, Memories — back undoes them in reverse.
      final recency = <ShellBackOwner>[]
        ..noteShellSurface(ShellBackOwner.detailColumn, open: true)
        ..noteShellSurface(ShellBackOwner.nowPlaying, open: true)
        ..noteShellSurface(ShellBackOwner.memories, open: true);
      ShellBackOwner owner({required bool np, required bool column, required bool memories}) => resolveShellBackOwner(
        nowPlayingOpen: np,
        nowPlayingIsPanel: true,
        homeSelected: true,
        memoriesOpen: memories,
        detailColumnOpen: column,
        recency: recency,
      );

      expect(owner(np: true, column: true, memories: true), ShellBackOwner.memories);
      recency.noteShellSurface(ShellBackOwner.memories, open: false);
      expect(owner(np: true, column: true, memories: false), ShellBackOwner.nowPlaying);
      recency.noteShellSurface(ShellBackOwner.nowPlaying, open: false);
      expect(owner(np: false, column: true, memories: false), ShellBackOwner.detailColumn);
    });

    test('should_keepTheFullPlayerFirst_when_memoriesOpenedAfterIt', () {
      final owner = resolveShellBackOwner(
        nowPlayingOpen: true,
        homeSelected: true,
        memoriesOpen: true,
        recency: const [ShellBackOwner.nowPlaying, ShellBackOwner.memories],
      );
      expect(owner, ShellBackOwner.nowPlaying);
    });

    test('should_closeTheColumnFirst_when_itOpenedFromMemories', () {
      final owner = resolveShellBackOwner(
        nowPlayingOpen: false,
        homeSelected: true,
        memoriesOpen: true,
        detailColumnOpen: true,
        recency: const [ShellBackOwner.memories, ShellBackOwner.detailColumn],
      );
      expect(owner, ShellBackOwner.detailColumn);
    });

    test('should_closeMemoriesFirst_when_itOpenedBesideAnOpenColumn', () {
      final owner = resolveShellBackOwner(
        nowPlayingOpen: false,
        homeSelected: true,
        memoriesOpen: true,
        detailColumnOpen: true,
        recency: const [ShellBackOwner.detailColumn, ShellBackOwner.memories],
      );
      expect(owner, ShellBackOwner.memories);
    });

    test('should_closeTheColumnFirst_when_aMemoryOpenedItsAlbumInTheOpenColumn', () {
      // Opened: column, Memories — then "Go to album" swaps the open column's page.
      final recency = <ShellBackOwner>[]
        ..noteShellSurface(ShellBackOwner.detailColumn, open: true)
        ..noteShellSurface(ShellBackOwner.memories, open: true)
        ..noteShellSurface(ShellBackOwner.detailColumn, open: true);
      ShellBackOwner owner({required bool column}) => resolveShellBackOwner(
        nowPlayingOpen: false,
        homeSelected: true,
        memoriesOpen: true,
        detailColumnOpen: column,
        recency: recency,
      );

      expect(recency, [ShellBackOwner.memories, ShellBackOwner.detailColumn]);
      expect(owner(column: true), ShellBackOwner.detailColumn);
      // The album leaves; the memory it came from is still there and answers next.
      recency.noteShellSurface(ShellBackOwner.detailColumn, open: false);
      expect(owner(column: false), ShellBackOwner.memories);
    });

    test('should_stepThePlayerBackFirst_when_itWentDeeperBesideANewerSurface', () {
      // Opened: side panel, column, Memories — then lyrics or focus in the panel.
      final recency = <ShellBackOwner>[]
        ..noteShellSurface(ShellBackOwner.nowPlaying, open: true)
        ..noteShellSurface(ShellBackOwner.detailColumn, open: true)
        ..noteShellSurface(ShellBackOwner.memories, open: true);
      ShellBackOwner owner() => resolveShellBackOwner(
        nowPlayingOpen: true,
        nowPlayingIsPanel: true,
        homeSelected: true,
        memoriesOpen: true,
        detailColumnOpen: true,
        recency: recency,
      );

      expect(owner(), ShellBackOwner.memories);
      recency.noteShellSurface(ShellBackOwner.nowPlaying, open: true);
      expect(owner(), ShellBackOwner.nowPlaying);
    });

    test('should_fallBackToNowPlayingThenColumnThenMemories_when_noneIsInTheRecency', () {
      ShellBackOwner owner({required bool np}) => resolveShellBackOwner(
        nowPlayingOpen: np,
        nowPlayingIsPanel: true,
        homeSelected: true,
        memoriesOpen: true,
        detailColumnOpen: true,
      );
      expect(owner(np: true), ShellBackOwner.nowPlaying);
      expect(owner(np: false), ShellBackOwner.detailColumn);
    });

    test('should_rankAnUnrecordedSurfaceOldest_when_othersAreRecorded', () {
      final owner = resolveShellBackOwner(
        nowPlayingOpen: true,
        nowPlayingIsPanel: true,
        homeSelected: true,
        memoriesOpen: true,
        recency: const [ShellBackOwner.memories],
      );
      expect(owner, ShellBackOwner.memories);
    });
  });

  group('ShellBackLedger', () {
    test('should_moveASurfaceToTheEnd_when_itIsNotedOpenAgain', () {
      final ledger = ShellBackLedger()
        ..note(ShellBackOwner.nowPlaying, open: true)
        ..note(ShellBackOwner.detailColumn, open: true)
        ..note(ShellBackOwner.nowPlaying, open: true);
      expect(ledger.recency, [ShellBackOwner.detailColumn, ShellBackOwner.nowPlaying]);
      ledger.note(ShellBackOwner.nowPlaying, open: false);
      expect(ledger.recency, [ShellBackOwner.detailColumn]);
    });

    test('should_readEachOwnersLiveState_when_resolving', () {
      var npOpen = true;
      var columnOpen = true;
      var memoriesOpen = false;
      final ledger = ShellBackLedger()
        ..bindNowPlaying(open: () => npOpen, isPanel: () => true)
        ..bindShell(homeSelected: () => true, memoriesOpen: () => memoriesOpen, detailColumnOpen: () => columnOpen)
        ..note(ShellBackOwner.nowPlaying, open: true)
        ..note(ShellBackOwner.detailColumn, open: true);

      expect(ledger.owner, ShellBackOwner.detailColumn);
      expect(ledger.owns(ShellBackOwner.nowPlaying), isFalse);
      memoriesOpen = true;
      ledger.note(ShellBackOwner.memories, open: true);
      expect(ledger.owner, ShellBackOwner.memories);
      memoriesOpen = false;
      ledger.note(ShellBackOwner.memories, open: false);
      columnOpen = false;
      ledger.note(ShellBackOwner.detailColumn, open: false);
      expect(ledger.owner, ShellBackOwner.nowPlaying);
      npOpen = false;
      expect(ledger.owner, ShellBackOwner.none);
    });

    test('should_giveHomeEditBack_when_editingBesideTheColumnAndMemories', () {
      var editing = true;
      final ledger = ShellBackLedger()
        ..bindShell(homeSelected: () => true, memoriesOpen: () => false, detailColumnOpen: () => true)
        ..bindHomeEdit(() => editing)
        ..note(ShellBackOwner.detailColumn, open: true);
      expect(ledger.owner, ShellBackOwner.homeEdit);
      editing = false;
      expect(ledger.owner, ShellBackOwner.detailColumn);
    });

    test('should_answerWithThePlayer_when_aSheetIsOpenOverThePanelBesideANewerSurface', () {
      final owner = resolveShellBackOwner(
        nowPlayingOpen: true,
        nowPlayingIsPanel: true,
        nowPlayingSheetOpen: true,
        homeSelected: true,
        detailColumnOpen: true,
        recency: const [ShellBackOwner.nowPlaying, ShellBackOwner.detailColumn],
      );
      expect(owner, ShellBackOwner.nowPlaying);
    });

    test('should_noteMemoriesOnlyOverHome_when_theSectionSwitches', () {
      var home = true;
      var memories = false;
      var column = false;
      final ledger = ShellBackLedger()
        ..bindShell(homeSelected: () => home, memoriesOpen: () => memories, detailColumnOpen: () => column);
      memories = true;
      ledger.syncShell();
      column = true;
      ledger.syncShell();
      expect(ledger.recency, [ShellBackOwner.memories, ShellBackOwner.detailColumn]);
      expect(ledger.owner, ShellBackOwner.detailColumn);

      // Library in front (a shortcut): Memories leaves the order (Android memoriesOpen).
      home = false;
      ledger.syncShell();
      expect(ledger.recency, [ShellBackOwner.detailColumn]);
      // Home again with Memories still open: it is the newest surface again.
      home = true;
      ledger.syncShell();
      expect(ledger.recency, [ShellBackOwner.detailColumn, ShellBackOwner.memories]);
      expect(ledger.owner, ShellBackOwner.memories);
    });

    test('should_noteTheColumnNewestWithoutANoteOnClose_when_aPageIsShownInTheOpenColumn', () {
      var column = false;
      final ledger = ShellBackLedger()
        ..bindShell(homeSelected: () => true, memoriesOpen: () => true, detailColumnOpen: () => column);
      column = true;
      ledger.columnPageShown();
      ledger.syncShell();
      ledger.syncShell();
      expect(ledger.recency, [ShellBackOwner.detailColumn, ShellBackOwner.memories]);
      ledger.columnPageShown();
      expect(ledger.recency, [ShellBackOwner.memories, ShellBackOwner.detailColumn]);
      column = false;
      ledger.syncShell();
      expect(ledger.recency, [ShellBackOwner.memories]);
    });

    test('should_forgetTheShellsSurfaces_when_theShellUnbinds', () {
      final ledger = ShellBackLedger()
        ..bindShell(homeSelected: () => true, memoriesOpen: () => true, detailColumnOpen: () => true)
        ..note(ShellBackOwner.detailColumn, open: true)
        ..note(ShellBackOwner.memories, open: true);
      ledger.unbindShell();
      expect(ledger.recency, isEmpty);
      expect(ledger.owner, ShellBackOwner.none);
    });
  });
}
