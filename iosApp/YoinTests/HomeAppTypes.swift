@testable import Yoin

// The app's Home types under names of their own: a test importing YoinShared too sees two `HomeLayout`s,
// `HomeSection`s… (the shared core's and the app's), and `Yoin.` names the app's theme enum, not the module.
typealias AppHomeLayout = HomeLayout
typealias AppHomeSection = HomeSection
typealias AppHomeRowPreset = HomeRowPreset
typealias AppHomeLayoutCodec = HomeLayoutCodec
