import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The system asked to show the player (the user tapped Yoin's card in
/// 播控中心 / the lock screen; Android: "tap on the system card opens Now
/// Playing"). The playback chain calls [request]; the Now Playing overlay
/// listens and opens. The value only counts requests.
class NowPlayingOpenRequests extends Notifier<int> {
  @override
  int build() => 0;

  void request() => state++;
}

final nowPlayingOpenRequestsProvider = NotifierProvider<NowPlayingOpenRequests, int>(NowPlayingOpenRequests.new);
