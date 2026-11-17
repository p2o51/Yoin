import 'package:flutter/material.dart';

import '../settings/account_identity.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'memories_strings.dart';
import 'memories_ui_state.dart';
import 'palette/memory_palette.dart';

/// Beside Go to album on another account's memory (Android
/// MemoryOtherProfileLine): that account's mark (its avatar shape in its
/// service's hue, as Home's other-accounts shelf shows it) and "From …"
/// (owner 2026-11-04: only the account, no "this switches accounts"). Off the
/// cover, so nothing sits on the art. Reads as one line.
class MemoryOtherProfileLine extends StatelessWidget {
  const MemoryOtherProfileLine({
    super.key,
    required this.owner,
    required this.tones,
    this.maxWidth = OtherProfileTokens.lineMaxWidth,
  });

  final MemoryOtherProfile owner;
  final MemoryPaletteTones tones;
  final double maxWidth;

  @override
  Widget build(BuildContext context) {
    final strings = MemoriesStrings.of(context);
    return Semantics(
      label: strings.cdOtherProfileFrom(owner.name),
      excludeSemantics: true,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: maxWidth),
        child: SizedBox(
          height: OtherProfileTokens.lineHeight,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            spacing: OtherProfileTokens.lineMarkGap,
            children: [
              AccountMark(
                shapeIndex: owner.shape,
                identity: ServiceIdentity.forProvider(owner.provider),
                size: OtherProfileTokens.lineMark,
              ),
              Flexible(
                child: Text(
                  strings.otherProfileFrom(owner.name),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: gsf(
                    OtherProfileTokens.lineTextSize,
                    weight: 500,
                    height: 1.3,
                    letterSpacing: 0,
                  ).copyWith(color: tones.ink.withValues(alpha: OtherProfileTokens.lineAlpha)),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
