import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../nowplaying/haptics.dart';
import '../playback/playback_controller.dart';
import '../shell/app_messages.dart';
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/add_to_playlist_sheet.dart';
import '../ui/component/component_strings.dart';
import '../ui/component/song_list_item.dart' show HorizontalSpringReveal;
import 'playlist_writes.dart';

/// Library's "+ New playlist" (LibraryScreen.kt PlaylistsTabContent): an
/// extended FAB that folds to its symbol while the list scrolls down and
/// unfolds when it scrolls up (spatial spring). It asks for a name, makes the
/// playlist and says how it went. It rises over the window's snackbar while
/// one shows (Android's Scaffold lifts its FAB the same way), on the default
/// spatial spring.
class NewPlaylistFab extends ConsumerStatefulWidget {
  const NewPlaylistFab({super.key, required this.controller});

  /// The playlists list's scroll: its direction folds the label.
  final ScrollController controller;

  @override
  ConsumerState<NewPlaylistFab> createState() => _NewPlaylistFabState();
}

class _NewPlaylistFabState extends ConsumerState<NewPlaylistFab> with SingleTickerProviderStateMixin {
  bool _expanded = true;

  /// How far up the snackbar pushes the FAB (logical px, ≥ 0).
  late final SpringValue _lift = SpringValue(
    vsync: this,
    initial: ref.read(snackbarLiftProvider),
    spring: YoinSprings.defaultSpatial,
  );

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onScroll);
  }

  @override
  void didUpdateWidget(NewPlaylistFab old) {
    super.didUpdateWidget(old);
    if (old.controller != widget.controller) {
      old.controller.removeListener(_onScroll);
      widget.controller.addListener(_onScroll);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onScroll);
    _lift.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (!widget.controller.hasClients) return;
    final expanded = switch (widget.controller.position.userScrollDirection) {
      ScrollDirection.reverse => false,
      ScrollDirection.forward => true,
      ScrollDirection.idle => _expanded,
    };
    if (expanded != _expanded) setState(() => _expanded = expanded);
  }

  Future<void> _create() async {
    YoinHaptics.tick();
    final strings = ComponentStrings.of(context);
    final writes = ref.read(playlistWritesProvider);
    final name = await showPlaylistNameDialog(
      context,
      title: strings.playlistNewTitle, // library_playlist_dialog_title
      label: strings.playlistNameLabel, // library_playlist_name_label
      confirmLabel: strings.playlistCreate, // library_playlist_create
      cancelLabel: strings.playlistCancel, // library_playlist_cancel
    );
    if (name == null) return;
    YoinHaptics.confirm();
    String text;
    try {
      await writes.create(name);
      text = strings.libraryPlaylistCreated(name);
    } catch (_) {
      text = strings.libraryPlaylistCreateFailed(name);
    }
    ref.read(playbackControllerProvider.notifier).showMessage(text);
  }

  @override
  Widget build(BuildContext context) {
    final strings = ComponentStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final lift = ref.watch(snackbarLiftProvider);
    if (MediaQuery.disableAnimationsOf(context)) {
      _lift.snapTo(lift);
    } else {
      _lift.animateTo(lift);
    }
    return AnimatedBuilder(
      animation: _lift.animation,
      builder: (context, child) => Transform.translate(offset: Offset(0, -_lift.value), child: child),
      child: _fab(strings, colors),
    );
  }

  Widget _fab(ComponentStrings strings, ColorScheme colors) {
    return Semantics(
      button: true,
      label: strings.libraryPlaylistFab,
      excludeSemantics: true,
      child: Material(
        color: colors.primaryContainer,
        elevation: PlaylistFabTokens.elevation,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(PlaylistFabTokens.corner)),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: _create,
          child: SizedBox(
            height: PlaylistFabTokens.height,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: PlaylistFabTokens.paddingH),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(YoinSymbols.add, color: colors.onPrimaryContainer),
                  HorizontalSpringReveal(
                    visible: _expanded,
                    child: Padding(
                      padding: const EdgeInsetsDirectional.only(start: PlaylistFabTokens.iconGap),
                      child: Text(
                        strings.libraryPlaylistFab,
                        maxLines: 1,
                        style: Theme.of(context).textTheme.labelLarge!.copyWith(color: colors.onPrimaryContainer),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
