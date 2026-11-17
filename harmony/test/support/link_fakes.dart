import 'package:yoin/platform/browser/url_opener.dart';
import 'package:yoin/platform/share/text_share.dart';

/// Records what the share sheet was handed.
final class RecordingTextShare implements TextShare {
  final List<(String, String?)> shared = [];

  @override
  Future<bool> shareText(String text, {String? title}) async {
    shared.add((text, title));
    return true;
  }
}

/// Records the pages opened in the browser.
final class RecordingUrlOpener implements UrlOpener {
  RecordingUrlOpener({this.opens = true});
  final bool opens;
  final List<Uri> opened = [];

  @override
  Future<bool> open(Uri url) async {
    opened.add(url);
    return opens;
  }
}
