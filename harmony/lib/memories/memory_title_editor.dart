/// The user's own title for a memory (Android showcase/MemoryTitleEditor.kt,
/// owner 2026-10-05: "the AI title should leave room for the user to change
/// it"). Tap the title — on the card face, in the diary, on the spread's
/// left page — and it becomes an in-place field in the title's own
/// typography (no box, no outline: the journal language of the diary
/// writer). Under it a quiet row drops in: [Restore] Cancel Save, Save the
/// album-coloured journal pill. Save confirms and the title changes in
/// place; an empty save is a restore.
///
/// Editing is an in-page mode of the memory, not a place: while it lasts no
/// drag pages the deck or moves q / p (the surface holds them), and system
/// back cancels the edit first (MemoriesSurface's back, the TitleEdit level).
///
/// The keyboard: the diary scrolls the field into view; the card and the
/// spread's left page don't scroll, so they rise by the overlap
/// ([memoryTitleLiftPx], [MemoryTitleLift]).
library;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';

import '../home/widgets/spring_height.dart';
import '../nowplaying/haptics.dart';
import '../theme/motion.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'copy/memory_voice.dart';
import 'memories_strings.dart';
import 'memories_ui_state.dart';
import 'palette/memory_palette.dart';

/// The three places a memory's title can be edited; each edits on its own.
enum MemoryTitleSurface { card, diary, spread }

/// The key of [memory]'s title on [surface].
String memoryTitleKey(MemoryTitleSurface surface, MemoryEntry memory) => '${surface.name}:${memory.sessionId}';

// ---------------------------------------------------------------- pure rules

/// The longest title the field takes: a title, not a note.
const int memoryTitleMaxLength = 80;

/// What the field opens with: the title shown, or nothing when the album
/// name stands in for one.
String memoryTitleDraftSeed(MemoryEntry memory) =>
    memory.memoryTitleKind == MemoryTitleKind.album ? '' : memory.memoryTitle;

/// The empty field's hint: what an empty save shows — Yoin's own title,
/// else the album name.
String memoryTitlePlaceholder(MemoryEntry memory) => memory.yoinTitle ?? memory.albumName;

/// Restore shows only over a user title with Yoin's own under it; over the
/// album name alone, clearing the field is the restore.
bool memoryTitleCanRestore(MemoryEntry memory) => memory.hasUserTitle && memory.yoinTitle != null;

/// AI and the user's own titles share the serif; the album name has its own face.
MemoryTitleKind memoryTitleTypeGroup(MemoryTitleKind kind) => kind == MemoryTitleKind.user ? MemoryTitleKind.ai : kind;

/// One line, at most [memoryTitleMaxLength]: a pasted line break becomes a space.
class MemoryTitleInputFormatter extends TextInputFormatter {
  const MemoryTitleInputFormatter();

  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) {
    final flat = newValue.text.replaceAll('\n', ' ').replaceAll('\r', ' ');
    final text = flat.length > memoryTitleMaxLength ? flat.substring(0, memoryTitleMaxLength) : flat;
    if (text == newValue.text) return newValue;
    int clamp(int i) => i < 0 ? i : (i > text.length ? text.length : i);
    return TextEditingValue(
      text: text,
      selection: TextSelection(
        baseOffset: clamp(newValue.selection.baseOffset),
        extentOffset: clamp(newValue.selection.extentOffset),
      ),
    );
  }
}

/// What Save does with the field.
sealed class TitleEditOutcome {
  const TitleEditOutcome();
}

/// Nothing changed: close, write nothing.
final class TitleUnchanged extends TitleEditOutcome {
  const TitleUnchanged();
}

/// Take the user's title off: back to Yoin's own (the row goes).
final class TitleRestore extends TitleEditOutcome {
  const TitleRestore();
}

/// The user's new title, trimmed.
final class TitleSave extends TitleEditOutcome {
  const TitleSave(this.title);
  final String title;

  @override
  bool operator ==(Object other) => other is TitleSave && other.title == title;

  @override
  int get hashCode => title.hashCode;
}

/// Save on [draft]: the same words as [seed] change nothing; an empty field
/// restores Yoin's title when the user's is in use and changes nothing
/// otherwise; anything else is the new title.
TitleEditOutcome titleEditOutcome(String draft, String seed, {required bool userTitleInUse}) {
  final words = draft.trim();
  if (words == seed.trim()) return const TitleUnchanged();
  if (words.isEmpty) return userTitleInUse ? const TitleRestore() : const TitleUnchanged();
  return TitleSave(words);
}

