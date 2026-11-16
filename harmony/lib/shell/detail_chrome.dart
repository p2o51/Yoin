import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// One page row in the Play split's ▾ menu (Android DetailMenu rows and the
/// promotable BarExtraActions, which phones show as menu rows).
@immutable
final class DetailMenuAction {
  const DetailMenuAction({required this.icon, required this.label, required this.onTap, this.promotable = false});

  final IconData icon;
  final String label;

  /// Runs after the menu has closed.
  final VoidCallback onTap;

  /// Android's BarExtraAction (Go to artist, Share): where the bar has room —
  /// the centred bar's detail pose — it leaves ▾ for a round button beside
  /// Shuffle; everywhere else it stays a menu row.
  final bool promotable;
}

/// What a detail page puts in the window's one bar (its "detail pose":
/// the Play split button + the now-playing pill; adaptive-principles §2).
@immutable
final class DetailPlayActions {
  const DetailPlayActions({
    required this.onPlay,
    this.onShuffle,
    this.menuItems = const [],
    this.playContainer,
    this.playContent,
  });

  final VoidCallback onPlay;
  final VoidCallback? onShuffle;

  /// The ▾ menu's rows after Shuffle play, in order (Android: Play next, Add
  /// to queue, …, then the page's promotable actions such as Go to artist).
  final List<DetailMenuAction> menuItems;

  /// The page's accent for the Play button (cover colour), null = theme primary.
  final Color? playContainer;
  final Color? playContent;
}

/// Detail pages currently mounted, newest last; the bar shows the last one's
/// actions. Pages register through [DetailChromeRegistration].
class DetailChrome extends Notifier<List<(Object, DetailPlayActions)>> {
  @override
  List<(Object, DetailPlayActions)> build() => const [];

  void put(Object token, DetailPlayActions actions) {
    final i = state.indexWhere((e) => identical(e.$1, token));
    state = i < 0 ? [...state, (token, actions)] : ([...state]..[i] = (token, actions));
  }

  void remove(Object token) {
    // A page disposed with the whole ProviderScope (app teardown) removes
    // itself after the notifier is gone: nothing left to remove.
    if (!ref.mounted) return;
    state = [
      for (final e in state)
        if (!identical(e.$1, token)) e,
    ];
  }
}

final detailChromeProvider = NotifierProvider<DetailChrome, List<(Object, DetailPlayActions)>>(DetailChrome.new);

/// The actions the bar should show now, or null on the shell (nav pose).
final topDetailActionsProvider = Provider<DetailPlayActions?>((ref) => ref.watch(detailChromeProvider).lastOrNull?.$2);

/// Wrap a detail page's body: registers [actions] while mounted (null =
/// still loading, nothing to play yet) and removes them when the page goes.
class DetailChromeRegistration extends ConsumerStatefulWidget {
  const DetailChromeRegistration({super.key, required this.actions, required this.child});

  final DetailPlayActions? actions;
  final Widget child;

  @override
  ConsumerState<DetailChromeRegistration> createState() => _DetailChromeRegistrationState();
}

class _DetailChromeRegistrationState extends ConsumerState<DetailChromeRegistration> {
  final Object _token = Object();
  late final DetailChrome _chrome = ref.read(detailChromeProvider.notifier);

  @override
  void initState() {
    super.initState();
    _sync();
  }

  @override
  void didUpdateWidget(DetailChromeRegistration oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.actions != widget.actions) _sync();
  }

  void _sync() {
    final actions = widget.actions;
    // Provider writes can't happen during build; defer to after this frame.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (actions == null) {
        _chrome.remove(_token);
      } else {
        _chrome.put(_token, actions);
      }
    });
  }

  @override
  void dispose() {
    final chrome = _chrome;
    final token = _token;
    WidgetsBinding.instance.addPostFrameCallback((_) => chrome.remove(token));
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
