// swift-tools-version: 6.0
// Yoin Symbols for iOS: the asset catalog of custom SF Symbols plus a typed enum.
// Mirrors android/ (Maven io.github.p2o51:yoin-symbols) and flutter/ (pub yoin_symbols).
// In the real repo this folder would be the repo root's Package.swift + ios/Sources,
// so SwiftPM can depend on it by URL and tag:
//   .package(url: "https://github.com/p2o51/yoin-symbols", from: "1.x.y")
import PackageDescription

let package = Package(
    name: "YoinSymbols",
    platforms: [.iOS(.v17), .macOS(.v14), .tvOS(.v17), .watchOS(.v10), .visionOS(.v1)],
    products: [
        .library(name: "YoinSymbols", targets: ["YoinSymbols"]),
    ],
    targets: [
        .target(
            name: "YoinSymbols",
            resources: [.process("Resources")]
        ),
    ]
)
