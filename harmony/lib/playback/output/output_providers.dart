/// The output router in the dependency graph. main() wires the platform's
/// (`platformOverrides`, which also makes it the playback engine); tests and
/// hosts without one leave it null, and the Devices sheet then lists this
/// device alone.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'playback_router.dart';

final playbackRouterProvider = Provider<PlaybackRouter?>((ref) => null);
