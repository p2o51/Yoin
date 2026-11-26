/// The share sheet (Android ShareSheet.kt, owner 2026-11-25, prototype round
/// 2): 歌词 / 关于 / 笔记 on top (the Now Playing tabs' words and look; it
/// opens on the tab it came from), then 版式 — a live thumbnail of each layout
/// — then the preview in a fixed box, 卡片 / Story, the tones (both colour
/// families on every tab; each opens on its role's colour), 保存 / 分享. Each tab remembers its last layout across opens
/// ([ShareLayoutStore]). At Expanded width the sheet is two panes: the preview
/// large on the left (up to 1.5× — it is not the PNG), the controls on the
/// right with larger thumbnails in two rows and Save / Share pinned at the
/// bottom. What the sheet shows IS what is saved or shared: the preview draws
/// the image at its logical size inside a repaint boundary, read back at the
/// format's fixed pixel ratio.
library;

import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

import '../platform/share/image_share.dart';
import '../theme/motion.dart';
import '../ui/component/seam/chrome_seam.dart';
import '../ui/component/spring_cross_fade.dart';
import 'haptics.dart';
import 'lyrics_share.dart';
import 'nowplaying_strings.dart';
import 'share_cards.dart';
import 'share_content.dart';

/// The sheet's geometry (Android ShareSheet.kt).
abstract final class ShareSheetMetrics {
  /// On a phone the preview takes this share of the window, whatever the tab, layout or format.
  static const double previewHeightFraction = 0.45;
  static const double thumbWidth = 70;
  static const double wideThumbWidth = 112;
  static const double thumbAspect = 88 / 70;
  static const double thumbCorner = 12;
  static const double thumbRingInset = 4;
  static const double thumbRing = 2;
  static const double thumbPressedScale = 0.94;

  /// Expanded: a two-pane sheet this wide at most, this share of the window tall; the preview may grow 1.5×.
  static const double wideMinWidth = 840;
  static const double wideMinHeight = 480;
  static const double wideMaxWidth = 1040;
  static const double widePaneHeightFraction = 0.82;
  static const double widePreviewMaxScale = 1.5;
  static const double swatch = 36;
  static const double swatchTouch = 48;
  static const double swatchSelectedCorner = 10;
  static const double swatchRing = 2;
  static const double toggleHeight = 44;
  static const double actionHeight = 56;
  static const double previewStoryCorner = 16;
  static const double previewCardCorner = 20;
}

/// Whether a window this big takes the two-pane sheet.
bool shareSheetIsWide(Size window) =>
    window.width >= ShareSheetMetrics.wideMinWidth && window.height >= ShareSheetMetrics.wideMinHeight;

/// The layout's name under its thumbnail.
String shareLayoutLabel(ShareLayout layout, NowPlayingStrings s) => switch (layout) {
  ShareLayout.lyricsClassic => s.shareLayoutClassic,
  ShareLayout.lyricsPoster => s.shareLayoutPoster,
  ShareLayout.lyricsPlayer => s.shareLayoutPlayer,
  ShareLayout.lyricsVertical => s.shareLayoutVertical,
  ShareLayout.aboutReceipt => s.shareLayoutReceipt,
  ShareLayout.aboutQuote => s.shareLayoutQuote,
  ShareLayout.aboutLiner => s.shareLayoutLiner,
  ShareLayout.noteBig => s.shareLayoutBig,
  ShareLayout.noteClipping => s.shareLayoutClipping,
  ShareLayout.noteTimeline => s.shareLayoutTimeline,
};

/// The tab's name on the sheet: the Now Playing tabs' words.
String shareTabLabel(ShareTab tab, NowPlayingStrings s) => switch (tab) {
  ShareTab.lyrics => s.tabLyrics,
  ShareTab.about => s.tabAbout,
  ShareTab.note => s.tabNote,
};

/// The image of [boundary] as a PNG at [format]'s fixed pixel ratio (1440 wide
/// for a Card, 1080 × 1920 for a Story); null when it can't be read.
Future<Uint8List?> exportShareImage(RenderRepaintBoundary boundary, ShareFormat format) async {
  try {
    final image = await boundary.toImage(pixelRatio: sharePixelRatio(format));
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    image.dispose();
    return data?.buffer.asUint8List();
  } catch (_) {
    return null;
  }
}

