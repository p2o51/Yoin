/// The album About sheet (owner + Carbon 2026-11-25; Android AlbumAboutSheet.kt), opened from the About
/// card on the scrapbook (or its dashed ✦ circle, or its question card): the credits as a dashed receipt
/// (Now Playing's About style), the whole paragraph with Regenerate (undoable), the questions asked about
/// the album, and a question field ("Ask ‹service›"). Everything here is the AI's, in the tertiary role like
/// the card; the byline names the AI service in use.
///
/// When the AI knew nothing ("known-empty"), the paragraph's place offers "Generate with ‹service›"; a try
/// that comes back with nothing, or fails, is said in a quiet line under it ("‹service› had nothing new").
library;

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../data/ai/ai_settings.dart';
import '../data/albumabout/album_about_models.dart';
import '../data/albumabout/album_credits.dart';
import '../data/model/model.dart';
import '../nowplaying/about_widgets.dart' show ThinkingDots, markdownBoldSpan;
import '../settings/ai_settings_screen.dart' show AiSpringSwap;
import '../settings/settings_components.dart' show SpringReveal;
import '../shell/bar_suppression.dart';
import '../ui/component/meta_line.dart' show albumTitleWithoutVersion;
import 'album_scrapbook.dart';
import 'album_scrapbook_editor.dart';
import 'album_scrapbook_page.dart' show ScrapbookColors, albumCreditsLineFor;
import 'album_scrapbook_sheet_model.dart';
import 'detail_strings.dart';

/// The sheet's numbers (AlbumAboutSheet.kt AboutSheetGutter, ReceiptLabelWidth …).
abstract final class AlbumAboutSheetMetrics {
  static const double gutter = 24;
  static const double receiptLabelWidth = 88;
  static const double receiptRowPadding = 10;
  static const double receiptDash = 4;
  static const double cardCorner = 20;
  static const double askFieldCorner = 24;
  static const double fieldCorner = 16;
  static const double sendButton = 44;
  static const double buttonMinHeight = 48;
  static const double sectionGap = 14;
  static const Duration notice = Duration(seconds: 4);
  static const Duration noticeWithUndo = Duration(seconds: 7);
}

/// Which album pages have the About sheet open (the page leaves the AI's outcomes to it meanwhile).
abstract final class AlbumAboutSheetScope {
  static final Map<MediaId, int> _open = {};

  static bool isOpen(MediaId albumId) => (_open[albumId] ?? 0) > 0;
}

/// What a try at the album's About came to, naming the service in use ("Qwen had nothing new", 千问没有新的内容).
String albumAboutFailureText(DetailStrings strings, ScrapFailure failure, AiProvider provider) => switch (failure) {
  ScrapFailure.keyMissing => strings.aiKeyMissing(provider),
  ScrapFailure.failed => strings.aiFailed(provider),
  ScrapFailure.nothingNew => strings.aiNothingNew(provider),
};

/// Opens the album's About sheet.
Future<void> showAlbumAboutSheet(BuildContext context, {required Album album, required ScrapbookColors colors}) =>
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerLow,
      builder: (_) => ChromeBarSuppression(
        child: AlbumAboutSheet(album: album, colors: colors),
      ),
    );

/// What the sheet shows of the page: the About piece and the credits beside the cover.
({ScrapAlbumAbout? about, List<AlbumCreditItem> credits}) albumAboutSheetContent(List<ScrapPiece>? pieces) {
  ScrapAlbumAbout? about;
  var credits = const <AlbumCreditItem>[];
  for (final piece in pieces ?? const <ScrapPiece>[]) {
    if (piece is ScrapAlbumAbout) about = piece;
    if (piece is ScrapOpening) credits = piece.credits;
  }
  return (about: about, credits: credits);
}

class AlbumAboutSheet extends ConsumerStatefulWidget {
  const AlbumAboutSheet({super.key, required this.album, required this.colors});

  final Album album;
  final ScrapbookColors colors;

  @override
  ConsumerState<AlbumAboutSheet> createState() => _AlbumAboutSheetState();
}

/// A notice at the sheet's foot ("Regenerated · Undo", a failed question).
final class _Notice {
  const _Notice(this.message, {this.icon, this.undo});
  final String message;
  final IconData? icon;
  final ScrapEdit? undo;
}

