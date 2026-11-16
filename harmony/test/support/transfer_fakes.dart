import 'dart:async';

import 'package:yoin/platform/background/background_transfer.dart';

/// The platform's one task: what it was asked to show, and when it was let go.
class RecordingTransferHost implements BackgroundTransferHost {
  final calls = <String>[];
  bool refuse = false;
  bool allowed = false;
  final _ended = StreamController<bool>.broadcast();

  void end({bool byUser = true}) => _ended.add(byUser);

  @override
  Future<bool> hold(TransferNotice notice) async {
    if (refuse) return false;
    calls.add('hold ${notice.title}');
    return true;
  }

  @override
  Future<void> release() async => calls.add('release');

  @override
  Stream<bool> get ended => _ended.stream;

  @override
  Future<bool> notificationsAllowed({bool ask = false}) async {
    calls.add(ask ? 'ask' : 'check');
    return allowed;
  }

  @override
  Future<void> notify(int id, TransferNotice notice) async => calls.add('notify $id ${notice.title}');

  @override
  Future<void> cancelNotice(int id) async => calls.add('cancel $id');
}
