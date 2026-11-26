// Script for compiling build behavior. It is built in the build plug-in and cannot be modified currently.
import fs from 'fs';
import path from 'path';
import { hapTasks } from '@ohos/hvigor-ohos-plugin';

/**
 * Apple's libraries and the personal Apple Music player (docs/applemusic.md "Personal builds") may only be packed
 * by scripts/build_personal_am.sh, which sets YOIN_PERSONAL_AM_BUILD=1. A build that finds them in libs/ without
 * it stops, so a leftover copy can never reach a build that leaves the owner's devices.
 */
const PERSONAL_ONLY_LIBS = [
  'libappleMusicSDK.so',
  'libc++_ndk1.so',
  'libam.so',
  'libam_bridge.so',
  'libmusickit_probe_napi.so',
];

function refuseStrayPersonalLibs(): void {
  if (process.env.YOIN_PERSONAL_AM_BUILD === '1') {
    return;
  }
  const libs = path.join(__dirname, 'libs');
  if (!fs.existsSync(libs)) {
    return;
  }
  const found: string[] = [];
  for (const abi of fs.readdirSync(libs)) {
    for (const name of PERSONAL_ONLY_LIBS) {
      if (fs.existsSync(path.join(libs, abi, name))) {
        found.push(`${abi}/${name}`);
      }
    }
  }
  if (found.length > 0) {
    throw new Error(`entry/libs holds personal-build-only libraries (${found.join(', ')}). ` +
      'Remove them, or build through harmony/scripts/build_personal_am.sh (docs/applemusic.md).');
  }
}

refuseStrayPersonalLibs();

export default {
    system: hapTasks,  /* Built-in plugin of Hvigor. It cannot be modified. */
    plugins: []        /* Custom plugin to extend the functionality of Hvigor. */
}
