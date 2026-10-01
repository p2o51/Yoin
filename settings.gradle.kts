pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Yoin"
include(":app")
include(":playground:track-match")

// Yoin Symbols (io.github.p2o51:yoin-symbols) is built from source until it is on Maven Central:
// by default from a checkout next to this repo; CI passes -PyoinSymbolsDir=<path>.
val yoinSymbolsDir = providers.gradleProperty("yoinSymbolsDir").getOrElse("../yoin-symbols")
val yoinSymbolsBuild = file("$yoinSymbolsDir/android")
if (yoinSymbolsBuild.isDirectory) includeBuild(yoinSymbolsBuild)
