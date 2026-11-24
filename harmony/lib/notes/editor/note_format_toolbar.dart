/// The editor's formatting row (Android ui/notes/editor/NoteFormatToolbar.kt):
/// @, B, I, H, list, to-do. Each is an M3 Expressive icon toggle (its shape
/// morphs on press, IconButtonDefaults.toggleableShapes) that reads as
/// pressed where the caret is (bold words, a list line). Bold / italic /
/// heading are letters, as in the prototype; @ and the to-do box are Yoin
/// Symbols; the list is a bullet (Yoin Symbols has no list glyph yet).
library;

import 'package:flutter/material.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../../data/notes/note_editor_text.dart';
import '../../nowplaying/haptics.dart';
import '../../theme/tokens.dart';
import '../../ui/component/expressive/expressive_shapes.dart';
import '../../ui/component/expressive/expressive_surface_button.dart';
import '../notes_strings.dart';
import 'note_editor_controller.dart';

typedef _E = NoteEditorTokens;

const _toolOrder = [
  NoteTool.mention,
  NoteTool.bold,
  NoteTool.italic,
  NoteTool.heading,
  NoteTool.bullet,
  NoteTool.checklist,
];

/// IconButtonDefaults.toggleableShapes() at the row's size: round, the small pressed corner, checked square-ish.
final InteractionShapes _toggleShapes = InteractionShapes(
  rest: CornerRadii.full,
  pressed: CornerRadii.all(M3eIconButtonTokens.pressedCornerFor(_E.toolSize)),
  checked: const CornerRadii.all(M3eShapeTokens.cornerMedium),
);

class NoteFormatToolbar extends StatelessWidget {
  const NoteFormatToolbar({super.key, required this.controller, this.contentColor, this.checkedColor});

  final NoteEditorController controller;

  /// The glyphs' colour (default onSurfaceVariant) and a pressed tool's (default primary).
  final Color? contentColor;
  final Color? checkedColor;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: controller,
    builder: (context, _) {
      final colors = Theme.of(context).colorScheme;
      final strings = NotesStrings.of(context);
      final active = controller.activeTools;
      // Part of the editor: a click here keeps the field's focus and selection.
      return TextFieldTapRegion(
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final (i, tool) in _toolOrder.indexed) ...[
              if (i > 0) const SizedBox(width: _E.toolGap),
              ExpressiveSurfaceButton(
                shapes: _toggleShapes,
                height: _E.toolSize,
                minWidth: _E.toolSize,
                color: Colors.transparent,
                contentColor: active.contains(tool)
                    ? (checkedColor ?? colors.primary)
                    : (contentColor ?? colors.onSurfaceVariant),
                checked: active.contains(tool),
                semanticLabel: switch (tool) {
                  NoteTool.bold => strings.toolBold,
                  NoteTool.italic => strings.toolItalic,
                  NoteTool.heading => strings.toolHeading,
                  NoteTool.bullet => strings.toolBullet,
                  NoteTool.checklist => strings.toolChecklist,
                  NoteTool.mention => strings.toolMention,
                },
                semanticValue: null,
                onTap: () {
                  YoinHaptics.tick();
                  controller.apply(tool);
                },
                child: _ToolGlyph(tool),
              ),
            ],
          ],
        ),
      );
    },
  );
}

class _ToolGlyph extends StatelessWidget {
  const _ToolGlyph(this.tool);

  final NoteTool tool;

  @override
  Widget build(BuildContext context) {
    final base = Theme.of(context).textTheme.titleMedium!;
    TextStyle letter(int weight, {bool italic = false, double size = _E.toolLetter}) => base.copyWith(
      fontSize: size,
      fontWeight: FontWeight.values.firstWhere((w) => w.value == weight),
      fontVariations: [FontVariation.weight(weight.toDouble())],
      fontStyle: italic ? FontStyle.italic : FontStyle.normal,
      color: IconTheme.of(context).color,
    );
    // Formatting glyphs, not words.
    return ExcludeSemantics(
      child: switch (tool) {
        NoteTool.mention => const Icon(YoinSymbols.at, size: _E.toolGlyph),
        NoteTool.checklist => const Icon(YoinSymbols.checkBox, size: _E.toolGlyph),
        NoteTool.bold => Text('B', style: letter(900)),
        NoteTool.italic => Text('I', style: letter(600, italic: true)),
        NoteTool.heading => Text('H', style: letter(700)),
        NoteTool.bullet => Text('•', style: letter(900, size: _E.toolBulletLetter)),
      },
    );
  }
}
