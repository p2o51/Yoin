part of 'album_scrapbook_page.dart';

/*
 * 被提及 (Android AlbumScrapbookNotes.kt BacklinksSection, prototype scenario 4): the scrapbook's last block.
 * Every song note, album note and review of the account that mentions this album or one of its songs, one
 * clipping each, newest first: the source's cover and name, "Note · 1:23 · 3 days ago", a one-line snippet with
 * the names that point here under a highlighter. A tap opens the scrapbook the writing is on (a song note: on
 * its song). Nothing mentions it: no block at all.
 */

class _Backlinks extends ConsumerStatefulWidget {
  const _Backlinks({required this.album});

  final Album album;

  @override
  ConsumerState<_Backlinks> createState() => _BacklinksState();
}

class _BacklinksState extends ConsumerState<_Backlinks> {
  List<NoteBacklink> _links = const [];
  StreamSubscription<MemoryStoreChange>? _changes;
  MemoryStore? _store;
  int _generation = 0;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  @override
  void didUpdateWidget(_Backlinks oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.album.id != widget.album.id || oldWidget.album.tracks.length != widget.album.tracks.length) {
      unawaited(_load());
    }
  }

  @override
  void dispose() {
    _changes?.cancel();
    super.dispose();
  }

  BacklinkAlbum get _target => BacklinkAlbum(
    widget.album.id,
    title: widget.album.name,
    artist: widget.album.artist,
    tracks: [
      for (final t in widget.album.tracks)
        BacklinkTrack(t.id, title: t.title ?? '', artist: t.artist),
    ],
  );

  Future<void> _load() async {
    final generation = ++_generation;
    final MemoryStore? store;
    try {
      store = await ref.read(memoryStoreProvider.future);
    } on Object {
      return;
    }
    if (!mounted || generation != _generation || store == null) return;
    if (store != _store) {
      await _changes?.cancel();
      _store = store;
      // A note, album note or review written anywhere re-reads the block.
      _changes = store.changes.listen((change) {
        if (change != MemoryStoreChange.titles) unawaited(_load());
      });
    }
    final writings = await backlinkWritings(
      store,
      links: TrackAlbumLinkStore.shared(ref.read(keyValueStoreProvider), store.profileId),
    );
    if (!mounted || generation != _generation) return;
    final links = findBacklinks(writings, _target);
    setState(() => _links = links);
  }

  @override
  Widget build(BuildContext context) {
    // Rebuilt for the account in use.
    ref.listen(memoryStoreProvider, (_, _) => unawaited(_load()));
    final colors = _ScrapScope.of(context).colors;
    final strings = NotesStrings.of(context);
    final theme = Theme.of(context);
    final links = _links;
    return SpringHeight(
      heightKey: links.length,
      child: links.isEmpty
          ? const SizedBox(width: double.infinity)
          : Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    SizedBox.square(
                      dimension: NoteBacklinkTokens.badge,
                      child: DecoratedBox(
                        decoration: ShapeDecoration(color: colors.ink, shape: PolygonBorder(MaterialShapes.cookie12Sided)),
                        child: Icon(YoinSymbols.at, size: NoteBacklinkTokens.badgeGlyph, color: colors.onInk),
                      ),
                    ),
                    const SizedBox(width: NoteBacklinkTokens.titleGap),
                    Text(
                      strings.backlinksTitle,
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w600,
                        color: theme.colorScheme.onSurface,
                      ),
                    ),
                    const SizedBox(width: NoteBacklinkTokens.titleGap),
                    Text(
                      strings.backlinksCount(links.length),
                      style: theme.textTheme.labelLarge?.copyWith(color: colors.muted),
                    ),
                  ],
                ),
                for (final link in links) ...[
                  const SizedBox(height: NoteBacklinkTokens.clipGap),
                  _BacklinkClipping(link: link, albumSeed: widget.album.id.rawId),
                ],
              ],
            ),
    );
  }
}

class _BacklinkClipping extends ConsumerWidget {
  const _BacklinkClipping({required this.link, required this.albumSeed});