/// How far a block whose bottom sits at [blockBottom] must rise to stay
/// [clearance] above the visible bottom [visibleBottom] (the keyboard's top).
double memoryTitleLiftPx({required double blockBottom, required double visibleBottom, required double clearance}) {
  final over = blockBottom + clearance - visibleBottom;
  return over > 0 ? over : 0;
}

/// A written title (the user's own, Yoin's AI title) is set in the serif;
/// the device's serif, Google Sans Flex where there is none.
TextStyle memoryWrittenTitleStyle(double size, {required double height, required Color color}) => TextStyle(
  fontFamily: 'serif',
  fontFamilyFallback: const ['Noto Serif', 'Noto Serif CJK SC', 'GoogleSansFlex'],
  fontSize: size,
  height: height,
  fontWeight: FontWeight.w600,
  letterSpacing: 0,
  color: color,
);

// ---------------------------------------------------------------- the editor

/// The deck's one title editor: which memory's title is open, on which
/// surface, and its draft. An edit ends when its surface leaves.
class MemoryTitleEditor extends ChangeNotifier {
  final TextEditingController draft = TextEditingController();
  String? _key;
  String _seed = '';

  /// "surface:sessionId" of the title being edited, or null.
  String? get editingKey => _key;
  bool get isEditing => _key != null;

  /// What the field opened with: saving it unchanged writes nothing.
  String get seed => _seed;

  bool isEditingKey(String key) => _key == key;

  /// The open field's save: the row's Save and the keyboard's Done run the same one.
  VoidCallback? saveAction;

  /// Opens [key]'s title with [seedText] in the field, the cursor at its end.
  void begin(String key, String seedText) {
    _seed = seedText;
    draft.value = TextEditingValue(
      text: seedText,
      selection: TextSelection.collapsed(offset: seedText.length),
    );
    _key = key;
    notifyListeners();
  }

  /// The edit is over: saved, cancelled or restored.
  void end() {
    // A slot's late clean-up can land after the deck (and this editor) went.
    if (_key == null || _disposed) return;
    _key = null;
    notifyListeners();
  }

  void requestSave() => saveAction?.call();

  bool _disposed = false;

  @override
  void dispose() {
    _disposed = true;
    draft.dispose();
    super.dispose();
  }
}

/// What a page needs to make its titles editable: the deck's editor and the writes.
@immutable
class MemoryTitleEditing {
  const MemoryTitleEditing({required this.editor, required this.onSave, required this.onRestore});

  final MemoryTitleEditor editor;

  /// Writes the user's [title]; false when the write failed.
  final Future<bool> Function(MemoryEntry memory, String title) onSave;
  final Future<bool> Function(MemoryEntry memory) onRestore;
}

// ---------------------------------------------------------------- the title slot

/// The title as a face shows it.
@immutable
sealed class _Face {
  const _Face();
}

final class _Shown extends _Face {
  const _Shown(this.text, this.kind);
  final String text;
  final MemoryTitleKind kind;

  @override
  bool operator ==(Object other) => other is _Shown && other.text == text && other.kind == kind;

  @override
  int get hashCode => Object.hash(text, kind);
}

final class _Editing extends _Face {
  const _Editing(this.kind);
  final MemoryTitleKind kind;

  @override
  bool operator ==(Object other) => other is _Editing && other.kind == kind;

  @override
  int get hashCode => kind.hashCode;
}

/// Whether going from [from] to [to] needs no crossfade: the field closing
/// on the very words it held in the same face, or opening in the same face.
bool _seamless(_Face from, _Face to, String draft) => switch ((from, to)) {
  (_Editing f, _Shown t) => t.text == draft.trim() && memoryTitleTypeGroup(f.kind) == memoryTitleTypeGroup(t.kind),
  (_Shown f, _Editing t) => memoryTitleTypeGroup(f.kind) == memoryTitleTypeGroup(t.kind),
  _ => from == to,
};

