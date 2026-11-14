/// The playing song's notes, each pinned to the second it was written at
/// (Android song_notes.positionMs; the NP note page). HarmonyOS's Now
/// Playing has no Lyrics / About / Note pages yet, so this sheet stands in
/// for the note page: the song's notes in timeline order (the line the
/// playhead is inside lit; a tap plays from it; a long press deletes, with
/// Undo), and the write bar, whose note is pinned to the moment the sheet
/// opened. Memories reads the same rows.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../app/providers.dart';
import '../data/memory/memory_store.dart';
import '../data/model/model.dart';
import '../nowplaying/haptics.dart';
import '../playback/playback_controller.dart';
import '../playback/playhead.dart';
import '../theme/theme.dart';
import 'copy/memory_voice.dart';

/// NP note strings, from Android strings_nowplaying.xml (key in the comment).
class _NoteStrings {
  const _NoteStrings(this.zh);
  final bool zh;
  String get title => zh ? '笔记' : 'Note'; // np_tab_note
  String get write => zh ? '写笔记' : 'Write'; // np_note_tap_to_write
  String playFrom(String at) => zh ? '从 $at 播放' : 'Play from $at'; // np_cd_note_play_from
  String get deleted => zh ? '已删除' : 'Deleted'; // np_note_deleted
  String get undo => zh ? '撤销' : 'Undo'; // np_note_undo
  String get empty => zh ? '暂无笔记' : 'No notes yet'; // np_note_empty
  String get save => zh ? '保存' : 'Save'; // mem_review_save
}

Future<void> showSongNotesSheet(BuildContext context, Track track) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    showDragHandle: true,
    backgroundColor: Theme.of(context).colorScheme.surfaceContainerLow,
    builder: (_) => SongNotesSheet(track: track),
  );
}

class SongNotesSheet extends ConsumerStatefulWidget {
  const SongNotesSheet({super.key, required this.track});

  final Track track;

  @override
  ConsumerState<SongNotesSheet> createState() => _SongNotesSheetState();
}

class _SongNotesSheetState extends ConsumerState<SongNotesSheet> {
  final TextEditingController _text = TextEditingController();
  List<SongNote> _notes = const [];
  StreamSubscription<MemoryStoreChange>? _changes;

  /// The second the new note is pinned to: where the playhead was when the sheet opened.
  late final int _moment = ref.read(playheadProvider).positionAt(ref.read(playbackClockProvider).wallMs());

  @override
  void initState() {
    super.initState();
    _text.addListener(() => setState(() {}));
    _load();
  }

  Future<void> _load() async {
    final store = await ref.read(memoryStoreProvider.future);
    if (store == null || !mounted) return;
    _changes ??= store.changes.listen((c) {
      if (c == MemoryStoreChange.songNotes) _load();
    });
    final notes = await store.notesForTracks([widget.track.id]);
    notes.sort((a, b) {
      final c = (a.positionMs ?? (1 << 62)).compareTo(b.positionMs ?? (1 << 62));
      return c != 0 ? c : a.createdAt.compareTo(b.createdAt);
    });
    if (mounted) setState(() => _notes = notes);
  }

  Future<void> _save() async {
    final words = _text.text.trim();
    if (words.isEmpty) return;
    final store = await ref.read(memoryStoreProvider.future);
    if (store == null) return;
    final now = ref.read(playbackClockProvider).wallMs();
    final t = widget.track;
    await store.putSongNote(
      SongNote(
        id: '${now.toRadixString(36)}-${t.id.rawId.hashCode.toRadixString(36)}',
        trackId: t.id,
        albumId: t.albumId,
        content: words,
        createdAt: now,
        updatedAt: now,
        title: t.title ?? '',
        artist: t.artist ?? '',
        positionMs: _moment,
      ),
    );
    YoinHaptics.confirm();
    _text.clear();
  }

  Future<void> _delete(SongNote note, _NoteStrings strings) async {
    final store = await ref.read(memoryStoreProvider.future);
    if (store == null) return;
    await store.deleteSongNote(note.id);
    if (!mounted) return;
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      SnackBar(
        content: Text(strings.deleted),
        action: SnackBarAction(label: strings.undo, onPressed: () => store.putSongNote(note)),
      ),
    );
  }

  void _playFrom(SongNote note) {
    final at = note.positionMs;
    if (at == null) return;
    YoinHaptics.click();
    ref.read(playbackControllerProvider.notifier).seekTo(at);
  }

  @override
  void dispose() {
    _changes?.cancel();
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final strings = _NoteStrings(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');
    final playing = ref.watch(playheadProvider);
    final pos = playing.positionMs;
    String? lit;
    var best = -1;
    for (final n in _notes) {
      final at = n.positionMs;
      if (at != null && at <= pos && at > best) {
        best = at;
        lit = n.id;
      }
    }
    final stampStyle = gsf(12.5, weight: 500, height: 25.6 / 12.5, letterSpacing: 0.1)
        .copyWith(fontFeatures: const [FontFeature.tabularFigures()]);
    final noteStyle = gsf(15.5, weight: 400, height: 25.6 / 15.5, letterSpacing: 0);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
            child: Semantics(
              header: true,
              child: Text(
                widget.track.title ?? strings.title,
                style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ),
          Flexible(
            child: _notes.isEmpty
                ? Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 16),
                    child: Text(strings.empty, style: noteStyle.copyWith(color: colors.onSurfaceVariant)),
                  )
                : ListView(
                    shrinkWrap: true,
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    children: [
                      for (final n in _notes)
                        Semantics(
                          button: n.positionMs != null,
                          onTapHint: n.positionMs == null ? null : strings.playFrom(MemoryExcerpt.anchorTime(n.positionMs!)),
                          child: InkWell(
                            borderRadius: BorderRadius.circular(12),
                            onTap: n.positionMs == null ? null : () => _playFrom(n),
                            onLongPress: () {
                              YoinHaptics.longPress();
                              _delete(n, strings);
                            },
                            child: Padding(
                              padding: const EdgeInsets.fromLTRB(12, 8, 12, 10),
                              child: Row(
                                crossAxisAlignment: CrossAxisAlignment.baseline,
                                textBaseline: TextBaseline.alphabetic,
                                children: [
                                  SizedBox(
                                    width: 44,
                                    child: Text(
                                      n.positionMs == null ? '' : MemoryExcerpt.anchorTime(n.positionMs!),
                                      style: stampStyle.copyWith(color: n.id == lit ? colors.primary : colors.onSurfaceVariant),
                                    ),
                                  ),
                                  Expanded(
                                    child: Text(
                                      n.content,
                                      style: noteStyle.copyWith(color: n.id == lit ? colors.primary : colors.onSurface),
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                    ],
                  ),
          ),
          // The write bar: the moment it pins to, the words, Save.
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
            child: Container(
              padding: const EdgeInsets.fromLTRB(16, 6, 6, 6),
              decoration: BoxDecoration(color: colors.surfaceContainerHigh, borderRadius: BorderRadius.circular(28)),
              child: Row(
                children: [
                  Text(MemoryExcerpt.anchorTime(_moment), style: stampStyle.copyWith(color: colors.primary, height: 1)),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextField(
                      controller: _text,
                      maxLines: 4,
                      minLines: 1,
                      style: noteStyle.copyWith(color: colors.onSurface, height: 1.4),
                      decoration: InputDecoration.collapsed(
                        hintText: strings.write,
                        hintStyle: noteStyle.copyWith(color: colors.onSurfaceVariant, height: 1.4),
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: strings.save,
                    onPressed: _text.text.trim().isEmpty ? null : _save,
                    icon: const Icon(YoinSymbols.check),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
