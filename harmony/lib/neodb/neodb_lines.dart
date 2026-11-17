/// NeoDB's quiet lines: the album rate sheet's last line (Android
/// AlbumDetailComponents AlbumNeoDbLine) and the Memories diary's
/// (MemoryDiary DiaryNeoDbLine). Both end in "What others say", which opens
/// [NeoDbVoicesScreen] signed in or not.
library;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../app/providers.dart';
import '../data/model/model.dart';
import '../nowplaying/haptics.dart';
import '../settings/settings_components.dart' show SpringReveal;
import '../settings/settings_pane.dart';
import '../settings/settings_screen.dart' show openSettingsAt;
import '../theme/theme.dart';
import '../theme/tokens.dart';
import '../ui/component/spring_cross_fade.dart';
import 'album_neodb_sync.dart';
import 'neodb_providers.dart';
import 'neodb_strings.dart';
import 'neodb_voices_screen.dart';

/// The words after "NeoDB" on the sheet's last line. Pending and idle say nothing.
String albumNeoDbLabel(AlbumNeoDbSync state, NeoDbStrings strings) => switch (state) {
  AlbumNeoDbSync.unknown || AlbumNeoDbSync.idle || AlbumNeoDbSync.pending => '',
  AlbumNeoDbSync.signedOut => strings.lineSignIn,
  AlbumNeoDbSync.syncing => strings.lineSyncing,
  AlbumNeoDbSync.synced => strings.lineSynced,
  AlbumNeoDbSync.failed => strings.lineFailed,
};

/// The diary's NeoDB words; blank where there is nothing to say.
String diaryNeoDbText(AlbumNeoDbSync state, NeoDbStrings strings) => switch (state) {
  AlbumNeoDbSync.unknown || AlbumNeoDbSync.signedOut || AlbumNeoDbSync.idle => '',
  AlbumNeoDbSync.pending => strings.diarySync,
  AlbumNeoDbSync.syncing => strings.diarySyncing,
  AlbumNeoDbSync.synced => strings.diarySynced,
  AlbumNeoDbSync.failed => strings.diaryFailed,
};

/// What the sheet's line shows while words are typed but not yet kept: a
/// stale "Synced" becomes a waiting change (AlbumDetailViewModel.refreshNeoDbLine).
AlbumNeoDbSync albumNeoDbShown(AlbumNeoDbSync state, {required bool unsaved}) =>
    unsaved && (state == AlbumNeoDbSync.synced || state == AlbumNeoDbSync.idle) ? AlbumNeoDbSync.pending : state;

/// The rate sheet's last line: "NeoDB" and where it stands. Signed out, the
/// line is the action (Settings › NeoDB, Settings underneath); a failure gets
/// a Retry. Left out until the state is read.
class AlbumNeoDbLine extends ConsumerWidget {
  const AlbumNeoDbLine({super.key, required this.albumId, required this.unsaved, required this.onVoices});

  final MediaId albumId;

  /// The sheet holds words not yet kept.
  final bool unsaved;
  final VoidCallback onVoices;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = NeoDbStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final shown = albumNeoDbShown(ref.watch(albumNeoDbProvider(albumId)), unsaved: unsaved);
    final signedOut = shown == AlbumNeoDbSync.signedOut;
    const shape = RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(NeoDbTokens.sheetLineCorner)));
    final label = albumNeoDbLabel(shown, strings);
    final line = Material(
      type: MaterialType.transparency,
      shape: shape,
      child: InkWell(
        customBorder: shape,
        onTap: signedOut
            ? () {
                YoinHaptics.click();
                openSettingsAt(context, const NeoDbPane());
              }
            : null,
        child: Padding(
          padding: const EdgeInsets.symmetric(
            horizontal: NeoDbTokens.sheetLinePaddingH,
            vertical: NeoDbTokens.sheetLinePaddingV,
          ),
          child: Row(
            children: [
              Text(
                strings.name,
                style: text.labelLarge?.copyWith(color: colors.onSurface, fontWeight: FontWeight.w600),
              ),
              const SizedBox(width: NeoDbTokens.sheetLineGap),
              Flexible(
                child: SpringCrossFade(
                  childKey: shown,
                  fit: StackFit.loose,
                  child: shown == AlbumNeoDbSync.failed
                      ? Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Flexible(
                              child: Text(label, style: text.bodyMedium?.copyWith(color: colors.onSurfaceVariant)),
                            ),
                            TextButton(
                              key: const ValueKey('neodb-line-retry'),
                              onPressed: () => ref
                                  .read(neoDbPushProvider.notifier)
                                  .push(albumId, failureMessage: strings.albumSyncFailed),
                              child: Text(strings.retry),
                            ),
                          ],
                        )
                      : Text(
                          label,
                          style: text.bodyMedium?.copyWith(color: signedOut ? colors.primary : colors.onSurfaceVariant),
                        ),
                ),
              ),
              const Spacer(),
              TextButton(
                key: const ValueKey('neodb-voices'),
                onPressed: () {
                  YoinHaptics.click();
                  onVoices();
                },
                child: Text(strings.voicesPeek),
              ),
            ],
          ),
        ),
      ),
    );
    // The gap above folds with the line, so the sheet's end doesn't hold empty room while it reads.
    return SpringReveal(
      visible: shown != AlbumNeoDbSync.unknown,
      child: Padding(
        padding: const EdgeInsets.only(top: NeoDbTokens.sheetLineTop),
        child: line,
      ),
    );
  }
}

