import 'package:flutter/material.dart';
import '../../shell/bar_suppression.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../data/model/model.dart';
import '../../nowplaying/haptics.dart';
import '../../theme/tokens.dart';
import 'component_strings.dart';
import 'cover_art.dart';
import 'expressive_text_field.dart';
import 'yoin_dialog.dart';
import 'yoin_loading_indicator.dart';

/// Add to playlist (component/AddToPlaylistSheet.kt): "Create new playlist…"
/// (when [onCreateAndAdd] is given), then the playlists this account can
/// write. The sheet knows nothing about which songs: the callbacks close over
/// them. A pick slides the sheet away first, then the write runs.
Future<void> showAddToPlaylistSheet(
  BuildContext context, {
  required Future<List<Playlist>> Function() loadWritablePlaylists,
  required ValueChanged<Playlist> onAddToExisting,
  ValueChanged<String>? onCreateAndAdd,
}) => showModalBottomSheet<void>(
  context: context,
  useSafeArea: false,
  isScrollControlled: true,
  showDragHandle: true,
  backgroundColor: Theme.of(context).colorScheme.surfaceContainer,
  // The window's bar sits above the Navigator: a sheet sends it away.
  builder: (_) => ChromeBarSuppression(
    child: AddToPlaylistSheet(
      loadWritablePlaylists: loadWritablePlaylists,
      onAddToExisting: onAddToExisting,
      onCreateAndAdd: onCreateAndAdd,
    ),
  ),
);

class AddToPlaylistSheet extends StatefulWidget {
  const AddToPlaylistSheet({
    super.key,
    required this.loadWritablePlaylists,
    required this.onAddToExisting,
    this.onCreateAndAdd,
  });

  final Future<List<Playlist>> Function() loadWritablePlaylists;
  final ValueChanged<Playlist> onAddToExisting;
  final ValueChanged<String>? onCreateAndAdd;

  @override
  State<AddToPlaylistSheet> createState() => _AddToPlaylistSheetState();
}

class _AddToPlaylistSheetState extends State<AddToPlaylistSheet> {
  late final Future<List<Playlist>> _playlists = widget.loadWritablePlaylists();

  /// A sheet that is leaving takes no more taps.
  bool _leaving = false;

  void _leaveThen(VoidCallback action) {
    if (_leaving) return;
    _leaving = true;
    YoinHaptics.confirm();
    Navigator.of(context).pop();
    action();
  }

  Future<void> _create() async {
    if (_leaving) return;
    YoinHaptics.tick();
    final strings = ComponentStrings.of(context);
    final name = await showPlaylistNameDialog(
      context,
      title: strings.playlistNewTitle,
      label: strings.playlistNameLabel,
      confirmLabel: strings.playlistCreate,
      cancelLabel: strings.playlistCancel,
    );
    if (name == null || !mounted) return;
    _leaveThen(() => widget.onCreateAndAdd!(name));
  }

  @override
  Widget build(BuildContext context) {
    final strings = ComponentStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final navBottom = MediaQuery.paddingOf(context).bottom;
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 8),
          child: Text(strings.playlistSheetTitle, style: text.titleMedium!.copyWith(color: colors.onSurface)),
        ),
        Flexible(
          child: FutureBuilder<List<Playlist>>(
            future: _playlists,
            builder: (context, snapshot) {
              final playlists = snapshot.data ?? const <Playlist>[];
              final loading = snapshot.connectionState != ConnectionState.done;
              return ListView(
                shrinkWrap: true,
                padding: EdgeInsets.only(bottom: 16 + navBottom),
                children: [
                  if (widget.onCreateAndAdd != null)
                    _Row(
                      leading: Container(
                        width: SheetTokens.playlistThumb,
                        height: SheetTokens.playlistThumb,
                        decoration: BoxDecoration(
                          color: colors.primaryContainer,
                          borderRadius: BorderRadius.circular(ShapeTokens.thumb),
                        ),
                        child: Icon(YoinSymbols.add, color: colors.onPrimaryContainer),
                      ),
                      title: Text(strings.playlistCreateRow, style: text.bodyLarge!.copyWith(color: colors.primary)),
                      onTap: _create,
                    ),
                  if (loading)
                    const Padding(
                      padding: EdgeInsets.all(24),
                      child: Center(child: YoinLoadingIndicator()),
                    )
                  else
                    for (final p in playlists)
                      _Row(
                        key: ValueKey(p.id),
                        leading: p.coverArt == null
                            ? Container(
                                width: SheetTokens.playlistThumb,
                                height: SheetTokens.playlistThumb,
                                decoration: BoxDecoration(
                                  color: colors.surfaceContainerHighest,
                                  borderRadius: BorderRadius.circular(ShapeTokens.thumb),
                                ),
                                child: Icon(YoinSymbols.playlist, color: colors.onSurfaceVariant),
                              )
                            : CoverArt(cover: p.coverArt, size: SheetTokens.playlistThumb, radius: ShapeTokens.thumb),
                        title: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              p.name,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: text.bodyMedium!.copyWith(color: colors.onSurface),
                            ),
                            if (p.songCount case final n?)
                              Text(
                                strings.playlistSongCount(n),
                                style: text.bodySmall!.copyWith(color: colors.onSurfaceVariant),
                              ),
                          ],
                        ),
                        onTap: () => _leaveThen(() => widget.onAddToExisting(p)),
                      ),
                ],
              );
            },
          ),
        ),
      ],
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({super.key, required this.leading, required this.title, required this.onTap});
  final Widget leading;
  final Widget title;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => InkWell(
    onTap: onTap,
    child: Padding(
      padding: const EdgeInsets.symmetric(
        horizontal: SheetTokens.playlistRowPaddingH,
        vertical: SheetTokens.playlistRowPaddingV,
      ),
      child: Row(
        children: [
          leading,
          const SizedBox(width: SheetTokens.playlistRowGap),
          Expanded(child: title),
        ],
      ),
    ),
  );
}

/// A playlist's name (New playlist, Rename): the confirm button is live once
/// the name isn't blank. Resolves with the trimmed name, or null.
Future<String?> showPlaylistNameDialog(
  BuildContext context, {
  required String title,
  required String label,
  required String confirmLabel,
  required String cancelLabel,
  String initial = '',
}) => showYoinDialog<String>(
  context: context,
  builder: (context) =>
      _NameDialog(title: title, label: label, confirmLabel: confirmLabel, cancelLabel: cancelLabel, initial: initial),
);

class _NameDialog extends StatefulWidget {
  const _NameDialog({
    required this.title,
    required this.label,
    required this.confirmLabel,
    required this.cancelLabel,
    required this.initial,
  });

  final String title;
  final String label;
  final String confirmLabel;
  final String cancelLabel;
  final String initial;

  @override
  State<_NameDialog> createState() => _NameDialogState();
}

class _NameDialogState extends State<_NameDialog> {
  late final _controller = TextEditingController(text: widget.initial);

  @override
  void initState() {
    super.initState();
    _controller.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _confirm() {
    final name = _controller.text.trim();
    if (name.isEmpty) return;
    Navigator.of(context).pop(name);
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.title),
    content: ExpressiveTextField(
      controller: _controller,
      label: widget.label,
      placeholder: '',
      textInputAction: TextInputAction.done,
      onSubmitted: (_) => _confirm(),
    ),
    actions: [
      TextButton(onPressed: () => Navigator.of(context).pop(), child: Text(widget.cancelLabel)),
      TextButton(onPressed: _controller.text.trim().isEmpty ? null : _confirm, child: Text(widget.confirmLabel)),
    ],
  );
}