/// A memory's title on [surface]: the text at rest, the field while it is
/// being edited, a crossfade between them on the effects spring with the
/// height on the spatial spring (none when the field closes on its own words
/// in the same face). A title changed from elsewhere crossfades the same way.
/// [enabled]: this page is current and the surface takes input; an edit
/// whose surface stops taking input ends, unsaved.
class MemoryTitleSlot extends StatefulWidget {
  const MemoryTitleSlot({
    super.key,
    required this.memory,
    required this.surface,
    required this.editing,
    required this.tones,
    required this.style,
    required this.enabled,
    required this.centred,
    this.maxLines,
  });

  final MemoryEntry memory;
  final MemoryTitleSurface surface;
  final MemoryTitleEditing? editing;
  final MemoryPaletteTones tones;

  /// The typography of a kind, coloured.
  final TextStyle Function(MemoryTitleKind kind) style;
  final bool enabled;
  final bool centred;

  /// The resting title's line cap for a kind (null = no cap).
  final int? Function(MemoryTitleKind kind)? maxLines;

  @override
  State<MemoryTitleSlot> createState() => _MemoryTitleSlotState();
}

class _MemoryTitleSlotState extends State<MemoryTitleSlot> with TickerProviderStateMixin {
  // A save shows its words at once, before the deck's refresh lands.
  _Shown? _held;
  MemoryTitleKind? _editKind;
  _Face? _face;
  int _faceKey = 0;
  _Face? _outgoing;
  late final SpringValue _fade = SpringValue(vsync: this, initial: 1, spring: YoinSprings.defaultEffects);
  MemoryTitleEditor? _listening;

  String get _key => memoryTitleKey(widget.surface, widget.memory);

  bool get _editable => widget.editing != null;

  bool get _open => _editable && (widget.editing?.editor.isEditingKey(_key) ?? false);

  @override
  void initState() {
    super.initState();
    _fade;
    _listen();
  }

