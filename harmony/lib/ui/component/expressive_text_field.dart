import 'package:flutter/material.dart';

import '../../theme/motion.dart';

/// Yoin's text field (component/ExpressiveComponents.kt ExpressiveTextField):
/// a small label over a 20-corner surfaceContainerLow box with a hairline
/// border; focus springs the border and label to primary (effects spring).
class ExpressiveTextField extends StatefulWidget {
  const ExpressiveTextField({
    super.key,
    required this.controller,
    required this.label,
    required this.placeholder,
    this.obscureText = false,
    this.keyboardType,
    this.textInputAction,
    this.autofillHints,
    this.onSubmitted,
    this.errorText,
  });

  final TextEditingController controller;
  final String label;
  final String placeholder;
  final bool obscureText;
  final TextInputType? keyboardType;
  final TextInputAction? textInputAction;
  final Iterable<String>? autofillHints;
  final ValueChanged<String>? onSubmitted;

  /// Shown under the box in error colour when non-null.
  final String? errorText;

  @override
  State<ExpressiveTextField> createState() => _ExpressiveTextFieldState();
}

class _ExpressiveTextFieldState extends State<ExpressiveTextField> with TickerProviderStateMixin {
  final FocusNode _focus = FocusNode();
  late final SpringValue _focused = SpringValue(vsync: this, initial: 0, spring: YoinSprings.defaultEffects);

  @override
  void initState() {
    super.initState();
    _focus.addListener(() => _focused.animateTo(_focus.hasFocus ? 1 : 0));
  }

  @override
  void dispose() {
    _focus.dispose();
    _focused.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    return AnimatedBuilder(
      animation: _focused.animation,
      builder: (context, _) {
        final f = _focused.value.clamp(0.0, 1.0);
        final border = Color.lerp(colors.outline.withValues(alpha: 0.1), colors.primary.withValues(alpha: 0.5), f)!;
        final label = Color.lerp(colors.onSurfaceVariant.withValues(alpha: 0.76), colors.primary, f)!;
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(widget.label, style: text.labelSmall?.copyWith(color: label)),
            const SizedBox(height: 8),
            DecoratedBox(
              decoration: ShapeDecoration(
                color: colors.surfaceContainerLow,
                shape: RoundedSuperellipseBorder(
                  borderRadius: BorderRadius.circular(20),
                  side: BorderSide(color: widget.errorText != null ? colors.error : border),
                ),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                child: TextField(
                  controller: widget.controller,
                  focusNode: _focus,
                  obscureText: widget.obscureText,
                  keyboardType: widget.keyboardType,
                  textInputAction: widget.textInputAction,
                  autofillHints: widget.autofillHints,
                  onSubmitted: widget.onSubmitted,
                  autocorrect: false,
                  enableSuggestions: !widget.obscureText,
                  style: text.bodyLarge,
                  decoration: InputDecoration.collapsed(
                    hintText: widget.placeholder,
                    hintStyle: text.bodyLarge?.copyWith(color: colors.onSurfaceVariant.withValues(alpha: 0.6)),
                  ),
                ),
              ),
            ),
            if (widget.errorText != null) ...[
              const SizedBox(height: 6),
              Text(widget.errorText!, style: text.bodySmall?.copyWith(color: colors.error)),
            ],
          ],
        );
      },
    );
  }
}
