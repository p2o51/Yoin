import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../shell/app_messages.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'detail_palette.dart';
import 'detail_strings.dart';

/// Detail header geometry (AlbumDetailScreen.kt AlbumTopHeader, ArtistTopHeader).
abstract final class DetailHeaderMetrics {
  static const double paddingStart = 8;
  static const double paddingEnd = 4;
  static const double paddingEndAlbum = 16;
  static const double paddingTop = 4;
  static const double paddingBottom = 6;
  static const double backButton = 40;
  static const double backIcon = 22;
  static const double titleGap = 14;

  /// Title (headlineSmall 32) + meta (bodyMedium 20) — the header row's height.
  static const double rowHeight = 52;

  /// The header band below the status bar.
  static const double bandHeight = paddingTop + rowHeight + paddingBottom;
}

/// The page's explicit back (DetailBackButton.kt): FilledTonalIconButton
/// 40dp (48 touch), surfaceContainer / onSurface, back glyph 22. Pops
/// through the same Navigator path as the system back.
class DetailBackButton extends StatelessWidget {
  const DetailBackButton({super.key, this.container, this.content});

  final Color? container;
  final Color? content;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return SizedBox.square(
      dimension: 48,
      child: Center(
        child: IconButton.filledTonal(
          tooltip: DetailStrings.of(context).cdBack,
          constraints: const BoxConstraints.tightFor(
            width: DetailHeaderMetrics.backButton,
            height: DetailHeaderMetrics.backButton,
          ),
          padding: EdgeInsets.zero,
          style: IconButton.styleFrom(
            backgroundColor: container ?? colors.surfaceContainer,
            foregroundColor: content ?? colors.onSurface,
            tapTargetSize: MaterialTapTargetSize.padded,
          ),
          icon: const Icon(YoinSymbols.back, size: DetailHeaderMetrics.backIcon),
          onPressed: () => Navigator.of(context).maybePop(),
        ),
      ),
    );
  }
}

/// ExpressivePageBackground with the cover accent: [wash, surface,
/// surfaceContainerLow], the wash following the page colours.
class DetailPageBackground extends StatelessWidget {
  const DetailPageBackground({super.key});

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return DetailColorsBuilder(
      builder: (context, page) => DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [page.wash, colors.surface, colors.surfaceContainerLow],
          ),
        ),
        child: const SizedBox.expand(),
      ),
    );
  }
}

/// The page title in the page accent (headlineSmall, one line).
class DetailTitle extends StatelessWidget {
  const DetailTitle(this.text, {super.key, this.trailing, this.color});

  final String text;
  final Widget? trailing;

  /// Overrides the accent (the docked header's onAccent hand-over).
  final Color? color;

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.headlineSmall;
    return DetailColorsBuilder(
      builder: (context, page) => Row(
        children: [
          Flexible(
            child: Text(
              text,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: style?.copyWith(color: color ?? page.accent),
            ),
          ),
          if (trailing != null) ...[const SizedBox(width: 8), trailing!],
        ],
      ),
    );
  }
}

/// Centred 48dp loading indicator.
class DetailLoadingView extends StatelessWidget {
  const DetailLoadingView({super.key});

  @override
  Widget build(BuildContext context) => const Center(child: YoinLoadingIndicator());
}

/// DetailErrorState: message (bodyLarge, error) and Retry, padding 24, spacing 10.
class DetailErrorView extends StatelessWidget {
  const DetailErrorView({super.key, required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              message,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyLarge?.copyWith(color: colors.error),
            ),
            const SizedBox(height: 10),
            TextButton(onPressed: onRetry, child: Text(DetailStrings.of(context).retry)),
          ],
        ),
      ),
    );
  }
}

/// A page's short confirmation (Android postMessage on the window's
/// snackbar): the shell's one snackbar, above the bar.
void showDetailMessage(BuildContext context, String text) => showAppMessage(context, text);
