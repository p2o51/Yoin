/// Share as image (Android ShareContent.kt, owner 2026-11-25): one sheet,
/// three tabs — the lyrics, the song's About (AI / objective text, tertiary)
/// and one of the listener's notes (their own words, primary) — each with its
/// own layouts. This file is the model: what each tab shares, which layouts it
/// offers, the layout each tab last used, and how Now Playing's state becomes
/// a [ShareContent]. The rules themselves are the Dart port of :shared
/// ([YoinShareCards], lib/data/share/); share_cards.dart draws the images,
/// share_sheet.dart the sheet.
library;

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/about/song_about_store.dart';
import '../data/ai/ai_settings.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../data/share/yoin_share_cards.dart';
import '../platform/storage/stores.dart';
import 'about_logic.dart';
import 'lyrics_logic.dart';
import 'nowplaying_strings.dart';

export '../data/share/yoin_share_cards.dart';

/// Everything the sheet can share for one song, frozen when it opens.
@immutable
final class ShareContent {
  const ShareContent({required this.song, this.lyrics, this.about, this.note, this.aiProvider = AiProvider.gemini});

  /// The song; its [ShareSong.coverArtUrl] is the cover's resolved url (null: a quiet tile).
  final ShareSong song;
  final ShareLyrics? lyrics;
  final ShareAbout? about;
  final ShareNote? note;

  /// The AI service chosen in Settings: About's "由 X 整理".
  final AiProvider aiProvider;

  ShareCardContent get shared => ShareCardContent(song: song, lyrics: lyrics, about: about, note: note);

  bool has(ShareTab tab) => YoinShareCards.has(shared, tab);

  /// The tabs this song has something for, in order.
  List<ShareTab> get tabs => YoinShareCards.tabs(shared);

  /// The layouts [tab] offers for this content, in 版式 order (empty when the tab has nothing).
  List<ShareLayout> layoutsFor(ShareTab tab) => YoinShareCards.layouts(shared, tab);

  /// [remembered] when this content offers it, else the tab's first layout.
  ShareLayout? resolveLayout(ShareTab tab, ShareLayout? remembered) =>
      YoinShareCards.resolveLayout(shared, tab, remembered);
}

/// A lyric row as the rules read it.
ShareLyricLine shareLyricLine(LyricRow row) =>
    ShareLyricLine(startMs: row.startMs, text: row.text, translation: row.translation);

/// The select-mode pick as shared: the lines in song order, where it skips part of the song.
ShareLyrics shareLyricsPick(List<LyricRow> lines, Set<int> passageStarts, {required bool showTranslation}) =>
    ShareLyrics(
      lines: [for (final l in lines) shareLyricLine(l)],
      passageStarts: passageStarts.toList()..sort(),
      showTranslation: showTranslation,
    );

/// About's labels from [strings] ([NowPlayingAboutLabels]' words).
ShareAboutLabels shareAboutLabels(NowPlayingStrings strings) => ShareAboutLabels(
  lyricist: strings.aboutField(YoinShareCards.canonLyricist),
  composer: strings.aboutField(YoinShareCards.canonComposer),
  arranger: strings.aboutArranger,
  producer: strings.aboutField(YoinShareCards.canonProducer),
  creationTime: strings.aboutField(YoinShareCards.canonCreationTime),
  creationLocation: strings.aboutField(YoinShareCards.canonCreationLocation),
  year: strings.spineYear,
  review: strings.aboutReview,
);

/// About's share content ([YoinShareCards.aboutPick]) from Now Playing's
/// sources: the lyrics' headline credits, the server's composer and year (no
/// other fact crosses over), the About rows, and the row long-pressed
/// ([focus]). Null when there is nothing to share.
ShareAbout? aboutSharePick({
  required List<SongAboutEntry> entries,
  required LyricCredits? credits,
  required AboutSourceFacts? facts,
  required NowPlayingStrings strings,
  SongAboutEntry? focus,
}) => YoinShareCards.aboutPick(
  [
    for (final e in entries)
      ShareAboutEntry(
        kind: e.kind,
        entryKey: e.entryKey,
        promptText: e.promptText,
        titleText: e.titleText,
        answerText: e.answerText,
      ),
  ],
  ShareAboutSources(
    lyricist: credits?.namesFor(LyricCreditRole.lyricist),
    composer: credits?.namesFor(LyricCreditRole.composer),
    arranger: credits?.namesFor(LyricCreditRole.arranger),
    producer: credits?.namesFor(LyricCreditRole.producer),
    serverComposer: facts?.composer,
    year: facts?.year,
  ),
  shareAboutLabels(strings),
  focus?.kind,
  focus?.entryKey,
);

