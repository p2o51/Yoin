/// Settings › Sync and storage › NeoDB (Android SettingsScreen NeoDbItem and
/// SettingsFeature.NeoDb's page). The row says whether album ratings and
/// reviews sync and opens the page (in the right pane on a wide window).
/// The page: the instance, Sign in (again) / Sign out, and behind "Use an
/// access token instead" the pasted-token escape hatch.
///
/// Sign-in is the browser OAuth of [NeoDbOAuth]; coming back to Yoin without
/// finishing on NeoDB's page cancels the wait after a short grace, as cloud
/// sync's sign-in does.
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/neodb/neodb_oauth.dart';
import '../nowplaying/haptics.dart';
import '../settings/settings_page.dart';
import '../settings/settings_pane.dart';
import '../settings/settings_rows.dart';
import '../settings/sync/cloud_sync_view_model.dart' show browserReturnGrace;
import '../theme/tokens.dart';
import '../ui/component/expressive_text_field.dart';
import '../ui/component/spring_cross_fade.dart';
import '../ui/navigation/yoin_push_route.dart';
import 'neodb_providers.dart';
import 'neodb_strings.dart';

/// The row in Settings' Sync and storage group.
class NeoDbSettingsRow extends ConsumerWidget {
  const NeoDbSettingsRow({super.key, this.trailing, this.onOpen});

  final Widget? trailing;

  /// The list-detail's opener; null pushes the page.
  final VoidCallback? onOpen;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = NeoDbStrings.of(context);
    final signedIn = ref.watch(neoDbAccountProvider).value?.signedIn ?? false;
    return SettingsItem(
      icon: YoinSymbols.reviews,
      title: strings.settingsTitle,
      summary: signedIn ? strings.summaryOn : strings.summaryOff,
      trailing: trailing,
      onTap:
          onOpen ?? () => Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const NeoDbSettingsScreen())),
    );
  }
}

class NeoDbSettingsScreen extends ConsumerStatefulWidget {
  const NeoDbSettingsScreen({super.key, this.inPane = false});

  final bool inPane;

  @override
  ConsumerState<NeoDbSettingsScreen> createState() => _NeoDbSettingsScreenState();
}

class _NeoDbSettingsScreenState extends ConsumerState<NeoDbSettingsScreen> {
  final _instance = TextEditingController();
  final _token = TextEditingController();
  String? _seededInstance;
  String? _seededToken;
  bool _manualToken = false;
  bool _signingIn = false;
  AppLifecycleListener? _lifecycle;
  Timer? _returnTimer;

  @override
  void initState() {
    super.initState();
    _token.addListener(() => setState(() {}));
    try {
      _lifecycle = AppLifecycleListener(onResume: _onResume);
    } catch (_) {
      _lifecycle = null;
    }
  }

  @override
  void dispose() {
    _lifecycle?.dispose();
    _returnTimer?.cancel();
    _instance.dispose();
    _token.dispose();
    super.dispose();
  }

  /// Back from the browser without NeoDB's answer: stop waiting after a grace
  /// (the redirect usually lands first).
  void _onResume() {
    if (!_signingIn) return;
    final account = ref.read(neoDbAccountProvider.notifier);
    if (!account.waitingForBrowser) return;
    _returnTimer?.cancel();
    _returnTimer = Timer(browserReturnGrace, () {
      if (account.waitingForBrowser) account.cancelSignIn();
    });
  }

  void _message(String text) => ref.read(neoDbMessengerProvider)(text);

  Future<void> _signIn(NeoDbStrings strings) async {
    if (_signingIn) return;
    YoinHaptics.click();
    setState(() => _signingIn = true);
    final NeoDbOAuthResult? result;
    try {
      result = await ref.read(neoDbAccountProvider.notifier).signIn(_instance.text);
    } finally {
      _returnTimer?.cancel();
      if (mounted) setState(() => _signingIn = false);
    }
    switch (result) {
      case null:
        _message(strings.invalidUrl);
      case NeoDbOAuthFailure(:final message):
        _message(message);
      case NeoDbOAuthSuccess() || NeoDbOAuthCancelled():
        break;
    }
  }

