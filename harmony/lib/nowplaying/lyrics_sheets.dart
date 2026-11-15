import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/lyrics/lrc_parser.dart';
import '../data/lyrics/lyric_provider.dart';
import '../data/lyrics/lyrics_search.dart';
import '../data/lyrics/user_lyrics_store.dart';
import '../theme/motion.dart';
import '../ui/component/yoin_dialog.dart';
import 'lyrics_logic.dart';
import 'nowplaying_strings.dart';

/// The lyrics as the LRC editor opens them (toEditableLyricsText): timed
/// lines with their `[mm:ss.xx]`, untimed ones as they are.
String editableLyricsText(List<LyricRow> rows) =>
    rows.map((r) => r.startMs == null ? r.text : '${LrcParser.timestamp(r.startMs!)}${r.text}').join('\n');

/// The Search key's query (NowPlayingViewModel.openLyricsSearch): title and
/// artist, trimmed, joined by a space; empty when the song has neither.
String lyricsSearchSeed(String? title, String? artist) =>
    [title?.trim() ?? '', artist?.trim() ?? ''].where((s) => s.isNotEmpty).join(' ');

/// One provider's rows in the search surface.
@immutable
final class _Section {
  const _Section(this.providerName, this.matches, {this.loading = false});

  final String providerName;
  final List<SongMatch> matches;
  final bool loading;
}

/// Opens the lyrics search over the player (Android LyricsSearchSheet, the
/// full-screen search surface): seeded with "title artist" and searched at
/// once, every provider in its own section; a result applies its lyrics
/// through [onApply] (false keeps the surface open with the error).
Future<void> showLyricsSearch(
  BuildContext navigatorContext, {
  required String initialQuery,
  required Future<bool> Function(LyricsSearchHit hit) onApply,
}) => Navigator.of(navigatorContext).push(
  PageRouteBuilder<void>(
    opaque: false,
    transitionDuration: Duration.zero,
    reverseTransitionDuration: Duration.zero,
    pageBuilder: (_, _, _) => LyricsSearchSurface(initialQuery: initialQuery, onApply: onApply),
  ),
);

/// The full-screen search surface; it rises in on the default spatial spring
/// and fades on the effects spring, and leaves the same way.
class LyricsSearchSurface extends ConsumerStatefulWidget {
  const LyricsSearchSurface({super.key, required this.initialQuery, required this.onApply});

  final String initialQuery;
  final Future<bool> Function(LyricsSearchHit hit) onApply;

  @override
  ConsumerState<LyricsSearchSurface> createState() => _LyricsSearchSurfaceState();
}

class _LyricsSearchSurfaceState extends ConsumerState<LyricsSearchSurface> with TickerProviderStateMixin {
  late final TextEditingController _field = TextEditingController(text: widget.initialQuery);
  late final SpringValue _rise = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultSpatial);
  late final SpringValue _fade = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);
  List<_Section> _sections = const [];
  bool _loading = false;
  String? _error;
  String? _applying;
  int _generation = 0;
  bool _leaving = false;

  @override
  void initState() {
    super.initState();
    _field.addListener(() => setState(() {}));
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (MediaQuery.disableAnimationsOf(context)) {
        _rise.snapTo(1);
        _fade.snapTo(1);
      } else {
        _rise.animateTo(1);
        _fade.animateTo(1);
      }
    });
    _search(widget.initialQuery);
  }

  @override
  void dispose() {
    _field.dispose();
    _rise.dispose();
    _fade.dispose();
    super.dispose();
  }

  LyricsSearch get _service => ref.read(lyricsSearchProvider);

  Future<void> _search(String query) async {
    final q = query.trim();
    final generation = ++_generation;
    if (q.isEmpty) {
      setState(() {
        _loading = false;
        _sections = const [];
        _error = null;
      });
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
      _sections = [for (final name in _service.providerNames) _Section(name, const [], loading: true)];
    });
    try {
      final sections = await _service.search(q);
      if (!mounted || generation != _generation) return;
      setState(() {
        _loading = false;
        _sections = [for (final s in sections) _Section(s.providerName, s.matches)];
      });
    } catch (_) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _loading = false;
        _sections = const [];
        _error = NowPlayingStrings.of(context).msgCouldntSearch;
      });
    }
  }

  Future<void> _pick(String providerName, String songId) async {
    if (_applying != null) return;
    final strings = NowPlayingStrings.of(context);
    setState(() {
      _applying = '$providerName:$songId';
      _error = null;
    });
    final hit = await _service.fetch(providerName, songId);
    final applied = hit != null && await widget.onApply(hit);
    if (!mounted) return;
    if (applied) {
      _close();
    } else {
      setState(() {
        _applying = null;
        _error = strings.msgCouldntApply;
      });
    }
  }

  void _close() {
    if (_leaving) return;
    _leaving = true;
    final nav = Navigator.of(context);
    if (MediaQuery.disableAnimationsOf(context)) {
      nav.pop();
      return;
    }
    _rise.animateTo(0);
    _fade.animateTo(0);
    void done(AnimationStatus status) {
      if (_fade.value <= 0.02 && mounted && nav.canPop()) {
        _fade.animation.removeStatusListener(done);
        nav.pop();
      }
    }

    _fade.animation.addStatusListener(done);
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final strings = NowPlayingStrings.of(context);
    final padding = MediaQuery.paddingOf(context);
    final field = Padding(
      padding: EdgeInsets.fromLTRB(8, padding.top + 8, 8, 8),
      child: SizedBox(
        height: 56,
        child: Row(
          children: [
            IconButton(
              tooltip: strings.cdCloseLyricsSearch,
              onPressed: _close,
              icon: Icon(YoinSymbols.back, color: colors.onSurface),
            ),
            Expanded(
              child: TextField(
                key: const ValueKey('lyrics-search-field'),
                controller: _field,
                autofocus: false,
                textInputAction: TextInputAction.search,
                onSubmitted: _search,
                style: text.bodyLarge!.copyWith(color: colors.onSurface),
                decoration: InputDecoration(
                  border: InputBorder.none,
                  hintText: strings.lyricsSearchPlaceholder,
                  hintStyle: text.bodyLarge!.copyWith(color: colors.onSurfaceVariant),
                ),
              ),
            ),
            if (_loading)
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 15),
                child: SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2)),
              )
            else if (_field.text.isNotEmpty)
              IconButton(
                tooltip: strings.cdClear,
                onPressed: _field.clear,
                icon: Icon(YoinSymbols.close, color: colors.onSurfaceVariant),
              ),
          ],
        ),
      ),
    );
    final items = <Widget>[
      if (_error != null)
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: Text(_error!, style: text.bodySmall!.copyWith(color: colors.error)),
        ),
      if (_sections.isEmpty && !_loading && _field.text.trim().isNotEmpty && _error == null)
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 18),
          child: Text(strings.lyricsNoneFound, style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
        ),
      for (var i = 0; i < _sections.length; i++) ...[
        Padding(
          padding: const EdgeInsets.all(8),
          child: Text(
            strings.lyricsProviderLabel(_sections[i].providerName),
            style: text.labelMedium!.copyWith(color: colors.primary, fontWeight: FontWeight.w600),
          ),
        ),
        if (_sections[i].loading)
          _StatusRow(text: strings.lyricsSearching, loading: true)
        else if (_sections[i].matches.isEmpty)
          _StatusRow(text: strings.lyricsNoResults)
        else
          for (final match in _sections[i].matches)
            _ResultRow(
              title: match.title,
              artist: match.artist,
              applying: _applying == '${_sections[i].providerName}:${match.songId}',
              enabled: _applying == null,
              onTap: () => _pick(_sections[i].providerName, match.songId),
            ),
        if (i != _sections.length - 1) Container(height: 1, color: colors.outlineVariant.withValues(alpha: 0.4)),
      ],
    ];
    return AnimatedBuilder(
      animation: Listenable.merge([_rise.animation, _fade.animation]),
      builder: (context, child) => Opacity(
        opacity: _fade.value.clamp(0.0, 1.0),
        child: Transform.translate(
          offset: Offset(0, (1 - _rise.value) * MediaQuery.sizeOf(context).height / 12),
          child: child,
        ),
      ),
      child: Material(
        color: colors.surfaceContainerHigh,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            field,
            Expanded(
              child: ListView(padding: EdgeInsets.fromLTRB(16, 8, 16, 16 + padding.bottom), children: items),
            ),
          ],
        ),
      ),
    );
  }
}

