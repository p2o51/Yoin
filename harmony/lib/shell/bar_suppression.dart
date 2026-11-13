import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Pushed pages that are not detail pages (account setup, full-screen
/// search) and have no place for the window's one bar: on Android they are
/// Activities of their own, with no bar. While any is mounted the bar leaves
/// (the chrome springs it out), so its Home / Library halves can't flip the
/// shell section under a page that stays up.
class BarSuppression extends Notifier<Set<Object>> {
  @override
  Set<Object> build() => const {};

  void add(Object token) {
    if (state.contains(token)) return;
    state = {...state, token};
  }

  void remove(Object token) {
    // Removed after the whole ProviderScope went (app teardown): nothing to do.
    if (!ref.mounted || !state.contains(token)) return;
    state = {...state}..remove(token);
  }
}

final barSuppressionProvider = NotifierProvider<BarSuppression, Set<Object>>(BarSuppression.new);

/// Whether the bar should be away because a bar-less page is up.
final barSuppressedProvider = Provider<bool>((ref) => ref.watch(barSuppressionProvider).isNotEmpty);

/// Wrap a bar-less pushed page: the bar leaves while it is mounted.
class ChromeBarSuppression extends ConsumerStatefulWidget {
  const ChromeBarSuppression({super.key, required this.child});

  final Widget child;

  @override
  ConsumerState<ChromeBarSuppression> createState() => _ChromeBarSuppressionState();
}

class _ChromeBarSuppressionState extends ConsumerState<ChromeBarSuppression> {
  final Object _token = Object();
  late final BarSuppression _suppression = ref.read(barSuppressionProvider.notifier);
  Animation<double>? _routeAnimation;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final animation = ModalRoute.of(context)?.animation;
    if (!identical(animation, _routeAnimation)) {
      _routeAnimation?.removeStatusListener(_onRouteStatus);
      _routeAnimation = animation?..addStatusListener(_onRouteStatus);
    }
    _sync(leaving: animation?.status == AnimationStatus.reverse);
  }

  /// The bar comes back as the page starts to leave, not after it has gone.
  void _onRouteStatus(AnimationStatus status) => _sync(leaving: status == AnimationStatus.reverse);

  void _sync({required bool leaving}) {
    // Provider writes can't happen during build; defer to after this frame.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (leaving) {
        _suppression.remove(_token);
      } else {
        _suppression.add(_token);
      }
    });
  }

  @override
  void dispose() {
    _routeAnimation?.removeStatusListener(_onRouteStatus);
    final suppression = _suppression;
    final token = _token;
    WidgetsBinding.instance.addPostFrameCallback((_) => suppression.remove(token));
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