  Future<void> _signOut() async {
    YoinHaptics.click();
    _token.text = '';
    await ref.read(neoDbAccountProvider.notifier).clearToken();
  }

  Future<void> _saveToken(NeoDbStrings strings) async {
    YoinHaptics.click();
    final ok = await ref.read(neoDbAccountProvider.notifier).saveConfig(_instance.text, _token.text);
    if (!ok) _message(strings.invalidUrl);
  }

  @override
  Widget build(BuildContext context) {
    final strings = NeoDbStrings.of(context);
    final account = ref.watch(neoDbAccountProvider).value;
    // The drafts follow what is stored whenever that changes (rememberSaveable(instance) / (accessToken)).
    if (account != null && account.instance != _seededInstance) {
      _seededInstance = account.instance;
      _instance.text = account.instance;
    }
    if (account != null && account.token != _seededToken) {
      _seededToken = account.token;
      _token.text = account.token;
    }
    final signedIn = account?.signedIn ?? false;
    final draft = _token.text.trim();
    final canSave = draft.isNotEmpty && draft != (account?.token.trim() ?? '');
    return SettingsSubPage(
      title: strings.settingsTitle,
      inPane: widget.inPane,
      children: [
        SettingsSegments(
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: NeoDbTokens.settingsPanelPaddingH,
                vertical: NeoDbTokens.settingsPanelPaddingV,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  ExpressiveTextField(
                    key: const ValueKey('neodb-instance'),
                    controller: _instance,
                    label: strings.instanceLabel,
                    placeholder: strings.instancePlaceholder,
                    keyboardType: TextInputType.url,
                    textInputAction: TextInputAction.done,
                  ),
                  const SizedBox(height: NeoDbTokens.settingsPanelGap),
                  // A row that wraps at large text rather than running off the panel.
                  Wrap(
                    spacing: NeoDbTokens.settingsButtonGap,
                    runSpacing: NeoDbTokens.settingsButtonGap,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      FilledButton(
                        key: const ValueKey('neodb-sign-in'),
                        onPressed: _signingIn ? null : () => _signIn(strings),
                        child: Text(signedIn ? strings.signInAgain : strings.signIn),
                      ),
                      if (signedIn)
                        TextButton(
                          key: const ValueKey('neodb-sign-out'),
                          onPressed: _signOut,
                          child: Text(strings.signOut),
                        ),
                    ],
                  ),
                  const SizedBox(height: NeoDbTokens.settingsPanelGap),
                  // The token is an escape hatch, not a step: hidden until asked for.
                  Align(
                    alignment: AlignmentDirectional.topStart,
                    child: SpringCrossFade(
                      childKey: _manualToken,
                      fit: StackFit.loose,
                      child: !_manualToken
                          ? TextButton(
                              key: const ValueKey('neodb-use-token'),
                              onPressed: () => setState(() => _manualToken = true),
                              child: Text(strings.useToken),
                            )
                          : Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                ExpressiveTextField(
                                  key: const ValueKey('neodb-token'),
                                  controller: _token,
                                  label: strings.tokenLabel,
                                  placeholder: strings.tokenPlaceholder,
                                  obscureText: true,
                                  textInputAction: TextInputAction.done,
                                ),
                                const SizedBox(height: NeoDbTokens.settingsTokenGap),
                                OutlinedButton(
                                  key: const ValueKey('neodb-save-token'),
                                  onPressed: canSave ? () => _saveToken(strings) : null,
                                  child: Text(strings.saveToken),
                                ),
                              ],
                            ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ],
    );
  }
}

/// Opens the NeoDB page: in the wide window's right pane, else pushed.
void openNeoDbSettingsPage(BuildContext context) {
  final openInPane = SettingsPaneScope.maybeOf(context);
  if (openInPane != null) {
    openInPane(const NeoDbPane());
  } else {
    Navigator.of(context).push(YoinPushRoute<void>(builder: (_) => const NeoDbSettingsScreen()));
  }
}