/// Opens the share sheet on [initialTab] (or the first tab this song has) over
/// [navigatorContext]'s navigator: the layout memory is read first and the
/// cover warmed, so the first frame already shows the remembered layout with
/// its cover. Does nothing when the song has nothing to share. [onShared]
/// runs once the image went to the system share sheet (the sheet closes);
/// [onKept] when the lyrics tab's image was saved or shared (Home's 「歌词一句」
/// keeps the lines as the listener's pick).
Future<void> showShareSheet(
  BuildContext navigatorContext, {
  required ShareContent content,
  required ShareTab initialTab,
  required ValueChanged<String> onMessage,
  VoidCallback? onShared,
  VoidCallback? onKept,
}) async {
  if (content.tabs.isEmpty) return;
  final store = ProviderScope.containerOf(navigatorContext, listen: false).read(shareLayoutStoreProvider);
  await store.load();
  if (!navigatorContext.mounted) return;
  final url = content.song.coverArtUrl;
  if (url != null) unawaited(precacheImage(shareCoverImage(url), navigatorContext).catchError((Object _) {}));
  final window = MediaQuery.sizeOf(navigatorContext);
  final wide = shareSheetIsWide(window);
  await showModalBottomSheet<void>(
    context: navigatorContext,
    useSafeArea: true,
    isScrollControlled: true,
    showDragHandle: true,
    constraints: BoxConstraints(maxWidth: wide ? ShareSheetMetrics.wideMaxWidth : 640),
    backgroundColor: Theme.of(navigatorContext).colorScheme.surfaceContainer,
    builder: (sheetContext) => ShareSheet(
      content: content,
      initialTab: initialTab,
      onShared: () {
        Navigator.of(sheetContext).pop();
        onShared?.call();
      },
      onMessage: onMessage,
      onKept: onKept,
    ),
  );
}

enum _SaveStatus { idle, saving, saved, failed }

/// Share as image: the tab ([initialTab] first, when it has something), its
/// layout, Card / Story, a tone, then Save and Share.
class ShareSheet extends ConsumerStatefulWidget {
  const ShareSheet({
    super.key,
    required this.content,
    required this.initialTab,
    required this.onShared,
    required this.onMessage,
    this.onKept,
  });

  final ShareContent content;
  final ShareTab initialTab;
  final VoidCallback onShared;
  final ValueChanged<String> onMessage;

  /// The lyrics tab's image was saved or shared (Android onKept).
  final VoidCallback? onKept;

  @override
  ConsumerState<ShareSheet> createState() => _ShareSheetState();
}

class _ShareSheetState extends ConsumerState<ShareSheet> {
  late final List<ShareTab> _tabs = widget.content.tabs;
  late ShareTab _tab = _tabs.contains(widget.initialTab) ? widget.initialTab : _tabs.first;
  final Map<ShareTab, ShareLayout> _layouts = {};
  ShareFormat _format = ShareFormat.card;

  /// The tone the listener picked, kept across tabs (null = none yet: each tab
  /// opens on its own default). Every tab offers both colour families; the
  /// tab's own colour is only its default (owner 2026-11-26).
  ShareTone? _pickedTone;
  bool _sharing = false;
  _SaveStatus _save = _SaveStatus.idle;

  /// One boundary per shown image: during a cross-fade both draw, and the export reads the current one.
  final Map<(ShareLayout, ShareFormat, ShareTone), GlobalKey> _boundaries = {};

  @override
  void initState() {
    super.initState();
    final store = ref.read(shareLayoutStoreProvider);
    // Each tab's layout: what it last used (when this song offers it), else its first.
    for (final t in _tabs) {
      final layout = widget.content.resolveLayout(t, store.layoutFor(t));
      if (layout != null) _layouts[t] = layout;
    }
  }

  ShareLayout get _layout => _layouts[_tab]!;