  @override
  void didUpdateWidget(MemoryTitleSlot old) {
    super.didUpdateWidget(old);
    if (old.memory.memoryTitle != widget.memory.memoryTitle ||
        old.memory.memoryTitleKind != widget.memory.memoryTitleKind) {
      _held = null;
    }
    _listen();
    if (_open && !widget.enabled) {
      // The page swiped away or the diary opened over the card: the edit goes, unsaved.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _open && !widget.enabled) widget.editing?.editor.end();
      });
    }
  }

  void _listen() {
    final editor = widget.editing?.editor;
    if (identical(editor, _listening)) return;
    _listening?.removeListener(_changed);
    _listening = editor?..addListener(_changed);
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    final editor = _listening;
    _listening?.removeListener(_changed);
    // The surface left with its title open (another tier, a new deck): the edit goes with it, unsaved.
    if (editor != null && editor.isEditingKey(_key)) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (editor.isEditingKey(_key)) editor.end();
      });
    }
    _fade.dispose();
    super.dispose();
  }

  Future<void> _commit() async {
    final editing = widget.editing;
    if (editing == null) return;
    final memory = widget.memory;
    final editor = editing.editor;
    final outcome = titleEditOutcome(editor.draft.text, editor.seed, userTitleInUse: memory.hasUserTitle);
    YoinHaptics.confirm();
    final Future<bool>? write;
    switch (outcome) {
      case TitleUnchanged():
        write = null;
      case TitleRestore():
        _held = memory.yoinTitle != null
            ? _Shown(memory.yoinTitle!, MemoryTitleKind.ai)
            : _Shown(memory.albumName, MemoryTitleKind.album);
        write = editing.onRestore(memory);
      case TitleSave(:final title):
        _held = _Shown(title, MemoryTitleKind.user);
        write = editing.onSave(memory, title);
    }
    editor.end();
    if (write != null && !await write && mounted) setState(() => _held = null);
  }

  @override
  Widget build(BuildContext context) {
    final memory = widget.memory;
    final open = _open;
    final kind = memory.memoryTitleKind;
    if (open) {
      _editKind ??= kind;
    } else {
      _editKind = null;
    }
    final face = open ? _Editing(_editKind!) : (_held ?? _Shown(memory.memoryTitle, kind));
    final previous = _face;
    if (previous != null && previous != face) {
      final draft = widget.editing?.editor.draft.text ?? '';
      if (!_seamless(previous, face, draft)) {
        _outgoing = previous is _Editing ? _Shown(draft.trim().isEmpty ? '' : draft, previous.kind) : previous;
        _faceKey++;
        if (MediaQuery.disableAnimationsOf(context)) {
          _fade.snapTo(1);
          _outgoing = null;
        } else {
          _fade.snapTo(0);
          _fade.animateTo(1);
        }
      }
    }
    _face = face;
    final align = widget.centred ? Alignment.topCenter : AlignmentDirectional.topStart;
    final incoming = KeyedSubtree(key: ValueKey(_faceKey), child: _build(face));
    final outgoing = _outgoing;
    return SpringHeight(
      heightKey: _faceKey,
      child: AnimatedBuilder(
        animation: _fade.animation,
        builder: (context, _) {
          // A spring settles within its tolerance, short of 1: done is close enough.
          final t = _fade.value >= 0.995 ? 1.0 : _fade.value.clamp(0.0, 1.0);
          if (t >= 1 && outgoing != null) {
            WidgetsBinding.instance.addPostFrameCallback((_) {
              if (mounted && identical(_outgoing, outgoing)) setState(() => _outgoing = null);
            });
          }
          return Stack(
            clipBehavior: Clip.none,
            alignment: align,
            children: [
              if (outgoing != null && t < 1)
                Positioned(
                  left: 0,
                  right: 0,
                  top: 0,
                  child: IgnorePointer(
                    child: ExcludeSemantics(
                      child: Opacity(opacity: 1 - t, child: _text(outgoing as _Shown, null)),
                    ),
                  ),
                ),
              Opacity(opacity: outgoing == null ? 1 : t, child: incoming),
            ],
          );
        },
      ),
    );
  }

  Widget _build(_Face face) => switch (face) {
    _Shown() => _text(face, widget.enabled && widget.editing != null ? _begin : null),
    _Editing(:final kind) => _field(kind),
  };

  void _begin() {
    YoinHaptics.click();
    widget.editing?.editor.begin(_key, memoryTitleDraftSeed(widget.memory));
  }

  Widget _text(_Shown face, VoidCallback? onTap) {
    final strings = MemoriesStrings.of(context);
    final lines = widget.maxLines?.call(face.kind);
    final text = Text(
      face.text,
      style: widget.style(face.kind),
      textAlign: widget.centred ? TextAlign.center : TextAlign.start,
      maxLines: lines,
      overflow: lines == null ? null : TextOverflow.fade,
    );
    final heading = Semantics(header: true, child: text);
    if (onTap == null) return SizedBox(width: double.infinity, child: heading);
    return Semantics(
      onTapHint: strings.cdEditTitle,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: SizedBox(width: double.infinity, child: heading),
      ),
    );
  }

  Widget _field(MemoryTitleKind kind) {
    final strings = MemoriesStrings.of(context);
    final editor = widget.editing!.editor;
    final colors = Theme.of(context).colorScheme;
    final style = widget.style(kind).copyWith(color: colors.onSurface);
    return _TitleField(
      editor: editor,
      style: style,
      hint: memoryTitlePlaceholder(widget.memory),
      hintColor: colors.onSurfaceVariant.withValues(alpha: MemoryTitleTokens.placeholderAlpha),
      cursor: widget.tones.ink,
      centred: widget.centred,
      label: strings.cdTitleField,
      onSave: _commit,
    );
  }
}

/// The field: the title's own typography and colour, the album ink's
/// cursor, no box. Focus (and so the keyboard) comes with it; Done saves.
class _TitleField extends StatefulWidget {
  const _TitleField({
    required this.editor,
    required this.style,
    required this.hint,
    required this.hintColor,
    required this.cursor,
    required this.centred,
    required this.label,
    required this.onSave,
  });

  final MemoryTitleEditor editor;
  final TextStyle style;
  final String hint;
  final Color hintColor;
  final Color cursor;
  final bool centred;
  final String label;
  final VoidCallback onSave;

  @override
  State<_TitleField> createState() => _TitleFieldState();
}

class _TitleFieldState extends State<_TitleField> {
  final FocusNode _focus = FocusNode();
  void _save() => widget.onSave();

  @override
  void initState() {
    super.initState();
    widget.editor.saveAction = _save;
  }

  @override
  void didUpdateWidget(_TitleField old) {
    super.didUpdateWidget(old);
    widget.editor.saveAction = _save;
  }