class _StatusRow extends StatelessWidget {
  const _StatusRow({required this.text, this.loading = false});

  final String text;
  final bool loading;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
      child: Row(
        children: [
          if (loading) ...[
            const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2)),
            const SizedBox(width: 10),
          ],
          Flexible(
            child: Text(text, style: Theme.of(context).textTheme.bodyMedium!.copyWith(color: colors.onSurfaceVariant)),
          ),
        ],
      ),
    );
  }
}

class _ResultRow extends StatelessWidget {
  const _ResultRow({
    required this.title,
    required this.artist,
    required this.applying,
    required this.enabled,
    required this.onTap,
  });

  final String title;
  final String artist;
  final bool applying;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Material(
        type: MaterialType.transparency,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: enabled ? onTap : null,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: text.bodyLarge!.copyWith(color: colors.onSurface),
                      ),
                      Text(
                        artist,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: text.bodyMedium!.copyWith(color: colors.onSurfaceVariant),
                      ),
                    ],
                  ),
                ),
                if (applying)
                  const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
                else
                  Icon(YoinSymbols.chevronRight, color: colors.onSurfaceVariant),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// The LRC editor (Android LyricsApplyDialog): the lyrics as LRC, Apply once
/// it isn't blank. Resolves with the text to apply, or null.
Future<String?> showLyricsEditor(BuildContext context, {required String initialText}) => showYoinDialog<String>(
  context: context,
  builder: (context) => LyricsEditorDialog(initialText: initialText),
);

class LyricsEditorDialog extends StatefulWidget {
  const LyricsEditorDialog({super.key, required this.initialText});

  final String initialText;

  @override
  State<LyricsEditorDialog> createState() => _LyricsEditorDialogState();
}

class _LyricsEditorDialogState extends State<LyricsEditorDialog> {
  late final TextEditingController _draft = TextEditingController(text: widget.initialText);

  @override
  void initState() {
    super.initState();
    _draft.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _draft.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    return AlertDialog(
      title: Text(strings.lyricsApplyTitle),
      content: ConstrainedBox(
        constraints: const BoxConstraints(minHeight: 180, maxHeight: 360),
        child: TextField(
          key: const ValueKey('lyrics-editor-field'),
          controller: _draft,
          minLines: 8,
          maxLines: null,
          keyboardType: TextInputType.multiline,
          decoration: InputDecoration(hintText: strings.lyricsFieldPlaceholder, border: const OutlineInputBorder()),
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: Text(strings.lyricsCancel)),
        TextButton(
          onPressed: _draft.text.trim().isEmpty ? null : () => Navigator.of(context).pop(_draft.text),
          child: Text(strings.lyricsApply),
        ),
      ],
    );
  }
}

/// [UserLyrics] provider ids → the snackbar after an apply.
String lyricsAppliedMessage(NowPlayingStrings strings, String provider) =>
    provider == UserLyrics.manualProvider ? strings.msgLyricsApplied : strings.msgLyricsAppliedFrom(provider);