  /// Paper, which the lyrics don't have, falls back to their default there.
  ShareTone get _tone => YoinShareCards.resolveTone(_tab, _pickedTone);

  GlobalKey _boundaryFor(ShareLayout layout, ShareFormat format, ShareTone tone) =>
      _boundaries.putIfAbsent((layout, format, tone), () => GlobalKey(debugLabel: 'share $layout $format $tone'));

  void _pickTab(ShareTab tab) {
    if (tab == _tab) return;
    YoinHaptics.segmentTick();
    setState(() {
      _tab = tab;
      _save = _SaveStatus.idle;
    });
  }

  void _pickLayout(ShareLayout layout) {
    if (layout == _layout) return;
    YoinHaptics.segmentTick();
    ref.read(shareLayoutStoreProvider).setLayout(layout.tab, layout);
    setState(() {
      _layouts[layout.tab] = layout;
      _save = _SaveStatus.idle;
    });
  }

  void _pickFormat(ShareFormat format) {
    if (format == _format) return;
    YoinHaptics.segmentTick();
    setState(() {
      _format = format;
      _save = _SaveStatus.idle;
    });
  }

  void _pickTone(ShareTone tone) {
    if (!YoinShareCards.tones(_tab).contains(tone) || tone == _tone) return;
    YoinHaptics.segmentTick();
    setState(() {
      _pickedTone = tone;
      _save = _SaveStatus.idle;
    });
  }

  void _keep() {
    if (_tab == ShareTab.lyrics) widget.onKept?.call();
  }

  Future<Uint8List?> _export() async {
    final url = widget.content.song.coverArtUrl;
    if (url != null) {
      // The cover must be in memory, or the PNG catches its quiet tile.
      try {
        await precacheImage(shareCoverImage(url), context);
      } on Object {
        // A cover that can't load leaves the tile.
      }
    }
    final key = _boundaries[(_layout, _format, _tone)];
    final boundary = key?.currentContext?.findRenderObject();
    if (boundary is! RenderRepaintBoundary) return null;
    return exportShareImage(boundary, _format);
  }

  Future<void> _onSave() async {
    if (_save == _SaveStatus.saving || _save == _SaveStatus.saved) return;
    final strings = NowPlayingStrings.of(context);
    final share = ref.read(imageShareProvider);
    setState(() => _save = _SaveStatus.saving);
    final png = await _export();
    final name = shareFileName(widget.content.song.title, DateTime.now().millisecondsSinceEpoch, strings);
    final saved = png != null && await share.save(png, fileName: name);
    if (!mounted) return;
    setState(() => _save = saved ? _SaveStatus.saved : _SaveStatus.failed);
    if (saved) _keep();
    saved ? YoinHaptics.confirm() : YoinHaptics.reject();
  }