/// The diary's last line under Go to album: NeoDB's state (signed in only;
/// sync is automatic, so it only reports, and a waiting change is a primary
/// "Sync to NeoDB" to tap), and beside it "What others say". Its 48 dp slot
/// is kept while signed in, so the diary's end never jumps as it reads.
class MemoryDiaryNeoDbRow extends ConsumerWidget {
  const MemoryDiaryNeoDbRow({
    super.key,
    required this.albumId,
    required this.albumName,
    this.artistName,
    this.coverArt,
    required this.interactive,
  });

  final MediaId albumId;
  final String albumName;
  final String? artistName;
  final CoverRef? coverArt;
  final bool interactive;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = NeoDbStrings.of(context);
    final configured = ref.watch(neoDbAccountProvider).value?.configured ?? false;
    final style = gsf(13, weight: 500, height: 1.3, letterSpacing: 0);
    // Wraps on a narrow window (a failure line beside "What others say" is wide).
    return Wrap(
      alignment: WrapAlignment.center,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        if (configured)
          _DiaryNeoDbState(
            albumId: albumId,
            interactive: interactive,
            onPush: () => pushMemoryAlbumToNeoDb(context, ref, albumId),
            style: style,
          ),
        TextButton(
          key: const ValueKey('diary-neodb-voices'),
          onPressed: interactive
              ? () {
                  YoinHaptics.click();
                  final cover = coverArt;
                  final source = ref.read(activeSourceProvider).value;
                  openNeoDbVoices(
                    context,
                    albumName: albumName,
                    artistName: artistName,
                    coverUrl: cover == null ? null : source?.resolveCoverUrl(cover),
                  );
                }
              : null,
          child: Text(strings.voicesPeek, style: style),
        ),
      ],
    );
  }
}

/// The diary's push (MemoriesViewModel.pushToNeoDb): signed out, the one-shot
/// "Sign in to NeoDB first" with Sign in (Settings › NeoDB); a failure reports
/// once and leaves the line on Retry.
Future<void> pushMemoryAlbumToNeoDb(BuildContext context, WidgetRef ref, MediaId albumId) async {
  final strings = NeoDbStrings.of(context);
  final messenger = ref.read(neoDbMessengerProvider);
  final outcome = await ref.read(neoDbPushProvider.notifier).push(albumId, failureMessage: strings.snackbarSyncFailed);
  if (outcome != NeoDbPushOutcome.notConfigured) return;
  messenger(
    strings.snackbarSignIn,
    actionLabel: strings.snackbarSignInAction,
    onAction: () {
      if (context.mounted) openSettingsAt(context, const NeoDbPane());
    },
  );
}

class _DiaryNeoDbState extends ConsumerWidget {
  const _DiaryNeoDbState({required this.albumId, required this.interactive, required this.onPush, required this.style});

  final MediaId albumId;
  final bool interactive;
  final VoidCallback onPush;
  final TextStyle style;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final strings = NeoDbStrings.of(context);
    final colors = Theme.of(context).colorScheme;
    final state = ref.watch(albumNeoDbProvider(albumId));
    final pending = state == AlbumNeoDbSync.pending;
    final Widget content = state == AlbumNeoDbSync.failed
        ? Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(strings.diaryFailed, style: style.copyWith(color: colors.onSurfaceVariant)),
              TextButton(
                key: const ValueKey('diary-neodb-retry'),
                onPressed: interactive ? onPush : null,
                child: Text(strings.retry),
              ),
            ],
          )
        : Text(
            diaryNeoDbText(state, strings),
            style: style.copyWith(color: pending ? colors.primary : colors.onSurfaceVariant),
          );
    final slot = SizedBox(
      height: NeoDbTokens.diaryLineHeight,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: NeoDbTokens.diaryLinePaddingH),
        child: Center(
          widthFactor: 1,
          child: SpringCrossFade(childKey: state, fit: StackFit.loose, child: content),
        ),
      ),
    );
    if (!pending) return slot;
    return Material(
      type: MaterialType.transparency,
      shape: const StadiumBorder(),
      child: InkWell(
        key: const ValueKey('diary-neodb-sync'),
        customBorder: const StadiumBorder(),
        onTap: interactive
            ? () {
                YoinHaptics.click();
                onPush();
              }
            : null,
        child: slot,
      ),
    );
  }
}
