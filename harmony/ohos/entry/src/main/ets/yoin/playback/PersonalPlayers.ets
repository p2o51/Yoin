import { common } from '@kit.AbilityKit';

/**
 * Players a personal build adds for URI schemes AVPlayer doesn't play (EnginePlayers). This copy adds none, so
 * every build made from the repository plays exactly what AVPlayer plays and Apple Music stays browse-only.
 *
 * `harmony/scripts/build_personal_am.sh` puts the owner's local copy (kept outside git, docs/applemusic.md
 * "Personal builds") in place of this file for the length of one build and restores it afterwards. Never commit
 * that copy.
 */
export function registerPersonalPlayers(context: common.UIAbilityContext): void {
}
