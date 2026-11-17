/// Dart packages the lockfile resolves that the app does NOT compile in:
/// build and test tooling (analyzer, lints, the test runner's server side…).
/// Flutter's license collection sees every package in the package config,
/// dev dependencies included, so the licenses page leaves these out.
///
/// Generated: everything in `pubspec.lock` minus the import closure of
/// `lib/main.dart` (test/support/shipped_packages.dart). The licenses test
/// fails with the new list whenever the dependencies change.
const notShippedDartPackages = <String>{
  '_fe_analyzer_shared',
  'analyzer',
  'args',
  'cli_config',
  'cli_util',
  'convert',
  'coverage',
  'ffi',
  'file',
  'flutter_lints',
  'frontend_server_client',
  'glob',
  'http_multi_server',
  'io',
  'lints',
  'logging',
  'mime',
  'node_preamble',
  'package_config',
  'pub_semver',
  'shelf',
  'shelf_packages_handler',
  'shelf_static',
  'shelf_web_socket',
  'source_map_stack_trace',
  'source_maps',
  'watcher',
  'web_socket',
  'web_socket_channel',
  'webkit_inspection_protocol',
  'yaml',
};