  Future<void> _onShare() async {
    if (_sharing) return;
    final strings = NowPlayingStrings.of(context);
    final share = ref.read(imageShareProvider);
    setState(() => _sharing = true);
    YoinHaptics.confirm();
    final png = await _export();
    final song = widget.content.song;
    final name = shareFileName(song.title, DateTime.now().millisecondsSinceEpoch, strings);
    final shared =
        png != null && await share.share(png, fileName: name, caption: YoinShareCards.caption(song.title, song.artist));
    if (!mounted) return;
    setState(() => _sharing = false);
    if (shared) {
      _keep();
      widget.onShared();
    } else {
      widget.onMessage(strings.shareFailed);
    }
  }

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final window = MediaQuery.sizeOf(context);
    final wide = shareSheetIsWide(window);
    final navBottom = MediaQuery.paddingOf(context).bottom;
    final canSave = ref.watch(imageShareProvider).canSave;
    final tabs = _ShareTabs(tabs: _tabs, selected: _tab, onSelect: _pickTab);
    Widget thumbs(double width, int rows) => _LayoutThumbs(
      content: widget.content,
      layouts: widget.content.layoutsFor(_tab),
      selected: _layout,
      format: _format,
      tone: _tone,
      thumbWidth: width,
      rows: rows,
      onSelect: _pickLayout,
    );
    Widget preview(double maxHeight, double maxScale) => _SharePreview(
      content: widget.content,
      layout: _layout,
      format: _format,
      tone: _tone,
      boundary: _boundaryFor(_layout, _format, _tone),
      maxHeight: maxHeight,
      maxScale: maxScale,
    );
    final formatAndTones = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        _FormatToggle(selected: _format, onSelect: _pickFormat),
        const SizedBox(height: 12),
        _ToneSwatches(role: _tab.role, selected: _tone, onSelect: _pickTone),
      ],
    );
    final actions = _ShareActions(canSave: canSave, save: _save, sharing: _sharing, onSave: _onSave, onShare: _onShare);
    final label = Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Text(
        strings.shareLayout,
        style: Theme.of(context).textTheme.labelMedium!.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
      ),
    );
    final Widget body;
    if (wide) {
      final paneHeight = window.height * ShareSheetMetrics.widePaneHeightFraction;
      body = SizedBox(
        height: paneHeight,
        child: Padding(
          padding: EdgeInsets.fromLTRB(32, 0, 32, 12 + navBottom),
          child: Row(
            spacing: 40,
            children: [
              Expanded(child: Center(child: preview(paneHeight - 24, ShareSheetMetrics.widePreviewMaxScale))),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    tabs,
                    const SizedBox(height: 16),
                    label,
                    thumbs(ShareSheetMetrics.wideThumbWidth, 2),
                    const SizedBox(height: 20),
                    formatAndTones,
                    const Spacer(),
                    actions,
                  ],
                ),
              ),
            ],
          ),
        ),
      );
    } else {
      // The tabs name the sheet and stay put; the rest scrolls under the user's scroll edge (the tide line by
      // default), never cut flat under the handle (Android #360 sheetScrollEdge; owner 2026-11-26).
      body = Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          Padding(padding: const EdgeInsets.symmetric(horizontal: 24), child: tabs),
          Flexible(
            child: ChromeSeam(
              background: SeamBackground([Theme.of(context).colorScheme.surfaceContainer]),
              child: SingleChildScrollView(
                key: const ValueKey('share-sheet-scroll'),
                padding: EdgeInsets.fromLTRB(24, 12, 24, 16 + navBottom),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    label,
                    thumbs(ShareSheetMetrics.thumbWidth, 1),
                    const SizedBox(height: 16),
                    preview(window.height * ShareSheetMetrics.previewHeightFraction, 1),
                    const SizedBox(height: 16),
                    formatAndTones,
                    const SizedBox(height: 16),
                    actions,
                  ],
                ),
              ),
            ),
          ),
        ],
      );
    }
    return Semantics(
      key: const ValueKey('share-sheet'),
      namesRoute: true,
      scopesRoute: true,
      explicitChildNodes: true,
      label: strings.shareSheetTitle,
      child: body,
    );
  }
}

/// The preview: the image itself in a fixed box (the sheet never resizes on a
/// switch), scaled to fit, rounded only here (the PNG is a rectangle). A
/// layout, format or tone change cross-fades outside the export boundary.
class _SharePreview extends StatelessWidget {
  const _SharePreview({
    required this.content,
    required this.layout,
    required this.format,
    required this.tone,
    required this.boundary,
    required this.maxHeight,
    required this.maxScale,
  });

  final ShareContent content;
  final ShareLayout layout;
  final ShareFormat format;
  final ShareTone tone;
  final GlobalKey boundary;
  final double maxHeight;
  final double maxScale;

  @override
  Widget build(BuildContext context) => SizedBox(
    key: const ValueKey('share-preview-box'),
    height: maxHeight,
    width: double.infinity,
    child: SpringCrossFade(
      childKey: (layout, format, tone),
      child: Center(
        child: ClipRRect(
          borderRadius: BorderRadius.circular(
            format == ShareFormat.story ? ShareSheetMetrics.previewStoryCorner : ShareSheetMetrics.previewCardCorner,
          ),
          child: ShareFitScale(
            key: const ValueKey('share-preview'),
            maxHeight: maxHeight,
            maxScale: maxScale,
            child: RepaintBoundary(
              key: boundary,
              child: ShareImage(content: content, layout: layout, format: format, tone: tone, animateTone: false),
            ),
          ),
        ),
      ),
    ),
  );
}

