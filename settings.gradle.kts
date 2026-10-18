// SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
// SPDX-License-Identifier: GPL-3.0-or-later

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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Rhythm"
include(":app")
include(":macrobenchmark")

// LaHo: bit-perfect USB Audio Class 2.0 driver, vendored from decent-player (MIT).
// libFLAC comes from the third_party/flac submodule.
include(":decent-usb-audio-driver")
project(":decent-usb-audio-driver").projectDir = file("third_party/decent-usb-audio/usb-audio-driver")
include(":decent-usb-audio-wrapper-media3")
project(":decent-usb-audio-wrapper-media3").projectDir = file("third_party/decent-usb-audio/usb-audio-wrapper-media3")
