import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../l10n/strings.dart';
import '../ui/component/expressive_text_field.dart';
import 'subsonic_setup_view_model.dart';

/// Adding a Subsonic server (PushPage; opened with YoinPushRoute).
class SubsonicSetupScreen extends ConsumerStatefulWidget {
  const SubsonicSetupScreen({super.key});

  @override
  ConsumerState<SubsonicSetupScreen> createState() => _SubsonicSetupScreenState();
}

class _SubsonicSetupScreenState extends ConsumerState<SubsonicSetupScreen> {
  final _address = TextEditingController();
  final _username = TextEditingController();
  final _password = TextEditingController();

  @override
  void dispose() {
    _address.dispose();
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  void _connect() => ref.read(subsonicSetupViewModelProvider.notifier).connect(
        address: _address.text,
        username: _username.text,
        password: _password.text,
      );

  @override
  Widget build(BuildContext context) {
    final strings = YoinStrings.of(context);
    final text = Theme.of(context).textTheme;
    final colors = Theme.of(context).colorScheme;
    final state = ref.watch(subsonicSetupViewModelProvider);
    ref.listen(subsonicSetupViewModelProvider, (_, next) {
      if (next is SetupConnected) Navigator.of(context).pop();
    });
    final missing = state is SetupEditing ? state.missing : const <SetupField>{};
    final failure = state is SetupEditing ? state.failure : null;
    final connecting = state is SetupConnecting;
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(YoinSymbols.back),
          tooltip: MaterialLocalizations.of(context).backButtonTooltip,
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: const Text('Subsonic'),
      ),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
          children: [
            AutofillGroup(
              child: Column(
                children: [
                  ExpressiveTextField(
                    controller: _address,
                    label: strings.setupAddressLabel,
                    placeholder: strings.setupAddressPlaceholder,
                    keyboardType: TextInputType.url,
                    textInputAction: TextInputAction.next,
                    autofillHints: const [AutofillHints.url],
                    errorText: missing.contains(SetupField.address) ? strings.setupAddressRequired : null,
                  ),
                  const SizedBox(height: 16),
                  ExpressiveTextField(
                    controller: _username,
                    label: strings.setupUsernameLabel,
                    placeholder: strings.setupUsernamePlaceholder,
                    textInputAction: TextInputAction.next,
                    autofillHints: const [AutofillHints.username],
                    errorText: missing.contains(SetupField.username) ? strings.setupUsernameRequired : null,
                  ),
                  const SizedBox(height: 16),
                  ExpressiveTextField(
                    controller: _password,
                    label: strings.setupPasswordLabel,
                    placeholder: strings.setupPasswordLabel,
                    obscureText: true,
                    textInputAction: TextInputAction.done,
                    autofillHints: const [AutofillHints.password],
                    onSubmitted: (_) => _connect(),
                    errorText: missing.contains(SetupField.password) ? strings.setupPasswordRequired : null,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),
            SizedBox(
              height: 56,
              child: FilledButton(
                onPressed: connecting ? null : _connect,
                style: FilledButton.styleFrom(shape: const StadiumBorder()),
                // No explicit style: the theme's text styles carry onSurface, which would beat the button's onPrimary.
                child: Text(connecting ? strings.setupConnecting : strings.setupConnect),
              ),
            ),
            if (failure != null) ...[
              const SizedBox(height: 12),
              Text(
                switch (failure) {
                  SetupFailure.unreachable => strings.setupUnreachable,
                  SetupFailure.serverError => strings.setupServerError,
                  SetupFailure.saveFailed => strings.setupSaveFailed,
                },
                style: text.bodyMedium?.copyWith(color: colors.error),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
