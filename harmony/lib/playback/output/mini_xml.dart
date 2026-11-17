/// A small, closed XML reader for UPnP: device descriptions, SOAP replies
/// and Sonos' ZoneGroupState. Elements, attributes, text, CDATA and the five
/// predefined entities plus character references; comments and processing
/// instructions are skipped. Any DTD is refused before parsing (Android
/// DlnaProtocol rejects `<!DOCTYPE` / `<!ENTITY` the same way), so nothing
/// on the network can make it expand entities or reach outside the string.
library;

/// The largest document read (Android DlnaProtocol MAX_XML_CHARS).
const int maxXmlChars = 1048576;

/// One element. Names keep their prefix ([name]); lookups go by [localName],
/// the namespace-agnostic way Android's `getElementsByTagNameNS("*", …)` reads.
final class XmlElement {
  XmlElement(this.name);

  final String name;
  final Map<String, String> attributes = {};

  /// Child elements and text runs ([String]), in document order.
  final List<Object> nodes = [];

  String get localName {
    final colon = name.indexOf(':');
    return colon < 0 ? name : name.substring(colon + 1);
  }

  Iterable<XmlElement> get children => nodes.whereType<XmlElement>();

  /// Direct children called [local].
  Iterable<XmlElement> childrenNamed(String local) => children.where((e) => e.localName == local);

  XmlElement? child(String local) {
    for (final e in children) {
      if (e.localName == local) return e;
    }
    return null;
  }

  /// The trimmed text of the first child called [local], or ''.
  String childText(String local) => child(local)?.text.trim() ?? '';

  /// Every text run under this element (DOM textContent).
  String get text {
    final out = StringBuffer();
    void walk(XmlElement e) {
      for (final node in e.nodes) {
        if (node is String) {
          out.write(node);
        } else if (node is XmlElement) {
          walk(node);
        }
      }
    }

    walk(this);
    return out.toString();
  }

  /// Every element below this one called [local], depth first (not this one).
  Iterable<XmlElement> descendants(String local) sync* {
    for (final e in children) {
      if (e.localName == local) yield e;
      yield* e.descendants(local);
    }
  }

  /// The attribute whose local name is [local], or ''.
  String attribute(String local) {
    final direct = attributes[local];
    if (direct != null) return direct;
    for (final entry in attributes.entries) {
      final colon = entry.key.indexOf(':');
      if (colon >= 0 && entry.key.substring(colon + 1) == local) return entry.value;
    }
    return '';
  }
}

/// Parses [xml] into its root element. Throws [FormatException] for anything
/// malformed, too large, or carrying a DTD.
XmlElement parseXml(String xml) {
  if (xml.length > maxXmlChars) throw const FormatException('XML too large');
  final lower = xml.toLowerCase();
  if (lower.contains('<!doctype') || lower.contains('<!entity')) {
    throw const FormatException('XML contains a DTD');
  }
  final stack = <XmlElement>[];
  XmlElement? root;
  var i = 0;
  final n = xml.length;

  void addText(String raw, {bool decode = true}) {
    if (raw.isEmpty) return;
    if (stack.isEmpty) {
      if (raw.trim().isNotEmpty) throw const FormatException('Text outside the root element');
      return;
    }
    stack.last.nodes.add(decode ? decodeXmlEntities(raw) : raw);
  }

  while (i < n) {
    final lt = xml.indexOf('<', i);
    if (lt < 0) {
      addText(xml.substring(i));
      break;
    }
    if (lt > i) addText(xml.substring(i, lt));
    if (xml.startsWith('<!--', lt)) {
      final end = xml.indexOf('-->', lt + 4);
      if (end < 0) throw const FormatException('Unterminated comment');
      i = end + 3;
      continue;
    }
    if (xml.startsWith('<![CDATA[', lt)) {
      final end = xml.indexOf(']]>', lt + 9);
      if (end < 0) throw const FormatException('Unterminated CDATA');
      addText(xml.substring(lt + 9, end), decode: false);
      i = end + 3;
      continue;
    }
    if (xml.startsWith('<?', lt)) {
      final end = xml.indexOf('?>', lt + 2);
      if (end < 0) throw const FormatException('Unterminated processing instruction');
      i = end + 2;
      continue;
    }
    if (xml.startsWith('<!', lt)) throw const FormatException('Unsupported markup declaration');
    final gt = _tagEnd(xml, lt + 1);
    if (gt < 0) throw const FormatException('Unterminated tag');
    var tag = xml.substring(lt + 1, gt);
    i = gt + 1;
    if (tag.startsWith('/')) {
      final name = tag.substring(1).trim();
      if (stack.isEmpty || stack.last.name != name) throw const FormatException('Mismatched closing tag');
      stack.removeLast();
      continue;
    }
    final selfClosing = tag.endsWith('/');
    if (selfClosing) tag = tag.substring(0, tag.length - 1);
    final element = _openTag(tag);
    if (stack.isEmpty) {
      if (root != null) throw const FormatException('More than one root element');
      root = element;
    } else {
      stack.last.nodes.add(element);
    }
    if (!selfClosing) stack.add(element);
  }
  if (root == null || stack.isNotEmpty) throw const FormatException('Incomplete XML');
  return root;
}

final _nameEnd = RegExp(r'[\s]');
final _attribute = RegExp(r'''([^\s=]+)\s*=\s*(?:"([^"]*)"|'([^']*)')''');

XmlElement _openTag(String tag) {
  final trimmed = tag.trim();
  if (trimmed.isEmpty) throw const FormatException('Empty tag');
  final cut = trimmed.indexOf(_nameEnd);
  final name = cut < 0 ? trimmed : trimmed.substring(0, cut);
  final element = XmlElement(name);
  if (cut >= 0) {
    for (final m in _attribute.allMatches(trimmed.substring(cut))) {
      element.attributes[m.group(1)!] = decodeXmlEntities(m.group(2) ?? m.group(3) ?? '');
    }
  }
  return element;
}

/// The `>` closing the tag that starts at [from], skipping quoted values.
int _tagEnd(String xml, int from) {
  String? quote;
  for (var i = from; i < xml.length; i++) {
    final c = xml[i];
    if (quote != null) {
      if (c == quote) quote = null;
    } else if (c == '"' || c == "'") {
      quote = c;
    } else if (c == '>') {
      return i;
    }
  }
  return -1;
}

final _entity = RegExp(r'&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|amp|quot|apos);');

/// The five predefined entities and character references; anything else stays as written.
String decodeXmlEntities(String value) {
  if (!value.contains('&')) return value;
  return value.replaceAllMapped(_entity, (m) {
    final body = m.group(1)!;
    switch (body) {
      case 'lt':
        return '<';
      case 'gt':
        return '>';
      case 'amp':
        return '&';
      case 'quot':
        return '"';
      case 'apos':
        return "'";
    }
    final code = body.startsWith('#x') ? int.tryParse(body.substring(2), radix: 16) : int.tryParse(body.substring(1));
    if (code == null || code < 0 || code > 0x10FFFF) return m.group(0)!;
    return String.fromCharCode(code);
  });
}
