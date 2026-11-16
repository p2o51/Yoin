/// What kind of "no answer" a transport failure was, so the UI can say the
/// specific line Android's UserMessage.kt says for it: a refused or unknown
/// host, a timeout, or a TLS handshake that failed.
library;

import 'dart:async' show TimeoutException;
import 'dart:io' show HandshakeException, IOException, SocketException, TlsException;

import 'package:http/http.dart' as http;

/// Android's connectivity split (UserMessage.kt appLine): UnknownHost /
/// Connect, SocketTimeout, SSLException, and any other IOException.
enum NetworkFailureKind {
  /// The host couldn't be found or refused the connection (cmp_error_unreachable_connect).
  connect,

  /// The server took too long (cmp_error_timeout).
  timeout,

  /// The secure connection failed: certificate, protocol (cmp_error_ssl).
  tls,

  /// Any other transport failure (cmp_error_unreachable_io).
  io,
}

final _tlsWords = RegExp(r'handshake|certificate|tls|ssl', caseSensitive: false);
final _timeoutWords = RegExp(r'timed out|timeout', caseSensitive: false);

/// The kind of [error], a failure thrown by an HTTP send or read. A
/// `ClientException` (package:http's wrapper) is read by its message, the
/// only thing it keeps of what went wrong; the message is never shown.
NetworkFailureKind networkFailureKindOf(Object error) => switch (error) {
  TimeoutException() => NetworkFailureKind.timeout,
  HandshakeException() || TlsException() => NetworkFailureKind.tls,
  // A connect that timed out (ETIMEDOUT) is a timeout, as OkHttp's connect timeout is on Android.
  SocketException() when _timeoutWords.hasMatch(error.toString()) => NetworkFailureKind.timeout,
  SocketException() => NetworkFailureKind.connect,
  http.ClientException(:final message) when _tlsWords.hasMatch(message) => NetworkFailureKind.tls,
  http.ClientException(:final message) when _timeoutWords.hasMatch(message) => NetworkFailureKind.timeout,
  IOException() || http.ClientException() => NetworkFailureKind.io,
  _ => NetworkFailureKind.io,
};
