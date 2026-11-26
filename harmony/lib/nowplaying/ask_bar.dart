/// The Ask bar (named for the AI service in use) under the expanded About (Android AskGeminiBar.kt): a
/// 56 dp pill at rest that grows to a 252 dp card while focused (the corner
/// pinned at 28), the question field with the quick questions not asked yet
/// under it while it is empty, then the loading row with the AI's short
/// heading, or the error in its place.
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../settings/settings_components.dart' show SpringReveal;
import '../theme/motion.dart';
import '../theme/tokens.dart';
import '../ui/component/ai_service_scope.dart';
import '../ui/component/yoin_loading_indicator.dart';
import 'about_logic.dart';
import 'about_widgets.dart';
import 'nowplaying_strings.dart';

class AskGeminiBar extends StatefulWidget {
  const AskGeminiBar({
    super.key,
    required this.state,
    required this.onSubmit,
    required this.onFocus,
    required this.onCollapse,
    required this.onDismissError,
    this.asked = const [],
  });

  final AskBarState state;
  final ValueChanged<String> onSubmit;
  final VoidCallback onFocus;

  /// The keyboard went away without a question.
  final VoidCallback onCollapse;
  final VoidCallback onDismissError;

  /// What was already asked about this song: those quick prompts aren't offered again.
  final List<String> asked;

  @override
  State<AskGeminiBar> createState() => _AskGeminiBarState();
}

