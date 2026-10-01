# Apple Music Android SDK

Unmodified libraries from Apple's Android MusicKit SDK 1.1.2 archive, downloaded
from the authenticated official developer download page on 2026-09-06:
https://download.developer.apple.com/Developer_Tools/Android_MusicKit/AndroidMusicKitSDK1.1.2.zip

- `musickitauth-release-1.1.2.aar` SHA-256:
  `c8f6f7ffec1baa5fb7fcdf04d3fcacfeb52c1de0c84c34b92a44dc6e431c4ef5`
- `mediaplayback-release-1.1.1.aar` SHA-256:
  `39bc28cbc2413c0ce0477c1ef4d3b9e51bb163a7723d930c46bc089715116e3c`

The package contains Apple's example app and API documentation. It requires
explicit loading of `c++_shared` and `appleMusicSDK` before controller creation.
The SDK's manifest predates Android 12, so Yoin declares the authentication URI
handler exported to receive Apple's callback. No Apple signing keys or tokens
are bundled. See `docs/integrations/apple-music.md` for current validation limits.