  final NoteBacklink link;
  final String albumSeed;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = _ScrapScope.of(context).colors;
    final strings = NotesStrings.of(context);
    final theme = Theme.of(context);
    final mentions = ref.read(noteMentionsProvider.notifier);
    final source = link.source;
    final name = switch (source) {
      BacklinkSongNote(:final trackTitle) => trackTitle,
      BacklinkAlbumNote(:final albumName) => albumName,
      BacklinkReview(:final albumName) => albumName ?? '',
    };
    final kind = switch (source) {
      BacklinkSongNote() => strings.backlinkKindNote,
      BacklinkAlbumNote() => strings.backlinkKindAlbumNote,
      BacklinkReview() => strings.backlinkKindReview,
    };
    final positionMs = source is BacklinkSongNote ? source.positionMs : null;
    final moment = positionMs == null ? null : formatNotePosition(positionMs);
    final date = formatNoteDate(link.at, nowMs: DateTime.now().millisecondsSinceEpoch, zh: strings.zh);
    final meta = [kind, ?moment, date].join(' · ');
    final tilt =
        ScrapbookRules.cardTilts[scrapHash('$albumSeed/bl/${source.sortKey}') % ScrapbookRules.cardTilts.length] *
        NoteBacklinkTokens.tiltShare;
    void open() {
      YoinHaptics.tick();
      mentions.openSource(context, source);
    }

    return _ScrapLayer(
      tilt: tilt,
      depth: 0.3,
      child: _scrapFade(
        Semantics(
          button: true,
          label: strings.backlinkCd(name, meta, link.snippet),
          onTap: open,
          excludeSemantics: true,
          child: Material(
            color: colors.receipt,
            shape: RoundedSuperellipseBorder(borderRadius: BorderRadius.circular(ShapeTokens.card)),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: open,
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: NoteBacklinkTokens.clipPadH,
                  vertical: NoteBacklinkTokens.clipPadV,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        SizedBox.square(
                          dimension: NoteBacklinkTokens.clipArt,
                          child: MediaArtwork(
                            url: mentions.coverUrl(source.coverKey),
                            fallbackIcon: source is BacklinkSongNote ? YoinSymbols.musicNote : YoinSymbols.album,
                            radius: ShapeTokens.thumb,
                          ),
                        ),
                        const SizedBox(width: NoteBacklinkTokens.clipRowGap),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                name,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: theme.textTheme.titleSmall?.copyWith(color: colors.onReceipt),
                              ),
                              MetaLine(
                                groups: [MetaKind(kind), if (moment != null) MetaPlain(moment), MetaMuted(date)],
                                style: theme.textTheme.labelMedium,
                                color: colors.onReceipt,
                              ),
                            ],
                          ),
                        ),
                        Icon(YoinSymbols.chevronRight, size: NoteBacklinkTokens.chevron, color: colors.muted),
                      ],
                    ),
                    const SizedBox(height: NoteBacklinkTokens.clipLineGap),
                    Text.rich(
                      _highlightMentions(
                        link.snippet,
                        link.marks,
                        colors.ask.withValues(alpha: NoteBacklinkTokens.markerAlpha),
                      ),
                      maxLines: NoteBacklinkTokens.snippetLines,
                      overflow: TextOverflow.ellipsis,
                      style: _userText(context)?.copyWith(color: colors.onReceipt),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// [snippet] with [marks] (where the mentions that point here landed) as by a
/// highlighter — only those words, never another that reads the same.
TextSpan _highlightMentions(String snippet, List<(int, int)> marks, Color marker) {
  final spans = <InlineSpan>[];
  var at = 0;
  for (final (start, end) in marks) {
    final s = start.clamp(at, snippet.length);
    final e = end.clamp(s, snippet.length);
    if (e <= s) continue;
    if (s > at) spans.add(TextSpan(text: snippet.substring(at, s)));
    spans.add(
      TextSpan(
        text: snippet.substring(s, e),
        style: TextStyle(backgroundColor: marker, fontWeight: FontWeight.w600),
      ),
    );
    at = e;
  }
  if (at < snippet.length) spans.add(TextSpan(text: snippet.substring(at)));
  return TextSpan(children: spans);
}
