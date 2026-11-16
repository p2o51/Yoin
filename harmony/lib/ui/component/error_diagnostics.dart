/// What a tester pastes back when a page fails, since a release build logs
/// nothing (Android ui/component/ErrorDiagnostics.kt): the build, the
/// system, and the failure — for an Apple Music refusal its status and
/// Apple's code; for a Subsonic one its error code; for no answer at all
/// which kind it was and what the transport threw. Never a raw message (a
/// server's or an exception's can quote a URL, a host, a name, a token or a
/// salt), never an id from the user's library.
library;

import 'dart:io' show Platform;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../data/applemusic/apple_music_api.dart';
import '../../data/source/music_source.dart';
import '../../nowplaying/haptics.dart';
import '../../settings/licenses.dart' show yoinVersionCode, yoinVersionName;
import 'error_strings.dart';
import 'spring_cross_fade.dart';

/// The report for [error]. [now] and [device] are for tests.
String diagnosticReport(Object error, {DateTime? now, String? device}) {
  final lines = <String>['Yoin $yoinVersionName ($yoinVersionCode)', device ?? deviceLine(), diagnosticLine(error)];
  final cause = switch (error) {
    // Apple's failure is the line itself; another cause is named by its type.
    SourceException(cause: AppleMusicApiException()) => null,
    SourceException(:final cause?) => cause,
    _ => null,
  };
  if (cause != null && !identical(cause, error)) lines.add('cause ${_typeName(cause)}');
  lines.add(_instant(now ?? DateTime.now()));
  return lines.join('\n');
}

/// The system line: HarmonyOS (or Windows) and its version.
String deviceLine() {
  final os = switch (Platform.operatingSystem) {
    'ohos' => 'HarmonyOS',
    'windows' => 'Windows',
    final other => other,
  };
  return '$os ${Platform.operatingSystemVersion}'.trim();
}

/// The fixed messages Yoin's own Subsonic transport writes (safe to quote).
final _httpStatus = RegExp(r'^HTTP \d{3}$');
const _fixedServerMessages = {'Not a Subsonic response'};

/// One line naming [error] without quoting it.
String diagnosticLine(Object error) => switch (error) {
  AppleMusicApiException() => [
    'Apple Music ${error.failure.name}',
    if (error.status != null) 'HTTP ${error.status}',
    if (error.appleCode != null) 'Apple ${error.appleCode}',
    if (error.networkKind != null) 'network ${error.networkKind!.name}',
  ].join(', '),
  SourceException(cause: final AppleMusicApiException apple) => diagnosticLine(apple),
  SourceNetworkException(:final kind) => 'Network ${kind.name}',
  SourceAuthException(:final code) => 'Subsonic auth${code == null ? '' : ' $code'}',
  SourceServerException(:final code?) => 'Subsonic $code',
  SourceServerException(:final message) when _httpStatus.hasMatch(message) || _fixedServerMessages.contains(message) =>
    message,
  SourceServerException() => 'Server error',
  _ => _typeName(error),
};

String _typeName(Object o) {
  final name = o.runtimeType.toString();
  return name.isEmpty ? 'Error' : name;
}

/// Seconds-truncated UTC, as Android's Instant prints it (2026-11-17T08:30:05Z).
String _instant(DateTime time) {
  final t = time.toUtc();
  String two(int v) => v.toString().padLeft(2, '0');
  return '${t.year.toString().padLeft(4, '0')}-${two(t.month)}-${two(t.day)}'
      'T${two(t.hour)}:${two(t.minute)}:${two(t.second)}Z';
}

/// Copies [report] for the user to send on (CopyDiagnosticsButton): a text
/// button whose label turns to Copied, cross-fading on the effects spring.
class CopyDiagnosticsButton extends StatefulWidget {
  const CopyDiagnosticsButton({super.key, required this.report});

  final String report;

  @override
  State<CopyDiagnosticsButton> createState() => _CopyDiagnosticsButtonState();
}

class _CopyDiagnosticsButtonState extends State<CopyDiagnosticsButton> {
  bool _copied = false;

  @override
  void didUpdateWidget(CopyDiagnosticsButton old) {
    super.didUpdateWidget(old);
    if (old.report != widget.report) _copied = false;
  }

  Future<void> _copy() async {
    YoinHaptics.click();
    await Clipboard.setData(ClipboardData(text: widget.report));
    if (mounted) setState(() => _copied = true);
  }

  @override
  Widget build(BuildContext context) {
    final strings = ErrorStrings.of(context);
    return TextButton(
      onPressed: _copy,
      child: SpringCrossFade(
        childKey: _copied,
        fit: StackFit.loose,
        child: Text(_copied ? strings.detailsCopied : strings.copyDetails),
      ),
    );
  }
}
