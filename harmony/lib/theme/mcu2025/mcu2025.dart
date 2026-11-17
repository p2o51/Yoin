/// Material Color Utilities SPEC_2025, ported from the Kotlin MCU sources
/// the Android app vendors (`app/src/main/java/{dynamiccolor,palettes,…}`).
///
/// `package:material_color_utilities` 0.13.0 (pinned by Flutter) only has the
/// 2021 spec. This port carries what SPEC_2025 needs on top of the package's
/// HCT solver: the 2025 role rules and tone solver ([ColorSpec2025]), a
/// spec- and platform-aware [DynamicScheme], [ToneDeltaPair] with
/// [DeltaConstraint], the yellow-aware [TonalPalette] and Kotlin's [Contrast].
/// Golden values from the Kotlin code live in
/// `test/theme/fixtures/spec2025_expressive_golden.json`.
library;

export 'color_spec_2025.dart';
export 'contrast.dart';
export 'dynamic_color.dart';
export 'dynamic_scheme.dart';
export 'tonal_palette.dart';
