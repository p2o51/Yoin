import 'dart:convert';
import 'dart:io';

/// The Dart packages whose code the app actually compiles in: the import
/// closure (import / export / part, conditional URIs included) from
/// `lib/main.dart`, resolved through `.dart_tool/package_config.json`.
/// Run from the package root (as `flutter test` does).
Set<String> shippedDartPackages() {
  final config = jsonDecode(File('.dart_tool/package_config.json').readAsStringSync()) as Map<String, dynamic>;
  final configDir = Directory('.dart_tool').absolute.uri;
  final roots = <String, Uri>{};
  for (final p in (config['packages'] as List).cast<Map<String, dynamic>>()) {
    final root = configDir.resolve(p['rootUri'] as String);
    final rootDir = root.path.endsWith('/') ? root : Uri.parse('$root/');
    roots[p['name'] as String] = rootDir.resolve(p['packageUri'] as String);
  }
  final self = config['packages'] is List ? _selfName() : null;
  final shipped = <String>{};
  final seen = <String>{};
  final queue = <Uri>[File('lib/main.dart').absolute.uri];
  final directive = RegExp(r'''^\s*(?:import|export|part)\s+['"]([^'"]+)['"]''', multiLine: true);
  final conditional = RegExp(r'''^\s*if\s*\([^)]*\)\s*['"]([^'"]+)['"]''', multiLine: true);
  while (queue.isNotEmpty) {
    final file = queue.removeLast();
    if (!seen.add(file.toString())) continue;
    final f = File.fromUri(file);
    if (!f.existsSync()) continue;
    final text = f.readAsStringSync();
    for (final m in [...directive.allMatches(text), ...conditional.allMatches(text)]) {
      final uri = Uri.parse(m.group(1)!);
      if (uri.scheme == 'package') {
        final name = uri.pathSegments.first;
        final root = roots[name];
        if (root == null) continue;
        if (name != self) shipped.add(name);
        queue.add(root.resolve(uri.pathSegments.skip(1).join('/')));
      } else if (!uri.hasScheme) {
        queue.add(file.resolveUri(uri));
      }
    }
  }
  // sky_engine is dart:ui and the Dart SDK libraries: always linked in.
  return shipped..add('sky_engine');
}

/// Every Dart package the lockfile resolves (what the build's license
/// collection sees, dev dependencies included).
Set<String> lockedDartPackages() {
  final names = <String>{};
  var inPackages = false;
  for (final line in File('pubspec.lock').readAsLinesSync()) {
    if (line == 'packages:') {
      inPackages = true;
      continue;
    }
    if (inPackages && line.isNotEmpty && !line.startsWith(' ')) inPackages = false;
    final m = RegExp(r'^  ([A-Za-z0-9_]+):$').firstMatch(line);
    if (inPackages && m != null) names.add(m.group(1)!);
  }
  return names;
}

String _selfName() =>
    RegExp(r'^name:\s*(\S+)', multiLine: true).firstMatch(File('pubspec.yaml').readAsStringSync())!.group(1)!;
