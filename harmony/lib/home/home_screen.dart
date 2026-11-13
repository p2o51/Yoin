import 'package:flutter/material.dart';

import '../l10n/strings.dart';

/// Home. Placeholder until the Subsonic source lands: the page title only.
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    return SafeArea(
      bottom: false,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 24, 24, 0),
        child: Align(
          alignment: Alignment.topLeft,
          child: Text(YoinStrings.of(context).barLabelHome, style: textTheme.headlineLarge),
        ),
      ),
    );
  }
}