/// 歌词 / 关于 / 笔记 as the Now Playing text tabs: the picked one bold and stretched, the others at half.
class _ShareTabs extends StatelessWidget {
  const _ShareTabs({required this.tabs, required this.selected, required this.onSelect});

  final List<ShareTab> tabs;
  final ShareTab selected;
  final ValueChanged<ShareTab> onSelect;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      for (final (i, tab) in tabs.indexed)
        Padding(
          padding: EdgeInsetsDirectional.only(start: i == 0 ? 0 : 16),
          child: _ShareTabLabel(
            key: ValueKey('share-tab-${tab.storeKey}'),
            label: shareTabLabel(tab, NowPlayingStrings.of(context)),
            selected: tab == selected,
            onTap: () => onSelect(tab),
          ),
        ),
    ],
  );
}

class _ShareTabLabel extends StatefulWidget {
  const _ShareTabLabel({super.key, required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  State<_ShareTabLabel> createState() => _ShareTabLabelState();
}

class _ShareTabLabelState extends State<_ShareTabLabel> with TickerProviderStateMixin {
  bool _pressed = false;
  late final SpringValue _stretch = SpringValue(
    vsync: this,
    initial: _stretchTarget,
    spring: YoinSprings.defaultSpatial,
  );
  late final SpringValue _alpha = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0.5,
    spring: YoinSprings.defaultEffects,
  );

  double get _stretchTarget => _pressed ? 0.92 : (widget.selected ? 1.08 : 1);

  @override
  void didUpdateWidget(_ShareTabLabel oldWidget) {
    super.didUpdateWidget(oldWidget);
    _stretch.animateTo(_stretchTarget);
    _alpha.animateTo(widget.selected ? 1 : 0.5);
  }

  void _press(bool down) {
    if (_pressed == down) return;
    _pressed = down;
    _stretch.animateTo(_stretchTarget);
  }

  @override
  void dispose() {
    _stretch.dispose();
    _alpha.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.titleMedium!;
    final color = Theme.of(context).colorScheme.onSurface;
    return Semantics(
      button: true,
      selected: widget.selected,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapDown: (_) => _press(true),
        onTapUp: (_) => _press(false),
        onTapCancel: () => _press(false),
        onTap: widget.onTap,
        child: MouseRegion(
          cursor: SystemMouseCursors.click,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 6),
            child: AnimatedBuilder(
              animation: Listenable.merge([_stretch.animation, _alpha.animation]),
              builder: (context, child) => Opacity(
                opacity: _alpha.value.clamp(0.0, 1.0),
                child: Transform(
                  alignment: AlignmentDirectional.centerStart,
                  transform: Matrix4.diagonal3Values(_stretch.value, 1, 1),
                  child: child,
                ),
              ),
              child: Text(widget.label, style: shareWeight(style, widget.selected ? 700 : 500).copyWith(color: color)),
            ),
          ),
        ),
      ),
    );
  }
}

/// 版式: each layout the tab offers as a live thumbnail of the image (in the
/// format and tone picked) with its name; the picked one wears a 2 primary
/// ring and its name in primary, bold. Nothing square behind it: no splash or
/// backdrop over the thumbnail-and-name block (owner 2026-11-26) — a press
/// squeezes the thumbnail. [rows] > 1 (the wide sheet) lays them out in
/// that many rows; one row scrolls sideways if it must.
class _LayoutThumbs extends StatelessWidget {
  const _LayoutThumbs({
    required this.content,
    required this.layouts,
    required this.selected,
    required this.format,
    required this.tone,
    required this.thumbWidth,
    required this.rows,
    required this.onSelect,
  });

  final ShareContent content;
  final List<ShareLayout> layouts;
  final ShareLayout selected;
  final ShareFormat format;
  final ShareTone tone;
  final double thumbWidth;
  final int rows;
  final ValueChanged<ShareLayout> onSelect;

