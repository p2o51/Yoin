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

/// Whether a bar-less page wants the bar away, from its route's two
/// animations: not while it is leaving ([animation] reversing), and not while
/// another page covers it ([secondaryAnimation] running forward or done). A
/// page route only drives [secondaryAnimation] for a page pushed over it, so
/// a menu or sheet above it keeps the bar away.
bool barSuppressedFor({required AnimationStatus animation, required AnimationStatus secondaryAnimation}) {
  if (animation == AnimationStatus.reverse) return false;
  return secondaryAnimation == AnimationStatus.dismissed || secondaryAnimation == AnimationStatus.reverse;
}

/// Wrap a bar-less pushed page: the bar leaves while it is the top page.
/// A detail page pushed over it (an album opened from search) has the bar,
/// so the bar comes back as that page comes in and leaves again as it goes.
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
  Animation<double>? _secondaryAnimation;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final route = ModalRoute.of(context);
    final animation = route?.animation;
    if (!identical(animation, _routeAnimation)) {
      _routeAnimation?.removeStatusListener(_onRouteStatus);
      _routeAnimation = animation?..addStatusListener(_onRouteStatus);
    }
    final secondary = route?.secondaryAnimation;
    if (!identical(secondary, _secondaryAnimation)) {
      _secondaryAnimation?.removeStatusListener(_onRouteStatus);
      _secondaryAnimation = secondary?..addStatusListener(_onRouteStatus);
    }
    _sync();
  }

  /// The bar comes back as the page starts to leave (or to be covered), not after.
  void _onRouteStatus(AnimationStatus _) => _sync();

  void _sync() {
    final away = barSuppressedFor(
      animation: _routeAnimation?.status ?? AnimationStatus.completed,
      secondaryAnimation: _secondaryAnimation?.status ?? AnimationStatus.dismissed,
    );
    // Provider writes can't happen during build; defer to after this frame.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      if (away) {
        _suppression.add(_token);
      } else {
        _suppression.remove(_token);
      }
    });
  }

  @override
  void dispose() {
    _routeAnimation?.removeStatusListener(_onRouteStatus);
    _secondaryAnimation?.removeStatusListener(_onRouteStatus);
    final suppression = _suppression;
    final token = _token;
    WidgetsBinding.instance.addPostFrameCallback((_) => suppression.remove(token));
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
