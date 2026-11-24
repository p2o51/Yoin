part of 'album_scrapbook_page.dart';

/*
 * The scrapbook's album notes (Android AlbumScrapbookNotes.kt, notes revamp phase B, prototype scenario 5).
 *
 * Every album note is a sticky under the cover and review, oldest first: secondaryContainer paper with a
 * folded bottom-right corner, its words drawn from the stored markup. A dashed sticky writes the next one in
 * place (so does a tap on bare paper). A sticky opens in place into an editor; clearing it and saving
 * deletes it, with Undo.
 */

/// D3's sticky width: [ScrapbookLayout.stickyWidthFactor] of the space it is given, at most
/// [ScrapbookLayout.stickyMaxWidth], on the start side.
class _StickyWidth extends StatelessWidget {
  const _StickyWidth({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) => Align(
      alignment: AlignmentDirectional.topStart,
      child: SizedBox(
        width: math.min(constraints.maxWidth * ScrapbookLayout.stickyWidthFactor, ScrapbookLayout.stickyMaxWidth),
        child: child,
      ),
    ),
  );
}

/// The album's stickies and the dashed one (or an editor in place of one). The pile follows its content on
/// the spatial spring (animateContentSize).
class _StickyPile extends StatelessWidget {
  const _StickyPile({required this.stickies, required this.editing, required this.onSave});

  final List<ScrapSticky> stickies;
  final StickyEditing editing;
  final void Function(String open, String text) onSave;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: editing,
    builder: (context, _) {
      final open = editing.open;
      // The sticky being edited went (deleted on another device): the pile is back to rest.
      if (open != null && open != StickyEditing.newSticky && !stickies.any((s) => s.noteId == open)) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (editing.open == open) editing.close();
        });
      }
      final children = <Widget>[
        for (final sticky in stickies)
          if (open == sticky.noteId)
            _StickyEditor(
              key: ValueKey('edit:${sticky.noteId}'),
              text: editing.text,
              tilt: sticky.tilt,
              onSave: (text) => onSave(sticky.noteId, text),
              onCancel: editing.close,
            )
          else
            _Sticky(key: ValueKey(sticky.noteId), sticky: sticky, onTap: () => editing.startEdit(sticky)),
        if (open == StickyEditing.newSticky)
          _StickyEditor(
            key: const ValueKey('edit:new'),
            text: '',
            tilt: ScrapbookLayout.stickyNewTilt,
            onSave: (text) => onSave(StickyEditing.newSticky, text),
            onCancel: editing.close,
          )
        else if (open == null)
          _DashedSticky(onTap: editing.startNew),
      ];
      return SpringHeight(
        heightKey: (open, Object.hashAll(stickies)),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final (i, child) in children.indexed) ...[
              if (i > 0) const SizedBox(height: ScrapbookLayout.stickyGap),
              child,
            ],
          ],
        ),
      );
    },
  );
}

/// One album note: secondaryContainer paper with a folded corner, its words drawn from the stored markup.
/// Told apart by paper and fold alone: no label, no rail (the rail is the track notes').
class _Sticky extends StatelessWidget {
  const _Sticky({super.key, required this.sticky, required this.onTap});

  final ScrapSticky sticky;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    final strings = DetailStrings.of(context);
    final words = NoteMarkup.plainText(sticky.text);
    void tap() {
      YoinHaptics.tick();
      onTap();
    }

