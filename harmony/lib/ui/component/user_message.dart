/// One short, actionable line for a failure (Android ui/component/UserMessage.kt).
///
/// Connectivity failures say which kind they were — the server can't be
/// reached, is taking too long, or the secure connection failed. A Subsonic
/// server's own error ("Wrong username or password") is said in its words;
/// Apple Music's refusals have lines of their own. Everything else says the
/// screen's fallback rather than a raw exception message.
library;

import 'dart:async' show TimeoutException;
import 'dart:io' show IOException;

import 'package:flutter/widgets.dart';
import 'package:http/http.dart' as http;

import '../../data/applemusic/apple_music_api.dart';
import '../../data/source/music_source.dart';
import 'error_strings.dart';

/// The app's own lines (cmp_error_*).
enum UserLine {
  unreachableConnect,
  unreachableIo,
  timeout,
  ssl,
  appleReconnect,
  appleToken,
  appleDenied,
  appleBusy;

  String text(ErrorStrings strings) => switch (this) {
    UserLine.unreachableConnect => strings.unreachableConnect,
    UserLine.unreachableIo => strings.unreachableIo,
    UserLine.timeout => strings.timeout,
    UserLine.ssl => strings.ssl,
    UserLine.appleReconnect => strings.appleReconnect,
    UserLine.appleToken => strings.appleToken,
    UserLine.appleDenied => strings.appleDenied,
    UserLine.appleBusy => strings.appleBusy,
  };

  static UserLine forNetwork(NetworkFailureKind kind) => switch (kind) {
    NetworkFailureKind.connect => UserLine.unreachableConnect,
    NetworkFailureKind.timeout => UserLine.timeout,
    NetworkFailureKind.tls => UserLine.ssl,
    NetworkFailureKind.io => UserLine.unreachableIo,
  };
}

/// What a failure has to say for itself: one of the app's lines, or the
/// server's own words. Null (from [userMessageOf]) = the screen's fallback.
sealed class UserMessage {
  const UserMessage();

  String text(ErrorStrings strings);
}

final class AppUserMessage extends UserMessage {
  const AppUserMessage(this.line);
  final UserLine line;

  @override
  String text(ErrorStrings strings) => line.text(strings);

  @override
  bool operator ==(Object other) => other is AppUserMessage && other.line == line;

  @override
  int get hashCode => line.hashCode;
}

/// A Subsonic server's message (already redacted by the transport).
final class ServerUserMessage extends UserMessage {
  const ServerUserMessage(this.message);
  final String message;

  @override
  String text(ErrorStrings strings) => message;

  @override
  bool operator ==(Object other) => other is ServerUserMessage && other.message == message;

  @override
  int get hashCode => message.hashCode;
}

/// The line [error] has of its own, or null when the screen says its fallback.
UserMessage? userMessageOf(Object? error) {
  final line = userLineOf(error);
  if (line != null) return AppUserMessage(line);
  final server = switch (error) {
    // Apple's exceptions carry Yoin's own English; only a Subsonic server speaks for itself.
    SourceException(cause: AppleMusicApiException()) => null,
    SourceAuthException(:final message) => message,
    SourceServerException(:final message, :final code?) when code >= 0 => message,
    _ => null,
  };
  final trimmed = server?.trim();
  return trimmed == null || trimmed.isEmpty ? null : ServerUserMessage(trimmed);
}

/// The app's line for [error] (UserMessage.kt appLine), or null.
UserLine? userLineOf(Object? error) => switch (error) {
  AppleMusicApiException() => _appleLine(error),
  SourceException(cause: final AppleMusicApiException apple) => _appleLine(apple),
  SourceNetworkException(:final kind) => UserLine.forNetwork(kind),
  TimeoutException() || IOException() || http.ClientException() => UserLine.forNetwork(networkFailureKindOf(error!)),
  _ => null,
};

UserLine? _appleLine(AppleMusicApiException e) => switch (e.failure) {
  AppleMusicFailure.network => UserLine.forNetwork(e.networkKind ?? NetworkFailureKind.io),
  AppleMusicFailure.userAuthorizationRequired => UserLine.appleReconnect,
  AppleMusicFailure.developerTokenRejected => UserLine.appleToken,
  // A 403 on one read says nothing of the subscription (Android): retry later.
  AppleMusicFailure.accessDenied => UserLine.appleDenied,
  AppleMusicFailure.rateLimited => UserLine.appleBusy,
  _ => null,
};

/// [error]'s line in the app's language, else [fallback].
String userMessageText(BuildContext context, Object? error, String fallback) =>
    userMessageOf(error)?.text(ErrorStrings.of(context)) ?? fallback;