class _AskGeminiBarState extends State<AskGeminiBar> with TickerProviderStateMixin {
  final TextEditingController _draft = TextEditingController();
  final FocusNode _focus = FocusNode();
  late final SpringValue _height = SpringValue(vsync: this, initial: _targetHeight, spring: YoinSprings.defaultSpatial);
  late final SpringValue _thinking = SpringValue(
    vsync: this,
    initial: widget.state is AskLoading ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  /// The keyboard has been up during this Focused spell (a hide after it collapses the bar).
  bool _imeOpened = false;

  double get _targetHeight =>
      widget.state is AskFocused ? NowPlayingAboutTokens.askFocusedHeight : NowPlayingAboutTokens.askIdleHeight;

  @override
  void initState() {
    super.initState();
    _draft.addListener(() => setState(() {}));
    _focus.addListener(_onFocusChange);
  }

  void _onFocusChange() {
    // Focus left the field without a question (a tap elsewhere): back to rest, as the keyboard's hide does.
    if (!_focus.hasFocus && widget.state is AskFocused && _imeOpened) widget.onCollapse();
  }

  @override
  void didUpdateWidget(AskGeminiBar old) {
    super.didUpdateWidget(old);
    final reduce = MediaQuery.disableAnimationsOf(context);
    reduce ? _height.snapTo(_targetHeight) : _height.animateTo(_targetHeight);
    final thinking = widget.state is AskLoading ? 1.0 : 0.0;
    reduce ? _thinking.snapTo(thinking) : _thinking.animateTo(thinking);
    if (widget.state.runtimeType != old.state.runtimeType) _imeOpened = false;
    // An answer landed (Loading → Idle): the field is empty on the next tap.
    if (widget.state is AskIdle) _draft.clear();
    if (widget.state is AskFocused && old.state is! AskFocused) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _focus.requestFocus();
      });
    }
    if (widget.state is! AskFocused && _focus.hasFocus) _focus.unfocus();
  }

  @override
  void dispose() {
    _draft.dispose();
    _focus.dispose();
    _height.dispose();
    _thinking.dispose();
    super.dispose();
  }

  void _submit() {
    final text = _draft.text.trim();
    if (text.isEmpty) return;
    _focus.unfocus();
    widget.onSubmit(text);
  }

  /// Watch the keyboard while focused: only a visible → hidden change collapses the bar.
  void _trackIme(double inset) {
    if (widget.state is! AskFocused) return;
    if (inset > 0) {
      _imeOpened = true;
    } else if (_imeOpened) {
      _imeOpened = false;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onCollapse();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    _trackIme(MediaQuery.viewInsetsOf(context).bottom);
    final colors = Theme.of(context).colorScheme;
    final rest = colors.primaryContainer;
    final lightened = Color.lerp(rest, colors.surface, NowPlayingAboutTokens.askThinkingLighten)!;
    return AnimatedBuilder(
      animation: Listenable.merge([_height.animation, _thinking.animation]),
      builder: (context, child) => SizedBox(
        height: _height.value.clamp(0.0, double.infinity),
        width: double.infinity,
        // A solid colour block, no shadow; the corner stays pinned at 28 (a pill at 56).
        child: DecoratedBox(
          decoration: ShapeDecoration(
            color: Color.lerp(rest, lightened, _thinking.value.clamp(0.0, 1.0)),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(NowPlayingAboutTokens.askCorner)),
          ),
          child: ClipRect(child: child),
        ),
      ),
      child: switch (widget.state) {
        AskIdle() || AskFailed() => _idle(context),
        AskFocused() => _focused(context),
        AskLoading(:final title) => _loading(context, title),
      },
    );
  }

  Widget _indicator(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(color: Theme.of(context).colorScheme.primary, shape: BoxShape.circle),
    child: const SizedBox.square(dimension: NowPlayingAboutTokens.askIndicator),
  );

  Widget _idle(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final failed = widget.state is AskFailed ? widget.state as AskFailed : null;
    final ai = AiServiceScope.of(context);
    final message = failed == null ? null : (failed.keyMissing ? strings.aiKeyMissing(ai) : (failed.message ?? ''));
    return Semantics(
      button: true,
      textField: failed == null,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: failed != null ? widget.onDismissError : widget.onFocus,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: NowPlayingAboutTokens.askPadH),
          child: Row(
            children: [
              _indicator(context),
              const SizedBox(width: NowPlayingAboutTokens.askIndicatorGap),
              Expanded(
                child: Text(
                  message ?? strings.aiAskProvider(ai),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(
                    context,
                  ).textTheme.titleMedium?.copyWith(color: message != null ? colors.error : colors.onPrimaryContainer),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _focused(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.titleMedium?.copyWith(color: colors.onPrimaryContainer);
    final prompts = remainingQuickPrompts(strings.quickPrompts, widget.asked);
    // The column pads only top and bottom so the quick prompts can scroll edge to edge.
    return OverflowBox(
      alignment: Alignment.topCenter,
      minHeight: 0,
      maxHeight: NowPlayingAboutTokens.askFocusedHeight,
      child: SizedBox(
        height: NowPlayingAboutTokens.askFocusedHeight,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: NowPlayingAboutTokens.askPadV),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: NowPlayingAboutTokens.askPadH),
                child: _indicator(context),
              ),
              const SizedBox(height: NowPlayingAboutTokens.askFieldGap),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: NowPlayingAboutTokens.askPadH),
                  child: TextField(
                    controller: _draft,
                    focusNode: _focus,
                    maxLines: null,
                    expands: true,
                    textAlignVertical: TextAlignVertical.top,
                    keyboardType: TextInputType.multiline,
                    textInputAction: TextInputAction.send,
                    // Send asks once: Flutter calls onEditingComplete and then onSubmitted for the
                    // same key, so only onSubmitted asks; the empty hook keeps the keyboard up on an
                    // empty Send, as Android's onSend does.
                    onSubmitted: (_) => _submit(),
                    onEditingComplete: () {},
                    inputFormatters: [FilteringTextInputFormatter.deny('\n', replacementString: '')],
                    style: style,
                    cursorColor: colors.onPrimaryContainer,
                    decoration: InputDecoration.collapsed(
                      hintText: strings.aiAskProvider(AiServiceScope.of(context)),
                      hintStyle: style?.copyWith(color: colors.onPrimaryContainer.withValues(alpha: 0.6)),
                    ),
                  ),
                ),
              ),
              // Quick questions while the field is empty; a tap fills the field, Send asks it.
              SpringReveal(
                visible: _draft.text.isEmpty && prompts.isNotEmpty,
                child: Padding(
                  padding: const EdgeInsets.only(top: NowPlayingAboutTokens.promptTop),
                  child: QuickPrompts(
                    prompts: prompts,
                    container: colors.surface,
                    content: colors.onSurface,
                    padding: const EdgeInsets.symmetric(horizontal: NowPlayingAboutTokens.askPadH),
                    onPick: (prompt) {
                      _draft.value = TextEditingValue(
                        text: prompt,
                        selection: TextSelection.collapsed(offset: prompt.length),
                      );
                    },
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _loading(BuildContext context, String title) {
    final strings = NowPlayingStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: NowPlayingAboutTokens.askPadH),
      child: Row(
        children: [
          const YoinLoadingIndicator(size: NowPlayingAboutTokens.askLoadingIndicator),
          const SizedBox(width: NowPlayingAboutTokens.askIndicatorGap),
          Expanded(
            child: Text(
              title.trim().isEmpty ? strings.aiAskProvider(AiServiceScope.of(context)) : title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.titleMedium?.copyWith(color: colors.onPrimaryContainer),
            ),
          ),
        ],
      ),
    );
  }
}