/// A stored note as the rules read it.
ShareNote shareNote(SongNote note) =>
    ShareNote(noteId: note.id, content: note.content, positionMs: note.positionMs, createdAt: note.createdAt);

/// What a share is asked for from: the lyrics' pick (its own lines), About (a long-pressed row) or a note (its edit card).
@immutable
final class ShareRequest {
  const ShareRequest(this.tab, {this.lyrics, this.aboutFocus, this.noteId});

  final ShareTab tab;

  /// The select-mode pick; null shares the current line.
  final ShareLyrics? lyrics;
  final SongAboutEntry? aboutFocus;
  final String? noteId;
}

/// Everything shareable for the song on show: the [lyrics] pick (else the
/// current line), its About (focused on [aboutFocus] when one was
/// long-pressed) and one note ([noteId], else the one at the playhead, else
/// the newest).
ShareContent nowPlayingShareContent({
  required Track track,
  required String? coverUrl,
  required int durationMs,
  required List<LyricRow> lyricRows,
  required bool showTranslation,
  required int positionMs,
  required List<SongAboutEntry> aboutEntries,
  required LyricCredits? credits,
  required List<SongNote> notes,
  required NowPlayingStrings strings,
  required AiProvider aiProvider,
  ShareLyrics? lyrics,
  SongAboutEntry? aboutFocus,
  String? noteId,
}) => ShareContent(
  song: ShareSong(
    songId: track.id.toString(),
    title: track.title ?? '',
    artist: track.artist ?? '',
    coverArtUrl: coverUrl,
    albumName: track.album ?? '',
    durationMs: durationMs,
  ),
  lyrics:
      lyrics ?? YoinShareCards.currentLine([for (final r in lyricRows) shareLyricLine(r)], positionMs, showTranslation),
  about: aboutSharePick(
    entries: aboutEntries,
    credits: credits,
    facts: AboutSourceFacts.of(track),
    strings: strings,
    focus: aboutFocus,
  ),
  note: YoinShareCards.notePick([for (final n in notes) shareNote(n)], noteId, positionMs),
  aiProvider: aiProvider,
);

/// The note's date on an image: the full date ("2026年7月18日", "July 18, 2026") — a relative one would age.
String shareNoteDate(int epochMs, Locale locale) {
  final d = DateTime.fromMillisecondsSinceEpoch(epochMs);
  if (locale.languageCode == 'zh') return '${d.year}年${d.month}月${d.day}日';
  const months = [
    'January',
    'February',
    'March',
    'April',
    'May',
    'June',
    'July',
    'August',
    'September',
    'October',
    'November',
    'December',
  ];
  return '${months[d.month - 1]} ${d.day}, ${d.year}';
}

/// "1:05" for a moment in the song.
String shareTime(int ms) => YoinShareCards.time(ms);

// ---------------------------------------------------------------------------
// The layout each tab last used.
// ---------------------------------------------------------------------------

/// The layout each tab was last shared in, per device (Android
/// SharedPreferences `yoin_share_layouts`; out of the sync, like Library's
/// sort). Read once into memory ([load]) before the sheet opens.
class ShareLayoutStore {
  ShareLayoutStore(this._store);

  /// Process-local, for previews and tests.
  ShareLayoutStore.inMemory() : _store = InMemoryStore();

  /// The store's keys: `yoin_share_layouts.<tab>` (the Android preferences file's name, then its key).
  static const keyPrefix = 'yoin_share_layouts.';

  final KeyValueStore _store;
  final Map<ShareTab, ShareLayout?> _layouts = {};
  bool _loaded = false;

  /// Reads every tab's layout once; later calls answer at once.
  Future<void> load() async {
    if (_loaded) return;
    for (final tab in ShareTab.values) {
      try {
        // A layout a later build dropped, or one filed under another tab, reads as none chosen.
        _layouts[tab] ??= YoinShareCards.layoutForStoreValue(tab, await _store.read('$keyPrefix${tab.storeKey}'));
      } on Object {
        // An unreadable store remembers nothing.
      }
    }
    _loaded = true;
  }

  ShareLayout? layoutFor(ShareTab tab) => _layouts[tab];

  void setLayout(ShareTab tab, ShareLayout layout) {
    if (layout.tab != tab) return;
    _layouts[tab] = layout;
    _store.write('$keyPrefix${tab.storeKey}', layout.storeValue).catchError((Object _) {});
  }
}

/// The device's layout memory; tests override it with [ShareLayoutStore.inMemory]. A host without a store
/// (a harness) remembers for the session only.
final shareLayoutStoreProvider = Provider<ShareLayoutStore>((ref) {
  try {
    return ShareLayoutStore(ref.watch(keyValueStoreProvider));
  } on Object {
    return ShareLayoutStore.inMemory();
  }
});
