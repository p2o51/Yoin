import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/library/library_cells.dart';
import 'package:yoin/library/library_grid_anchor.dart';
import 'package:yoin/library/library_screen.dart';
import 'package:yoin/library/library_search.dart';
import 'package:yoin/library/library_search_view.dart';
import 'package:yoin/library/library_sort.dart';
import 'package:yoin/library/library_view_model.dart';
import 'package:yoin/library/library_views.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/shell/yoin_section.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/component/yoin_fast_scroller.dart';
import 'package:yoin/ui/navigation/pane/detail_pane.dart';

import '../support/fake_playback_engine.dart';
import '../support/library_fake_source.dart';

/// Library grids: a short shelf grows its cells past Compact (owner
/// 2026-11-12), and a grid keeps its place when its columns change
/// (Android LibrarySparseGrid.kt, LibraryGridAnchor.kt).

void _window(WidgetTester tester, double width, double height) {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = Size(width, height);
}

Future<ProviderContainer> _pumpLibrary(WidgetTester tester, LibraryFakeSource source) async {
  final container = ProviderContainer(
    overrides: [
      activeSourceProvider.overrideWith((ref) async => source),
      keyValueStoreProvider.overrideWithValue(InMemoryStore()),
      secretStoreProvider.overrideWithValue(InMemoryStore()),
      activityLogProvider.overrideWith((ref) async => null),
      playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
    ],
  );
  addTearDown(container.dispose);
  container.read(shellSectionProvider.notifier).select(YoinSection.library);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        theme: yoinTheme(Brightness.light),
        home: const Scaffold(body: LibraryScreen()),
      ),
    ),
  );
  await _settle(tester);
  return container;
}

/// A few frames, so springs run (one long pump is one frame).
Future<void> _frames(WidgetTester tester) async {
  for (var i = 0; i < 20; i++) {
    await tester.pump(const Duration(milliseconds: 16));
  }
}

Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 20; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

/// Albums named so the alphabetical sort cuts a section per letter, a dozen or so items each.
List<Album> _albums(int count) => [
  for (var i = 0; i < count; i++)
    LibraryFixtures.album(
      'al$i',
      name: '${String.fromCharCode(65 + i % 26) * 2} ${i.toString().padLeft(3, '0')}',
      artist: 'Artist',
    ),
];

Future<void> _showAlbums(WidgetTester tester, ProviderContainer c, {LibrarySort? sort}) async {
  final vm = c.read(libraryViewModelProvider.notifier);
  vm.selectTab(LibraryTab.albums);
  if (sort != null) await vm.selectSort(LibraryTab.albums, sort);
  await _settle(tester);
}

Finder _albumsView() => find.byKey(libraryScrollStorageKey(LibraryTab.albums));

ScrollableState _albumsScrollable(WidgetTester tester) =>
    tester.state<ScrollableState>(find.descendant(of: _albumsView(), matching: find.byType(Scrollable)).first);

LibraryGridAnchor _anchor(WidgetTester tester) =>
    (_albumsScrollable(tester).widget.controller! as LibraryGridScrollController).anchor;

List<LibraryItem> _albumItems(ProviderContainer c) =>
    (c.read(libraryViewModelProvider) as LibraryContent).sorted.of(LibraryTab.albums)!.items;

/// The cell of [item] (its cover's square is the cell's width).
Rect _cell(WidgetTester tester, LibraryItem item) => tester.getRect(find.byKey(ValueKey(item.key)));

/// The first cell of the top row's top, against the grid's own top (rows come back at its 8 top padding).
double _topOf(WidgetTester tester, LibraryItem item) => _cell(tester, item).top - tester.getTopLeft(_albumsView()).dy;