  @override
  void dispose() {
    if (widget.editor.saveAction == _save) widget.editor.saveAction = null;
    _focus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Semantics(
    label: widget.label,
    child: TextField(
      controller: widget.editor.draft,
      focusNode: _focus,
      autofocus: true,
      maxLines: null,
      style: widget.style,
      textAlign: widget.centred ? TextAlign.center : TextAlign.start,
      cursorColor: widget.cursor,
      textCapitalization: TextCapitalization.sentences,
      textInputAction: TextInputAction.done,
      inputFormatters: const [MemoryTitleInputFormatter()],
      onSubmitted: (_) => widget.onSave(),
      // The row under the field rides above the keyboard with it.
      scrollPadding: const EdgeInsets.only(bottom: MemoryTitleTokens.scrollClearance),
      decoration: InputDecoration.collapsed(
        hintText: widget.hint,
        hintStyle: widget.style.copyWith(color: widget.hintColor),
      ),
    ),
  );
}

// ---------------------------------------------------------------- the row

/// The quiet row under the field while [memory]'s title is open on
/// [surface]: [Restore] (only over a user title with Yoin's own under it),
/// Cancel, Save. It unfolds and folds on the spatial spring, fading on the
/// effects spring, like the diary writer's.
class MemoryTitleEditRow extends StatelessWidget {
  const MemoryTitleEditRow({
    super.key,
    required this.memory,
    required this.surface,
    required this.editing,
    required this.tones,
    required this.centred,
  });

  final MemoryEntry memory;
  final MemoryTitleSurface surface;
  final MemoryTitleEditing? editing;
  final MemoryPaletteTones tones;
  final bool centred;

  @override
  Widget build(BuildContext context) {
    final editing = this.editing;
    if (editing == null) return const SizedBox.shrink();
    final editor = editing.editor;
    return ListenableBuilder(
      listenable: editor,
      builder: (context, _) {
        final open = editor.isEditingKey(memoryTitleKey(surface, memory));
        return MemorySpringFold(visible: open, child: _row(context, editing));
      },
    );
  }

  Widget _row(BuildContext context, MemoryTitleEditing editing) {
    final strings = MemoriesStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final label = gsf(14, weight: 600, height: 1, letterSpacing: 0);
    final editor = editing.editor;
    return Padding(
      padding: const EdgeInsets.only(top: MemoryTitleTokens.rowTop),
      child: Row(
        mainAxisAlignment: centred ? MainAxisAlignment.center : MainAxisAlignment.end,
        children: [
          if (memoryTitleCanRestore(memory))
            TextButton(
              onPressed: () {
                YoinHaptics.confirm();
                editing.onRestore(memory);
                editor.end();
              },
              child: Text(strings.titleRestoreAi, style: label.copyWith(color: colors.onSurfaceVariant)),
            ),
          Semantics(
            button: true,
            label: strings.cdDiscardTitle,
            excludeSemantics: true,
            child: TextButton(
              onPressed: editor.end,
              child: Text(strings.titleCancel, style: label.copyWith(color: colors.onSurfaceVariant)),
            ),
          ),
          const SizedBox(width: 8),
          MemoryJournalSavePill(
            label: strings.titleSave,
            semanticsLabel: strings.cdSaveTitle,
            tones: tones,
            // The same save as the keyboard's Done.
            onTap: editor.requestSave,
          ),
        ],
      ),
    );
  }
}

/// The diary writer's Save: the album-coloured pill, 36 high.
class MemoryJournalSavePill extends StatelessWidget {
  const MemoryJournalSavePill({
    super.key,
    required this.label,
    required this.semanticsLabel,
    required this.tones,
    required this.onTap,
  });

  final String label;
  final String semanticsLabel;
  final MemoryPaletteTones tones;

