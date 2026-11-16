/// The scroll seams in one import: the chrome seam ([ChromeSeam]) and what
/// marks content for it — [SeamDissolve] on graphics, [SeamFade] on text —
/// plus the user's [SeamTopStyle].
library;

export '../../../home/widgets/seam.dart' show SeamFade;
export 'chrome_seam.dart' show ChromeSeam, SeamBackground;
export 'seam_dissolve.dart' show SeamDissolve, SeamPrint;
export 'seam_style.dart' show SeamTopStyle, seamTopStyleProvider;