void main() {
  group('short shelf grows its cells', () {
    testWidgets('should_growCells_when_fewAlbumsOnALandscapeTablet', (tester) async {
      _window(tester, 1280, 800);
      addTearDown(tester.view.reset);
      final c = await _pumpLibrary(tester, LibraryFakeSource(albums: _albums(8)));
      await _showAlbums(tester, c);

      // 11 usual columns; eight albums fill half the columns' rows, so six grow to fill the page.
      final width = _cell(tester, _albumItems(c).first).width;
      expect(width, closeTo((1280 - 32 - 12 * 5) / 6, 0.5));
    });

    testWidgets('should_keepUsualCells_when_theShelfFillsThePage', (tester) async {
      _window(tester, 1280, 800);
      addTearDown(tester.view.reset);
      final c = await _pumpLibrary(tester, LibraryFakeSource(albums: _albums(200)));
      await _showAlbums(tester, c);

      expect(_cell(tester, _albumItems(c).first).width, closeTo((1280 - 32 - 12 * 10) / 11, 0.5));
    });

    testWidgets('should_keepThreeColumns_when_compactWidth', (tester) async {
      _window(tester, 400, 860);
      addTearDown(tester.view.reset);
      final c = await _pumpLibrary(tester, LibraryFakeSource(albums: _albums(2)));
      await _showAlbums(tester, c);

      expect(_cell(tester, _albumItems(c).first).width, closeTo((400 - 32 - 12 * 2) / 3, 0.5));
    });

    testWidgets('should_growToHalfAtMost_when_portraitTablet', (tester) async {
      _window(tester, 800, 1280);
      addTearDown(tester.view.reset);
      final c = await _pumpLibrary(tester, LibraryFakeSource(albums: _albums(1)));
      await _showAlbums(tester, c);

      // Six usual columns, never fewer than three.
      expect(_cell(tester, _albumItems(c).first).width, closeTo((800 - 32 - 12 * 2) / 3, 0.5));
    });

    testWidgets('should_keepUsualCells_when_landscapeHandset', (tester) async {
      _window(tester, 900, 420);
      addTearDown(tester.view.reset);
      final c = await _pumpLibrary(tester, LibraryFakeSource(albums: _albums(3)));
      await _showAlbums(tester, c);

      // 900 → seven usual columns, kept on a short window.
      expect(_cell(tester, _albumItems(c).first).width, closeTo((900 - 32 - 12 * 6) / 7, 0.5));
    });

    testWidgets('should_keepUsualCells_when_searchResults', (tester) async {
      _window(tester, 1280, 800);
      addTearDown(tester.view.reset);
      final source = LibraryFakeSource(searchResults: SearchResults(albums: _albums(4)));
      final c = await _openSearch(tester, source);
      c.read(librarySearchProvider.notifier).setFilter(LibrarySearchFilter.albums);
      await _settle(tester);

      final cover = tester.getRect(find.byKey(ValueKey(LibraryAlbumItem(source.searchResults.albums.first).key)));
      expect(cover.width, closeTo((1280 - 32 - 12 * 10) / 11, 0.5));
      await _closeSearch(tester);
    });
  });

  group('grid keeps its place', () {
    testWidgets('should_bringTheAnchoredRowToTheTop_when_widthChanges', (tester) async {
      _window(tester, 1280, 800);
      addTearDown(tester.view.reset);
      final c = await _pumpLibrary(tester, LibraryFakeSource(albums: _albums(300)));
      await _showAlbums(tester, c);

      await tester.drag(_albumsView(), const Offset(0, -1700));
      await _settle(tester);
      final anchor = _anchor(tester);
      expect(anchor.index, greaterThan(0));
      final anchored = _albumItems(c)[anchor.index];

      // Portrait tablet, a split column, a phone, the MateBook window, and back.
      for (final (w, h) in const [(800.0, 1280.0), (600.0, 1000.0), (412.0, 900.0), (1440.0, 900.0), (1280.0, 800.0)]) {
        _window(tester, w, h);
        await tester.pump();
        expect(
          _topOf(tester, anchored),
          closeTo(LibraryTokens.gridTopPadding, 0.5),
          reason: 'at $w × $h the anchored row leads',
        );
        expect(anchor.index, _albumItems(c).indexOf(anchored), reason: 'a relayout is no scroll');
        await _settle(tester);
        expect(_topOf(tester, anchored), closeTo(LibraryTokens.gridTopPadding, 0.5));
      }
    });

    testWidgets('should_followTheUsersScroll_when_scrolledAfterAResize', (tester) async {
      _window(tester, 1280, 800);
      addTearDown(tester.view.reset);
      final c = await _pumpLibrary(tester, LibraryFakeSource(albums: _albums(300)));
      await _showAlbums(tester, c);

      await tester.drag(_albumsView(), const Offset(0, -900));
      await _settle(tester);
      _window(tester, 800, 1280);
      await _settle(tester);
      await tester.drag(_albumsView(), const Offset(0, -1200));
      await _settle(tester);
      final anchored = _albumItems(c)[_anchor(tester).index];

      _window(tester, 1280, 800);
      await _settle(tester);
      expect(_topOf(tester, anchored), closeTo(LibraryTokens.gridTopPadding, 0.5));
    });

    testWidgets('should_returnToTheTop_when_restingAtTheTop', (tester) async {
      _window(tester, 1280, 800);
      addTearDown(tester.view.reset);
      final c = await _pumpLibrary(tester, LibraryFakeSource(albums: _albums(300)));
      await _showAlbums(tester, c);

      _window(tester, 800, 1280);
      await _settle(tester);
      expect(_albumsScrollable(tester).position.pixels, 0);
      expect(find.text('Recents'), findsOneWidget, reason: 'the sort row still leads');
    });

    testWidgets('should_keepTheListTop_when_aNewAlbumLandsAtTheHead', (tester) async {
      _window(tester, 1280, 800);
      addTearDown(tester.view.reset);
      final source = LibraryFakeSource(albums: _albums(11));
      final c = await _pumpLibrary(tester, source);
      await _showAlbums(tester, c, sort: LibrarySort.alphabetical);
      expect(_albumsScrollable(tester).position.pixels, 0);

      source.albums = [LibraryFixtures.album('new', name: 'AA', artist: 'Artist'), ...source.albums];
      await c.read(libraryViewModelProvider.notifier).refresh();
      await _settle(tester);

      expect(_albumItems(c).first.key, contains('new'));
      expect(_albumsScrollable(tester).position.pixels, 0);
      expect(_topOf(tester, _albumItems(c).first), greaterThan(0), reason: 'under the sort row, in sight');
      expect(find.text('AA').hitTestable(), findsOneWidget);
    });

    testWidgets('should_keepTheJumpedSection_when_widthChangesAfterAFastScroll', (tester) async {
      _window(tester, 1280, 800);
      addTearDown(tester.view.reset);
      final c = await _pumpLibrary(tester, LibraryFakeSource(albums: _albums(400)));
      await _showAlbums(tester, c, sort: LibrarySort.alphabetical);

      // A small scroll wakes the handle at the top of its track.
      await tester.drag(_albumsView(), const Offset(0, -40));
      await _frames(tester);
      final scroller = tester.getRect(find.byType(YoinFastScroller));
      final position = _albumsScrollable(tester).position;
      final bottom = tester.widget<YoinFastScroller>(find.byType(YoinFastScroller)).bottomInset;
      final track = scroller.height - LibraryTokens.scrollerTopInset - bottom;
      final handleY =
          scroller.top +
          LibraryTokens.scrollerTopInset +
          FastScrollerGeometry.thumbTop(position.pixels / position.maxScrollExtent, track) +
          FastScrollerGeometry.thumbHeight / 2;
      final gesture = await tester.startGesture(Offset(scroller.right - 12, handleY));
      await tester.pump(const Duration(milliseconds: 16));
      await gesture.moveBy(Offset(0, track * 0.43));
      await tester.pump(const Duration(milliseconds: 16));
      await gesture.up();
      await _frames(tester);

      final view = (c.read(libraryViewModelProvider) as LibraryContent).sorted.of(LibraryTab.albums)!;
      final anchor = _anchor(tester);
      final place = libraryGridPlaceAt(
        pixels: position.pixels,
        leading: anchor.head,
        stride: anchor.rowPx,
        columns: 11,
        itemCount: view.items.length,
      );
      expect(place.index, greaterThan(100), reason: 'the handle jumped the grid');
      final expected = libraryJumpAnchor(
        rowStart: place.index,
        columns: 11,
        sectionStarts: [for (final s in view.sections) s.startIndex],
      );
      expect(anchor.index, expected, reason: 'the section starting on the top row, else the row');
      final anchored = view.items[expected];

      _window(tester, 800, 1280);
      await tester.pump();
      expect(
        _topOf(tester, anchored),
        closeTo(LibraryTokens.gridTopPadding, 0.5),
        reason: "the section's first item leads the new rows",
      );
    });

    testWidgets('should_findItsPlace_when_theViewComesBackAtAnotherWidth', (tester) async {
      _window(tester, 1280, 800);
      addTearDown(tester.view.reset);
      final c = await _pumpLibrary(tester, LibraryFakeSource(albums: _albums(300)));
      await _showAlbums(tester, c);
      await tester.drag(_albumsView(), const Offset(0, -1700));
      await _settle(tester);
      final anchored = _albumItems(c)[_anchor(tester).index];

      // Away on All while the window turns, then back to Albums.
      c.read(libraryViewModelProvider.notifier).selectTab(LibraryTab.albums); // the chip again clears it
      await _settle(tester);
      expect(_albumsView(), findsNothing);
      _window(tester, 800, 1280);
      await _settle(tester);
      c.read(libraryViewModelProvider.notifier).selectTab(LibraryTab.albums);
      await _settle(tester);

      expect(_topOf(tester, anchored), closeTo(LibraryTokens.gridTopPadding, 0.5));
    });
  });

  group('search grids land exactly', () {
    testWidgets('should_landExactly_when_backAtTheWidthItWasLeftAt', (tester) async {
      _window(tester, 800, 1280);
      addTearDown(tester.view.reset);
      final source = LibraryFakeSource(searchResults: SearchResults(albums: _albums(200)));
      final c = await _openSearch(tester, source);
      c.read(librarySearchProvider.notifier).setFilter(LibrarySearchFilter.albums);
      await _settle(tester);

      final grid = find.descendant(of: find.byType(GridView), matching: find.byType(Scrollable));
      await tester.drag(grid, const Offset(0, -1234));
      await _settle(tester);
      final left = tester.state<ScrollableState>(grid).position.pixels;
      final anchor = (tester.state<ScrollableState>(grid).widget.controller! as LibraryGridScrollController).anchor;
      final share = anchor.offset / anchor.rowPx;
      expect(share, greaterThan(0), reason: 'left partway into a row');

      _window(tester, 1280, 800);
      await _settle(tester);
      final turned = tester.state<ScrollableState>(grid).position.pixels;
      // The same share of the anchored row scrolled past, on the new rows (11 columns).
      final stride = libraryCellExtent((1280 - 32 - 12 * 10) / 11, TextScaler.noScaling) + LibraryTokens.gridGutterV;
      expect(turned, closeTo((anchor.index ~/ 11 + share) * stride, 0.01));

      _window(tester, 800, 1280);
      await _settle(tester);
      expect(tester.state<ScrollableState>(grid).position.pixels, closeTo(left, 0.01));
      await _closeSearch(tester);
    });

    testWidgets('should_landExactly_when_backFromTheDetailColumn', (tester) async {
      _window(tester, 1280, 800);
      addTearDown(tester.view.reset);
      final source = LibraryFakeSource(searchResults: SearchResults(albums: _albums(300)));
      final c = await _openSearch(tester, source);
      c.read(librarySearchProvider.notifier).setFilter(LibrarySearchFilter.albums);
      await _settle(tester);
      final grid = find.descendant(of: find.byType(GridView), matching: find.byType(Scrollable));
      await tester.drag(grid, const Offset(0, -1234));
      await _settle(tester);
      final left = tester.state<ScrollableState>(grid).position.pixels;
      expect(left, greaterThan(0));

      // A result went to the detail column: the surface collapses, its query kept.
      final route = ModalRoute.of(tester.element(find.byType(LibrarySearchSurface)))! as DetailPaneYieldingRoute;
      route.yieldToDetailColumn();
      await _settle(tester);
      expect(find.byType(LibrarySearchSurface), findsNothing);
      expect(c.read(librarySearchProvider).query, 'a');

      await tester.tap(find.text('pill'));
      await _settle(tester);
      expect(tester.state<ScrollableState>(grid).position.pixels, closeTo(left, 0.01));
      await _closeSearch(tester);
    });

    test('should_keepOneAnchorPerScope_when_theSameTypeIsShownInBoth', () {
      final anchors = LibrarySearchGridAnchors();
      final library = anchors.of(LibrarySearchScope.library, LibrarySearchFilter.albums);
      expect(anchors.of(LibrarySearchScope.library, LibrarySearchFilter.albums), same(library));
      expect(anchors.of(LibrarySearchScope.catalog, LibrarySearchFilter.albums), isNot(same(library)));
      expect(anchors.of(LibrarySearchScope.library, LibrarySearchFilter.artists), isNot(same(library)));
    });

    test('should_forgetEveryPlace_when_theQueryChanges', () {
      final c = ProviderContainer(overrides: [activeSourceProvider.overrideWith((ref) async => LibraryFakeSource())]);
      addTearDown(c.dispose);
      final anchors = c.read(librarySearchGridAnchorsProvider);
      final anchor = anchors.of(LibrarySearchScope.library, LibrarySearchFilter.albums)
        ..index = 40
        ..width = 800;
      c.read(librarySearchProvider.notifier).search('a');
      expect(anchor.index, -1);
      expect(anchor.width, 0);
    });

    testWidgets('should_startAtTheTop_when_aNewQueryLands', (tester) async {
      _window(tester, 800, 1280);
      addTearDown(tester.view.reset);
      final source = LibraryFakeSource(searchResults: SearchResults(albums: _albums(200)));
      final c = await _openSearch(tester, source);
      c.read(librarySearchProvider.notifier).setFilter(LibrarySearchFilter.albums);
      await _settle(tester);
      final grid = find.descendant(of: find.byType(GridView), matching: find.byType(Scrollable));
      await tester.drag(grid, const Offset(0, -1234));
      await _settle(tester);

      await tester.enterText(find.byType(TextField), 'other');
      await _settle(tester);
      _window(tester, 1280, 800);
      await _settle(tester);

      expect(tester.state<ScrollableState>(grid).position.pixels, 0);
      await _closeSearch(tester);
    });
  });
}

/// Opens the search over a page and searches "a".
Future<ProviderContainer> _openSearch(WidgetTester tester, LibraryFakeSource source) async {
  final c = ProviderContainer(overrides: [activeSourceProvider.overrideWith((ref) async => source)]);
  addTearDown(c.dispose);
  c.listen(librarySearchProvider, (_, _) {});
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: c,
      child: MaterialApp(
        theme: yoinTheme(Brightness.light),
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () => openLibrarySearch(context, const Rect.fromLTWH(16, 8, 300, 56)),
              child: const Text('pill'),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('pill'));
  await _settle(tester);
  await tester.enterText(find.byType(TextField), 'a');
  await _settle(tester);
  return c;
}

/// Closes the search (it clears the query as it goes, before the container does).
Future<void> _closeSearch(WidgetTester tester) async {
  await tester.binding.handlePopRoute();
  await _settle(tester);
}