    return _ScrapLayer(
      tilt: sticky.tilt,
      depth: ScrapbookLayout.stickyDepth,
      child: _scrapFade(
        Semantics(
          button: true,
          label: strings.scrapCdAlbumNote(words),
          onTapHint: strings.stickyEditCd(words),
          onTap: tap,
          excludeSemantics: true,
          child: _StickyPaper(
            colors: colors,
            child: InkWell(
              onTap: tap,
              child: Padding(
                padding: ScrapbookLayout.stickyPadding,
                child: SizedBox(
                  width: double.infinity,
                  child: NoteMarkupText(
                    sticky.text,
                    style: _userText(context)?.copyWith(fontSize: ScrapbookLayout.stickyText, color: colors.onSticky),
                    mentionColor: colors.onSticky,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// "Write an album note": a dashed sticky outline with the note glyph.
class _DashedSticky extends StatelessWidget {
  const _DashedSticky({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    void tap() {
      YoinHaptics.tick();
      onTap();
    }

    return Semantics(
      button: true,
      label: DetailStrings.of(context).stickyAdd,
      onTap: tap,
      excludeSemantics: true,
      child: ClipPath(
        clipper: const _StickyClipper(),
        child: Material(
          type: MaterialType.transparency,
          child: InkWell(
            onTap: tap,
            child: CustomPaint(
              painter: _DashedStickyOutline(colors.outline),
              child: SizedBox.fromSize(
                size: ScrapbookLayout.stickyDashed,
                child: Icon(YoinSymbols.note, size: ScrapbookLayout.stickyDashedGlyph, color: colors.muted),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// A sticky being written: a field on the sticky's paper, then Cancel and Save.
class _StickyEditor extends StatefulWidget {
  const _StickyEditor({
    super.key,
    required this.text,
    required this.tilt,
    required this.onSave,
    required this.onCancel,
  });

  final String text;
  final double tilt;
  final ValueChanged<String> onSave;
  final VoidCallback onCancel;

  @override
  State<_StickyEditor> createState() => _StickyEditorState();
}

class _StickyEditorState extends State<_StickyEditor> {
  late final TextEditingController _field = TextEditingController(text: widget.text)
    ..selection = TextSelection.collapsed(offset: widget.text.length);
  final FocusNode _focus = FocusNode();

  @override
  void initState() {
    super.initState();
    // An opened sticky takes the focus (and the keyboard comes up).
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _focus.requestFocus();
    });
  }

  @override
  void dispose() {
    _field.dispose();
    _focus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = _ScrapScope.of(context).colors;
    final strings = DetailStrings.of(context);
    final style = _userText(context)?.copyWith(fontSize: ScrapbookLayout.stickyText, color: colors.onSticky);
    return _ScrapLayer(
      tilt: widget.tilt * ScrapbookLayout.stickyEditorLean,
      depth: ScrapbookLayout.stickyDepth,
      child: _StickyPaper(
        colors: colors,
        child: Padding(
          padding: ScrapbookLayout.stickyEditorPadding,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: _field,
                focusNode: _focus,
                minLines: ScrapbookLayout.stickyMinLines,
                maxLines: ScrapbookLayout.stickyMaxLines,
                keyboardType: TextInputType.multiline,
                textCapitalization: TextCapitalization.sentences,
                style: style,
                cursorColor: colors.onSticky,
                decoration: InputDecoration.collapsed(
                  hintText: strings.stickyPlaceholder,
                  hintStyle: style?.copyWith(color: colors.onSticky.withValues(alpha: 0.6)),
                ),
              ),
              const SizedBox(height: 6),
              Row(
                mainAxisAlignment: MainAxisAlignment.end,
                children: [
                  TextButton(
                    style: TextButton.styleFrom(foregroundColor: colors.onSticky),
                    onPressed: widget.onCancel,
                    child: Text(strings.scrapCancel),
                  ),
                  FilledButton.tonal(
                    style: FilledButton.styleFrom(backgroundColor: colors.stickyFold, foregroundColor: colors.onSticky),
                    onPressed: () => widget.onSave(_field.text),
                    child: Text(strings.scrapSave),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The sticky's paper: card corners with the bottom-right corner folded over.
class _StickyPaper extends StatelessWidget {
  const _StickyPaper({required this.colors, required this.child});

  final ScrapbookColors colors;
  final Widget child;

  @override
  Widget build(BuildContext context) => ClipPath(
    clipper: const _StickyClipper(),
    child: ColoredBox(
      color: colors.sticky,
      child: CustomPaint(
        painter: _StickyFold(colors.stickyFold),
        child: Material(type: MaterialType.transparency, child: child),
      ),
    ),
  );
}

/// Card corners (16 continuous) with the bottom-right corner cut off on the diagonal where it folds over
/// (StickyShape). Mirrored never: the fold is a corner of the paper, not of the reading direction.
Path _stickyPath(Size size) {
  final body = RoundedSuperellipseBorder(
    borderRadius: BorderRadius.circular(ShapeTokens.card),
  ).getOuterPath(Offset.zero & size);
  const f = ScrapbookLayout.stickyFold;
  final w = size.width, h = size.height;
  // Everything past the fold line, with margin outside the box.
  final corner = Path()
    ..moveTo(w - f, h)
    ..lineTo(w, h - f)
    ..lineTo(w + f, h - f)
    ..lineTo(w + f, h + f)
    ..lineTo(w - f, h + f)
    ..close();
  return Path.combine(PathOperation.difference, body, corner);
}

class _StickyClipper extends CustomClipper<Path> {
  const _StickyClipper();

  @override
  Path getClip(Size size) => _stickyPath(size);

  @override
  bool shouldReclip(_StickyClipper oldClipper) => false;
}

/// The folded-over flap: the triangle on the paper's side of the fold line, its inner corner rounded.
class _StickyFold extends CustomPainter {
  const _StickyFold(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    const f = ScrapbookLayout.stickyFold, r = ScrapbookLayout.stickyFoldRadius;
    final x = size.width - f, y = size.height - f;
    final flap = Path()
      ..moveTo(x, size.height)
      ..lineTo(x, y + r)
      ..quadraticBezierTo(x, y, x + r, y)
      ..lineTo(size.width, y)
      ..close();
    canvas.drawPath(flap, Paint()..color = color);
  }

  @override
  bool shouldRepaint(_StickyFold old) => old.color != color;
}

/// The dashed sticky's outline: the sticky's shape, 1.5 wide, 5 on / 5 off.
class _DashedStickyOutline extends CustomPainter {
  const _DashedStickyOutline(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    const width = 1.5, dash = 5.0, gap = 5.0;
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = width;
    for (final metric in _stickyPath(size).computeMetrics()) {
      for (var d = 0.0; d < metric.length; d += dash + gap) {
        canvas.drawPath(metric.extractPath(d, math.min(d + dash, metric.length)), paint);
      }
    }
  }

  @override
  bool shouldRepaint(_DashedStickyOutline old) => old.color != color;
}