class _AlbumAboutSheetState extends ConsumerState<AlbumAboutSheet> {
  StreamSubscription<ScrapEvent>? _events;
  _Notice? _notice;
  Timer? _noticeTimer;

  /// What came of writing the About again ("had nothing new", "couldn't answer"), said in place under it.
  ScrapFailure? _aboutFailure;

  AlbumScrapbookEditor get _editor => ref.read(albumScrapbookEditorProvider(widget.album.id).notifier);

  @override
  void initState() {
    super.initState();
    final open = AlbumAboutSheetScope._open;
    open[widget.album.id] = (open[widget.album.id] ?? 0) + 1;
    _events = _editor.events.listen(_onEvent);
  }

  @override
  void dispose() {
    final open = AlbumAboutSheetScope._open;
    open[widget.album.id] = (open[widget.album.id] ?? 1) - 1;
    _events?.cancel();
    _noticeTimer?.cancel();
    super.dispose();
  }

  void _onEvent(ScrapEvent event) {
    if (!mounted) return;
    final strings = DetailStrings.of(context);
    switch (event) {
      case ScrapRegenerated(:final restore):
        _say(_Notice(strings.scrapRegenerated, icon: YoinSymbols.sparkle, undo: restore));
      case ScrapFailed(:final failure):
        _say(_Notice(albumAboutFailureText(strings, failure, _provider), icon: YoinSymbols.error));
      case ScrapAlbumAboutFailed(:final failure):
        setState(() => _aboutFailure = failure);
    }
  }

  AiProvider get _provider => ref.read(aiSettingsProvider).value?.provider ?? AiProvider.gemini;

  void _say(_Notice notice) {
    _noticeTimer?.cancel();
    setState(() => _notice = notice);
    _noticeTimer = Timer(
      notice.undo != null ? AlbumAboutSheetMetrics.noticeWithUndo : AlbumAboutSheetMetrics.notice,
      () {
        if (mounted) setState(() => _notice = null);
      },
    );
  }

  void _edit(ScrapEdit edit) {
    if (edit is ScrapRegenerateAlbumAboutEdit) setState(() => _aboutFailure = null);
    _editor.edit(edit);
  }

