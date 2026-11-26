import YoinShared

// The shared core's edit types for tests that don't import YoinShared (where `HomeSection`, `HomeLayout`… would
// be ambiguous with the app's).
typealias SharedExitReason = HomeEditExitReason
typealias SharedHitBlank = HomeEditHitBlank
typealias SharedHitBlock = HomeEditHitBlock
typealias SharedHitHandle = HomeEditHitHandle
typealias SharedHitExcluded = HomeEditHitExcluded