  @override
  Widget build(BuildContext context) {
    final perRow = math.max(1, (layouts.length / rows).ceil());
    Widget row(List<ShareLayout> items) => Row(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 10,
      children: [
        for (final layout in items)
          _LayoutThumb(
            key: ValueKey('share-thumb-${layout.storeValue}'),
            content: content,
            layout: layout,
            format: format,
            tone: tone,
            selected: layout == selected,
            width: thumbWidth,
            onTap: () => onSelect(layout),
          ),
      ],
    );
    if (rows == 1) {
      return SingleChildScrollView(scrollDirection: Axis.horizontal, child: row(layouts));
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 12,
      children: [
        for (var i = 0; i < layouts.length; i += perRow) row(layouts.sublist(i, math.min(layouts.length, i + perRow))),
      ],
    );
  }
}

class _LayoutThumb extends StatefulWidget {
  const _LayoutThumb({
    super.key,
    required this.content,
    required this.layout,
    required this.format,
    required this.tone,
    required this.selected,
    required this.width,
    required this.onTap,
  });

  final ShareContent content;
  final ShareLayout layout;
  final ShareFormat format;
  final ShareTone tone;
  final bool selected;
  final double width;
  final VoidCallback onTap;

  @override
  State<_LayoutThumb> createState() => _LayoutThumbState();
}