  @override
  Widget build(BuildContext context) {
    final content = albumAboutSheetContent(ref.watch(albumScrapbookProvider(widget.album.id)));
    final gemini = ref.watch(albumScrapbookEditorProvider(widget.album.id));
    final provider = ref.watch(aiSettingsProvider).value?.provider ?? AiProvider.gemini;
    final notice = _notice;
    final strings = DetailStrings.of(context);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Flexible(
            child: AlbumAboutSheetBody(
              albumName: albumTitleWithoutVersion(widget.album.name, widget.album.version),
              about: content.about,
              credits: content.credits,
              colors: widget.colors,
              gemini: gemini,
              provider: provider,
              failure: gemini.albumAbout ? null : _aboutFailure,
              onEdit: _edit,
            ),
          ),
          SpringReveal(
            visible: notice != null,
            child: notice == null
                ? null
                : _NoticeBar(
                    notice: notice,
                    undoLabel: strings.scrapUndo,
                    onUndo: () {
                      final undo = notice.undo;
                      setState(() => _notice = null);
                      if (undo != null) _edit(undo);
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

/// The sheet's content apart from the sheet itself (so widget tests and screenshots can draw it).
class AlbumAboutSheetBody extends StatelessWidget {
  const AlbumAboutSheetBody({
    super.key,
    required this.albumName,
    required this.about,
    required this.credits,
    required this.colors,
    required this.gemini,
    required this.provider,
    required this.onEdit,
    this.failure,
  });

  final String albumName;
  final ScrapAlbumAbout? about;
  final List<AlbumCreditItem> credits;
  final ScrapbookColors colors;
  final ScrapGemini gemini;
  final AiProvider provider;
  final ValueChanged<ScrapEdit> onEdit;

  /// The last try at writing the About again came back with nothing or failed.
  final ScrapFailure? failure;

  @override
  Widget build(BuildContext context) {
    final strings = DetailStrings.of(context);
    final theme = Theme.of(context);
    final about = this.about;
    final text = about?.text;
    final pending = about?.pending ?? false;
    final regenerating = gemini.albumAbout;
    final notice = failure == null ? null : albumAboutFailureText(strings, failure!, provider);
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: AlbumAboutSheetMetrics.gutter),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(YoinSymbols.sparkle, size: 14, color: colors.aboutMark),
                  const SizedBox(width: 6),
                  Text(
                    strings.scrapAbout,
                    style: theme.textTheme.labelLarge?.copyWith(fontWeight: FontWeight.w600, color: colors.aboutMark),
                  ),
                ],
              ),
              Padding(
                padding: const EdgeInsets.only(top: 4, bottom: 8),
                child: Text(
                  albumName,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.titleLarge?.copyWith(
                    fontWeight: FontWeight.w600,
                    color: theme.colorScheme.onSurface,
                  ),
                ),
              ),
            ],
          ),
        ),
        Flexible(
          child: SingleChildScrollView(
            padding: EdgeInsets.fromLTRB(
              AlbumAboutSheetMetrics.gutter,
              0,
              AlbumAboutSheetMetrics.gutter,
              24 + MediaQuery.paddingOf(context).bottom,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _CreditsReceipt(credits: credits, colors: colors),
                if (text == null && !regenerating && !pending) ...[
                  // Asked before and the AI knew nothing: no empty card, just the way to ask it again.
                  _GenerateAbout(
                    label: strings.aiGenerateWith(provider),
                    colors: colors,
                    onGenerate: () => onEdit(const ScrapRegenerateAlbumAboutEdit()),
                  ),
                  _AboutNotice(text: notice),
                ] else ...[
                  _AboutParagraph(
                    text: text,
                    busy: regenerating || text == null,
                    colors: colors,
                    byline: strings.aiByline(provider),
                    regenerateLabel: strings.scrapRegenerate,
                    onRegenerate: regenerating ? null : () => onEdit(const ScrapRegenerateAlbumAboutEdit()),
                  ),
                  _AboutNotice(text: notice),
                ],
                for (final question in gemini.albumAsking)
                  _AskAnswerCard(
                    key: ValueKey('asking:$question'),
                    question: question,
                    colors: colors,
                    byline: strings.aiByline(provider),
                  ),
                for (final ask in about?.asks ?? const <AlbumAboutAsk>[])
                  _AskAnswerCard(
                    key: ValueKey('ask:${ask.question}'),
                    question: ask.question,
                    title: ask.title,
                    answer: ask.answer,
                    colors: colors,
                    byline: strings.aiByline(provider),
                  ),
                _AskAlbum(
                  hint: strings.aiAskProvider(provider),
                  colors: colors,
                  onAsk: (question) => onEdit(ScrapAskAlbumEdit(question)),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// The credits one per line, label left and value right, a dashed rule between (Now Playing's receipt).
class _CreditsReceipt extends StatelessWidget {
  const _CreditsReceipt({required this.credits, required this.colors});

  final List<AlbumCreditItem> credits;
  final ScrapbookColors colors;

  @override
  Widget build(BuildContext context) {
    final rows = albumCreditsLineFor(credits, DetailStrings.of(context))?.rows ?? const <AlbumCreditRow>[];
    if (rows.isEmpty) return const SizedBox.shrink();
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 8, bottom: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final (i, row) in rows.indexed) ...[
            if (i > 0)
              CustomPaint(
                size: const Size.fromHeight(1),
                painter: _DashedRule(colors.aboutMark.withValues(alpha: 0.45)),
              ),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: AlbumAboutSheetMetrics.receiptRowPadding),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: AlbumAboutSheetMetrics.receiptLabelWidth,
                    child: Text(
                      row.label,
                      style: theme.textTheme.labelLarge?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                    ),
                  ),
                  const SizedBox(width: 16),
                  Expanded(
                    child: Text(
                      row.value,
                      textAlign: TextAlign.end,
                      style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurface),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _DashedRule extends CustomPainter {
  _DashedRule(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    const dash = AlbumAboutSheetMetrics.receiptDash;
    final paint = Paint()
      ..color = color
      ..strokeWidth = 1;
    final y = size.height / 2;
    for (var x = 0.0; x < size.width; x += dash * 2) {
      canvas.drawLine(Offset(x, y), Offset((x + dash).clamp(0, size.width), y), paint);
    }
  }

  @override
  bool shouldRepaint(_DashedRule old) => old.color != color;
}

/// The paragraph on the card's colour, its byline and Regenerate; the AI's dots while it writes.
class _AboutParagraph extends StatelessWidget {
  const _AboutParagraph({
    required this.text,
    required this.busy,
    required this.colors,
    required this.byline,
    required this.regenerateLabel,
    required this.onRegenerate,
  });

  final String? text;
  final bool busy;
  final ScrapbookColors colors;
  final String byline;
  final String regenerateLabel;
  final VoidCallback? onRegenerate;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: AlbumAboutSheetMetrics.sectionGap),
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: colors.about,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AlbumAboutSheetMetrics.cardCorner)),
        ),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              AiSpringSwap(
                childKey: busy,
                child: busy
                    ? Align(
                        alignment: AlignmentDirectional.centerStart,
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          child: ThinkingDots(color: colors.onAbout),
                        ),
                      )
                    : Text(text ?? '', style: theme.textTheme.bodyLarge?.copyWith(height: 1.6, color: colors.onAbout)),
              ),
              Padding(
                padding: const EdgeInsets.only(top: 10),
                child: Row(
                  children: [
                    _Byline(text: byline, color: colors.onAbout),
                    const Spacer(),
                    FilledButton.tonal(
                      style: FilledButton.styleFrom(
                        backgroundColor: theme.colorScheme.surface,
                        foregroundColor: colors.aboutMark,
                      ),
                      onPressed: onRegenerate,
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(YoinSymbols.sparkle, size: 16),
                          const SizedBox(width: 6),
                          Text(regenerateLabel, maxLines: 1),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// "Generate with ‹service›" (the song sheet's empty About offers the same), for an About that came back empty.
class _GenerateAbout extends StatelessWidget {
  const _GenerateAbout({required this.label, required this.colors, required this.onGenerate});

  final String label;
  final ScrapbookColors colors;
  final VoidCallback onGenerate;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(top: AlbumAboutSheetMetrics.sectionGap),
    child: ConstrainedBox(
      constraints: const BoxConstraints(minHeight: AlbumAboutSheetMetrics.buttonMinHeight),
      child: FilledButton.tonal(
        style: FilledButton.styleFrom(backgroundColor: colors.about, foregroundColor: colors.onAbout),
        onPressed: onGenerate,
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(YoinSymbols.sparkle, size: 18),
            const SizedBox(width: 8),
            Flexible(child: Text(label, maxLines: 1, overflow: TextOverflow.ellipsis)),
          ],
        ),
      ),
    ),
  );
}

/// A quiet line under what was just tried: the existing "‹service› had nothing new" / "couldn't answer".
class _AboutNotice extends StatelessWidget {
  const _AboutNotice({required this.text});

  final String? text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final text = this.text;
    return SpringReveal(
      visible: text != null,
      child: text == null
          ? null
          : Padding(
              padding: const EdgeInsets.only(top: 10, left: 4, right: 4),
              child: Text(text, style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ),
    );
  }
}

/// A question about the album with the AI's title and answer; its dots while the answer is on the way.
class _AskAnswerCard extends StatelessWidget {
  const _AskAnswerCard({
    super.key,
    required this.question,
    this.title,
    this.answer,
    required this.colors,
    required this.byline,
  });

  final String question;
  final String? title;

  /// Null while the AI is still answering.
  final String? answer;
  final ScrapbookColors colors;
  final String byline;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final title = this.title;
    final answer = this.answer;
    return Padding(
      padding: const EdgeInsets.only(top: AlbumAboutSheetMetrics.sectionGap),
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: colors.about,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AlbumAboutSheetMetrics.cardCorner)),
        ),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                question,
                style: theme.textTheme.bodyLarge?.copyWith(
                  fontSize: 13.5,
                  height: 1.5,
                  color: colors.onAbout.withValues(alpha: 0.75),
                ),
              ),
              if (answer == null)
                Padding(
                  padding: const EdgeInsets.only(top: 12, bottom: 4),
                  child: ThinkingDots(color: colors.onAbout),
                )
              else ...[
                if (title != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Text(
                      title,
                      style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600, color: colors.onAbout),
                    ),
                  ),
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text.rich(
                    markdownBoldSpan(answer),
                    style: theme.textTheme.bodyLarge?.copyWith(height: 1.55, color: colors.onAbout),
                  ),
                ),
              ],
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: _Byline(text: byline, color: colors.onAbout),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Byline extends StatelessWidget {
  const _Byline({required this.text, required this.color});

  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Icon(YoinSymbols.sparkle, size: 14, color: color),
      const SizedBox(width: 4),
      Text(
        text,
        style: Theme.of(
          context,
        ).textTheme.labelMedium?.copyWith(fontWeight: FontWeight.w600, letterSpacing: 0.2, color: color),
      ),
    ],
  );
}

