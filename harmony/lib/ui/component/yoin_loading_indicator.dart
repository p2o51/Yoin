import 'package:flutter/material.dart';

import 'expressive/loading_indicator.dart';

/// Yoin's loading affordance (component/YoinLoadingIndicator.kt): the M3
/// Expressive ContainedLoadingIndicator — the MaterialShapes sequence turning
/// and morphing on its spring — in secondaryContainer with
/// onSecondaryContainer ink. [contained] false drops the disc for inline
/// spots (bare LoadingIndicator, primary ink unless [color] says otherwise).
class YoinLoadingIndicator extends StatelessWidget {
  const YoinLoadingIndicator({super.key, this.size = 48, this.contained = true, this.color, this.containerColor});

  final double size;
  final bool contained;
  final Color? color;
  final Color? containerColor;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return ExpressiveLoadingIndicator(
      size: size,
      contained: contained,
      indicatorColor: color ?? (contained ? colors.onSecondaryContainer : colors.primary),
      containerColor: contained ? (containerColor ?? colors.secondaryContainer) : null,
      semanticsLabel: MaterialLocalizations.of(context).refreshIndicatorSemanticLabel,
    );
  }
}