class _LayoutThumbState extends State<_LayoutThumb> with TickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  /// The press squeeze on the thumbnail alone (Android elasticPress, ThumbPressedScale).
  late final SpringValue _press = SpringValue(vsync: this, initial: 1, spring: YoinSprings.standardDefaultSpatial);

  @override
  void didUpdateWidget(_LayoutThumb oldWidget) {
    super.didUpdateWidget(oldWidget);
    _on.animateTo(widget.selected ? 1 : 0);
  }

  @override
  void dispose() {
    _on.dispose();
    _press.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final label = Theme.of(context).textTheme.labelMedium!;
    final width = widget.width;
    final height = width * ShareSheetMetrics.thumbAspect;
    const inset = ShareSheetMetrics.thumbRingInset;
    final story = widget.format == ShareFormat.story;
    return Semantics(
      button: true,
      inMutuallyExclusiveGroup: true,
      selected: widget.selected,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapDown: (_) => _press.animateTo(ShareSheetMetrics.thumbPressedScale),
        onTapUp: (_) => _press.animateTo(1),
        onTapCancel: () => _press.animateTo(1),
        onTap: widget.onTap,
        child: MouseRegion(
          cursor: SystemMouseCursors.click,
          child: SizedBox(
            width: width + inset * 2,
            child: AnimatedBuilder(
              animation: _on.animation,
              builder: (context, child) {
                final t = _on.value.clamp(0.0, 1.0);
                return Column(
                  children: [
                    ScaleTransition(
                      scale: _press.animation,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          border: Border.all(
                            color: scheme.primary.withValues(alpha: t),
                            width: ShareSheetMetrics.thumbRing,
                          ),
                          borderRadius: BorderRadius.circular(ShareSheetMetrics.thumbCorner + inset),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.all(inset - ShareSheetMetrics.thumbRing),
                          child: child,
                        ),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(
                        shareLayoutLabel(widget.layout, NowPlayingStrings.of(context)),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: shareWeight(
                          label,
                          widget.selected ? 700 : 500,
                        ).copyWith(color: Color.lerp(scheme.onSurfaceVariant, scheme.primary, t)),
                      ),
                    ),
                  ],
                );
              },
              child: ClipRRect(
                borderRadius: BorderRadius.circular(ShareSheetMetrics.thumbCorner),
                child: Container(
                  width: width,
                  height: height,
                  color: scheme.surfaceContainerHigh,
                  // The image at its own size, scaled to the thumbnail's width (a card: its top shows) or height
                  // (a story: whole).
                  child: FittedBox(
                    fit: story ? BoxFit.fitHeight : BoxFit.fitWidth,
                    alignment: Alignment.topCenter,
                    clipBehavior: Clip.hardEdge,
                    child: IgnorePointer(
                      child: ShareImage(
                        content: widget.content,
                        layout: widget.layout,
                        format: widget.format,
                        tone: widget.tone,
                      ),
                    ),
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

/// Card / Story, as the NP tab group's pills: primary when picked, primaryContainer otherwise.
class _FormatToggle extends StatelessWidget {
  const _FormatToggle({required this.selected, required this.onSelect});

  final ShareFormat selected;
  final ValueChanged<ShareFormat> onSelect;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    return SizedBox(
      height: ShareSheetMetrics.toggleHeight,
      child: Row(
        spacing: 8,
        children: [
          for (final f in ShareFormat.values)
            Expanded(
              child: _FormatPill(
                key: ValueKey('share-format-${f.kotlinName}'),
                label: f == ShareFormat.card ? strings.shareFormatCard : strings.shareFormatStory,
                selected: f == selected,
                onTap: () => onSelect(f),
              ),
            ),
        ],
      ),
    );
  }
}

class _FormatPill extends StatefulWidget {
  const _FormatPill({super.key, required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  State<_FormatPill> createState() => _FormatPillState();
}

class _FormatPillState extends State<_FormatPill> with TickerProviderStateMixin {
  late final SpringValue _on = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.defaultEffects,
  );

  @override
  void didUpdateWidget(_FormatPill oldWidget) {
    super.didUpdateWidget(oldWidget);
    _on.animateTo(widget.selected ? 1 : 0);
  }

  @override
  void dispose() {
    _on.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final style = Theme.of(context).textTheme.labelLarge!;
    return Semantics(
      button: true,
      selected: widget.selected,
      child: AnimatedBuilder(
        animation: _on.animation,
        builder: (context, _) {
          final t = _on.value.clamp(0.0, 1.0);
          return Material(
            color: Color.lerp(colors.primaryContainer, colors.primary, t),
            shape: const StadiumBorder(),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: widget.onTap,
              child: Center(
                child: Text(
                  widget.label,
                  style: shareWeight(
                    style,
                    widget.selected ? 700 : 500,
                  ).copyWith(color: Color.lerp(colors.onPrimaryContainer, colors.onPrimary, t)),
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// [role]'s tones as swatches of the card colour; the picked one squares
/// off (corner on the spatial spring) and wears a check. Paper is the lowest
/// surface: a ring of its ink keeps it from vanishing into the sheet.
class _ToneSwatches extends StatelessWidget {
  const _ToneSwatches({required this.role, required this.selected, required this.onSelect});

  final ShareRole role;
  final ShareTone selected;
  final ValueChanged<ShareTone> onSelect;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    final scheme = Theme.of(context).colorScheme;
    return Row(
      spacing: 4,
      children: [
        for (final tone in role.tones())
          _Swatch(
            key: ValueKey('share-tone-${tone.kotlinName}'),
            colors: shareToneColors(tone, role, scheme),
            label: switch (tone) {
              ShareTone.soft => strings.cdToneSoft,
              ShareTone.deep => strings.cdToneDeep,
              ShareTone.vivid => strings.cdToneVivid,
              ShareTone.mist => strings.cdToneMist,
              ShareTone.paper => strings.cdTonePaper,
            },
            ring: tone == ShareTone.paper,
            selected: tone == selected,
            onTap: () => onSelect(tone),
          ),
      ],
    );
  }
}

class _Swatch extends StatefulWidget {
  const _Swatch({
    super.key,
    required this.colors,
    required this.label,
    required this.selected,
    required this.onTap,
    this.ring = false,
  });

  final ShareCardColors colors;
  final String label;
  final bool selected;
  final bool ring;
  final VoidCallback onTap;

  @override
  State<_Swatch> createState() => _SwatchState();
}

class _SwatchState extends State<_Swatch> with TickerProviderStateMixin {
  late final SpringValue _corner = SpringValue(vsync: this, initial: _target, spring: YoinSprings.defaultSpatial);
  late final SpringValue _check = SpringValue(
    vsync: this,
    initial: widget.selected ? 1 : 0,
    spring: YoinSprings.defaultSpatial,
  );

  double get _target => widget.selected ? ShareSheetMetrics.swatchSelectedCorner : ShareSheetMetrics.swatch / 2;

  @override
  void didUpdateWidget(_Swatch oldWidget) {
    super.didUpdateWidget(oldWidget);
    _corner.animateTo(_target);
    _check.animateTo(widget.selected ? 1 : 0);
  }

  @override
  void dispose() {
    _corner.dispose();
    _check.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Semantics(
    inMutuallyExclusiveGroup: true,
    checked: widget.selected,
    label: widget.label,
    button: true,
    excludeSemantics: true,
    child: InkResponse(
      onTap: widget.onTap,
      radius: ShareSheetMetrics.swatchTouch / 2,
      child: SizedBox.square(
        dimension: ShareSheetMetrics.swatchTouch,
        child: Center(
          child: AnimatedBuilder(
            animation: Listenable.merge([_corner.animation, _check.animation]),
            builder: (context, _) {
              final c = _check.value;
              final radius = BorderRadius.circular(math.max(0, _corner.value));
              return Container(
                width: ShareSheetMetrics.swatch,
                height: ShareSheetMetrics.swatch,
                decoration: BoxDecoration(
                  color: widget.colors.card,
                  borderRadius: radius,
                  border: widget.ring
                      ? Border.all(color: widget.colors.content, width: ShareSheetMetrics.swatchRing)
                      : null,
                ),
                child: c <= 0.01
                    ? null
                    : Opacity(
                        opacity: c.clamp(0.0, 1.0),
                        child: Transform.scale(
                          scale: 0.6 + 0.4 * c,
                          child: Icon(YoinSymbols.check, size: 20, color: widget.colors.content),
                        ),
                      ),
              );
            },
          ),
        ),
      ),
    ),
  );
}

/// Save (Save → Saving… → Saved / Try again, in place) and Share.
class _ShareActions extends StatelessWidget {
  const _ShareActions({
    required this.canSave,
    required this.save,
    required this.sharing,
    required this.onSave,
    required this.onShare,
  });

  final bool canSave;
  final _SaveStatus save;
  final bool sharing;
  final VoidCallback onSave;
  final VoidCallback onShare;

  @override
  Widget build(BuildContext context) {
    final strings = NowPlayingStrings.of(context);
    return Row(
      spacing: 12,
      children: [
        if (canSave)
          Expanded(
            child: SizedBox(
              height: ShareSheetMetrics.actionHeight,
              child: FilledButton.tonal(
                key: const ValueKey('share-save'),
                onPressed: save == _SaveStatus.saving ? null : onSave,
                style: FilledButton.styleFrom(shape: const StadiumBorder()),
                child: SpringCrossFade(
                  childKey: save,
                  fit: StackFit.loose,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(save == _SaveStatus.saved ? YoinSymbols.downloadDone : YoinSymbols.download),
                      const SizedBox(width: 8),
                      Flexible(
                        child: Text(
                          switch (save) {
                            _SaveStatus.idle => strings.shareSave,
                            _SaveStatus.saving => strings.shareSaving,
                            _SaveStatus.saved => strings.shareSaved,
                            _SaveStatus.failed => strings.shareTryAgain,
                          },
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        Expanded(
          child: SizedBox(
            height: ShareSheetMetrics.actionHeight,
            child: FilledButton(
              key: const ValueKey('share-share'),
              onPressed: sharing ? null : onShare,
              style: FilledButton.styleFrom(shape: const StadiumBorder()),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [const Icon(YoinSymbols.share), const SizedBox(width: 8), Text(strings.shareAction)],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// Now Playing's share opener (Android LocalShareSheetHost): About's row
/// sheet and a note's edit card ask it for the sheet on their tab. Null
/// outside Now Playing: the About row and the note card then offer no Share.
class NowPlayingShareScope extends InheritedWidget {
  const NowPlayingShareScope({super.key, required this.open, required super.child});

  final void Function(ShareRequest request) open;

  static void Function(ShareRequest request)? maybeOf(BuildContext context) =>
      context.getInheritedWidgetOfExactType<NowPlayingShareScope>()?.open;

  @override
  bool updateShouldNotify(NowPlayingShareScope oldWidget) => false;
}