/// The question field, "Ask ‹service›" as its hint, and a send button beside it (Now Playing's ask bar:
/// the keyboard's Send and a hardware Enter work too). No Write: everything on this sheet is generated.
class _AskAlbum extends StatefulWidget {
  const _AskAlbum({required this.hint, required this.colors, required this.onAsk});

  final String hint;
  final ScrapbookColors colors;
  final ValueChanged<String> onAsk;

  @override
  State<_AskAlbum> createState() => _AskAlbumState();
}

class _AskAlbumState extends State<_AskAlbum> {
  final TextEditingController _question = TextEditingController();

  @override
  void initState() {
    super.initState();
    _question.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _question.dispose();
    super.dispose();
  }

  void _send() {
    final text = _question.text.trim();
    if (text.isEmpty) return;
    widget.onAsk(text);
    _question.clear();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final hasQuestion = _question.text.trim().isNotEmpty;
    final style = theme.textTheme.bodyLarge;
    return Padding(
      padding: const EdgeInsets.only(top: 18),
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: scheme.surfaceContainerHigh,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AlbumAboutSheetMetrics.askFieldCorner)),
        ),
        child: Padding(
          padding: const EdgeInsetsDirectional.fromSTEB(10, 10, 6, 10),
          child: Row(
            children: [
              Expanded(
                child: DecoratedBox(
                  decoration: ShapeDecoration(
                    color: scheme.surface,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(AlbumAboutSheetMetrics.fieldCorner),
                    ),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
                    child: CallbackShortcuts(
                      // A hardware Enter asks too (desktop windows, a keyboard on the tablet).
                      bindings: {const SingleActivator(LogicalKeyboardKey.enter): _send},
                      child: TextField(
                        key: const ValueKey('albumAboutAskField'),
                        controller: _question,
                        maxLines: 4,
                        minLines: 1,
                        textInputAction: TextInputAction.send,
                        textCapitalization: TextCapitalization.sentences,
                        onSubmitted: (_) => _send(),
                        style: style?.copyWith(color: scheme.onSurface),
                        cursorColor: scheme.primary,
                        decoration: InputDecoration.collapsed(
                          hintText: widget.hint,
                          hintStyle: style?.copyWith(color: scheme.onSurfaceVariant),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 6),
              SizedBox.square(
                dimension: AlbumAboutSheetMetrics.sendButton,
                child: IconButton.filled(
                  key: const ValueKey('albumAboutAskSend'),
                  style: IconButton.styleFrom(
                    backgroundColor: widget.colors.about,
                    foregroundColor: widget.colors.onAbout,
                  ),
                  tooltip: widget.hint,
                  onPressed: hasQuestion ? _send : null,
                  icon: const Icon(YoinSymbols.send, size: 20),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _NoticeBar extends StatelessWidget {
  const _NoticeBar({required this.notice, required this.undoLabel, required this.onUndo});

  final _Notice notice;
  final String undoLabel;
  final VoidCallback onUndo;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    return Padding(
      padding: EdgeInsets.fromLTRB(
        AlbumAboutSheetMetrics.gutter,
        4,
        AlbumAboutSheetMetrics.gutter,
        8 + MediaQuery.paddingOf(context).bottom,
      ),
      child: DecoratedBox(
        decoration: ShapeDecoration(
          color: colors.inverseSurface,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AlbumAboutSheetMetrics.fieldCorner)),
        ),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 6, 8, 6),
          child: Row(
            children: [
              if (notice.icon case final icon?) ...[
                Icon(icon, size: 18, color: colors.onInverseSurface),
                const SizedBox(width: 10),
              ],
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    notice.message,
                    style: theme.textTheme.bodyMedium?.copyWith(color: colors.onInverseSurface),
                  ),
                ),
              ),
              if (notice.undo != null)
                TextButton(
                  onPressed: onUndo,
                  child: Text(undoLabel, style: TextStyle(color: colors.inversePrimary)),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