  /// Null: dimmed and inert (nothing to save yet).
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => Semantics(
    button: true,
    label: semanticsLabel,
    excludeSemantics: true,
    child: Opacity(
      opacity: onTap == null ? 0.38 : 1,
      child: SizedBox(
        height: MemoryTitleTokens.pillHeight,
        child: Material(
          color: tones.button,
          shape: const StadiumBorder(),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Center(
                widthFactor: 1,
                child: Text(
                  label,
                  style: gsf(14, weight: 600, height: 1, letterSpacing: 0).copyWith(color: tones.onButton),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

// ---------------------------------------------------------------- spring helpers

/// Unfolds [child] downward on the spatial spring and folds it away again,
/// fading on the effects spring; the last child stays while it folds.
class MemorySpringFold extends StatefulWidget {
  const MemorySpringFold({super.key, required this.visible, required this.child});

  final bool visible;
  final Widget child;

  @override
  State<MemorySpringFold> createState() => _MemorySpringFoldState();
}

class _MemorySpringFoldState extends State<MemorySpringFold> with TickerProviderStateMixin {
  late final SpringValue _size = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void initState() {
    super.initState();
    _size;
    _alpha;
  }

  @override
  void didUpdateWidget(MemorySpringFold old) {
    super.didUpdateWidget(old);
    final target = widget.visible ? 1.0 : 0.0;
    if (MediaQuery.disableAnimationsOf(context)) {
      _size.snapTo(target);
      _alpha.snapTo(target);
    } else {
      _size.animateTo(target);
      _alpha.animateTo(target);
    }
  }

  @override
  void dispose() {
    _size.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: Listenable.merge([_size.animation, _alpha.animation]),
    child: widget.child,
    builder: (context, child) {
      final size = _size.value.clamp(0.0, 1.0);
      if (size <= 0.001 && !widget.visible) return const SizedBox.shrink();
      return IgnorePointer(
        ignoring: !widget.visible,
        child: ClipRect(
          child: Align(
            alignment: Alignment.topCenter,
            heightFactor: size,
            child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
          ),
        ),
      );
    },
  );
}

/// [child] at [visible]'s alpha, on the effects spring.
class MemorySpringOpacity extends StatefulWidget {
  const MemorySpringOpacity({super.key, required this.visible, required this.child});

  final bool visible;
  final Widget child;

  @override
  State<MemorySpringOpacity> createState() => _MemorySpringOpacityState();
}

class _MemorySpringOpacityState extends State<MemorySpringOpacity> with SingleTickerProviderStateMixin {
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.visible ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void initState() {
    super.initState();
    _alpha;
  }

  @override
  void didUpdateWidget(MemorySpringOpacity old) {
    super.didUpdateWidget(old);
    final target = widget.visible ? 1.0 : 0.0;
    MediaQuery.disableAnimationsOf(context) ? _alpha.snapTo(target) : _alpha.animateTo(target);
  }

  @override
  void dispose() {
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _alpha.animation,
    child: widget.child,
    builder: (context, child) => IgnorePointer(
      ignoring: !widget.visible,
      child: Opacity(opacity: _alpha.value.clamp(0.0, 1.0), child: child),
    ),
  );
}

/// The keyboard lift of a surface that doesn't scroll: rises by [lift] px on
/// the spatial spring (and back down when the keyboard goes).
class MemoryTitleLift extends StatefulWidget {
  const MemoryTitleLift({super.key, required this.lift, required this.child, this.mirror});

  final double lift;
  final Widget child;

  /// Told the lift as it moves: a part of the same surface drawn outside
  /// [child] (the card's flying cover) rides it too.
  final ValueNotifier<double>? mirror;

  @override
  State<MemoryTitleLift> createState() => _MemoryTitleLiftState();
}

class _MemoryTitleLiftState extends State<MemoryTitleLift> with SingleTickerProviderStateMixin {
  late final SpringValue _lift = SpringValue(vsync: this, initial: widget.lift, spring: YoinSprings.defaultSpatial);

  @override
  void initState() {
    super.initState();
    _lift.animation.addListener(_tell);
    _tell();
  }

  void _tell() {
    final mirror = widget.mirror;
    if (mirror == null || mirror.value == _lift.value) return;
    // A snap lands mid-build: the cover hears it once the frame is built.
    if (SchedulerBinding.instance.schedulerPhase == SchedulerPhase.persistentCallbacks) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) _tell();
      });
    } else {
      mirror.value = _lift.value;
    }
  }

  @override
  void didUpdateWidget(MemoryTitleLift old) {
    super.didUpdateWidget(old);
    MediaQuery.disableAnimationsOf(context) ? _lift.snapTo(widget.lift) : _lift.animateTo(widget.lift);
    if (!identical(old.mirror, widget.mirror)) _tell();
  }

  @override
  void dispose() {
    _lift.animation.removeListener(_tell);
    _lift.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _lift.animation,
    child: widget.child,
    builder: (context, child) => Transform.translate(offset: Offset(0, -_lift.value), child: child),
  );
}
