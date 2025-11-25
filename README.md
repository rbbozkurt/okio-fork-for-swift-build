# okio-fork-for-swift-build

![Research](https://jb.gg/badges/research-plastic.svg)
[![JetBrains incubator project](https://jb.gg/badges/incubator.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)
[![GitHub license](https://img.shields.io/github/license/square/okio)](LICENSE)

This repository is a **Swift Package Manager–compatible fork** of
[`square/okio`][okio].

It is used to prototype **Kotlin/Native integration within the Swift build system (`swift-build`)**, and to provide the Okio dependency required by the `kotlinx-io` SwiftPM fork.

The goal of this fork is *not* to modify the public API of Okio, but to:

- expose the library as a **Swift package** that can be resolved and built by a modified `swift-build`, and
- embed the required **Kotlin/Native (`konanc`) configuration** via `Package.swift`.

> **Important:** This repository is experimental and requires a custom version of
> `swift-build` that supports Kotlin build.
> It will **not** build with stock SwiftPM or Xcode toolchains.

---

## Upstream Project

Official library:

- **Repository:** [okio][okio]
- **Website:** https://square.github.io/okio/
- **Description:** A modern I/O library for Android, Java, and Kotlin Multiplatform.

This fork does not change Okio’s I/O functionality; it only provides the SwiftPM-compatibility layer needed for Kotlin/Native integration.

---

## What This Fork Adds

### 1. SwiftPM Manifest

A fully generated `Package.swift` allowing Okio to be resolved and built as a Swift package.

### 2. SwiftPM-Compatible Source Layout

Kotlin sources are exposed under `Sources/` using `.kt.swift` shims:

- Each original `.kt` file is mirrored as `<name>.kt.swift` so SwiftPM can discover it.
- During the build, `swift-build` restores these shims back to `.kt` and compiles them with `konanc`.

### 3. Embedded Kotlin/Native (`konanc`) Flags via `cSettings`

All required Kotlin/Native fragment configuration (e.g.
`-Xfragments`, `-Xfragment-refines`, `-Xfragment-sources`, `-Xmulti-platform`)
is encoded into:

```swift
cSettings: [
    .define("KOTLIN", to: "<konanc flags with __PACKAGE_DIR__ placeholder>")
]
```

## Requirements

To build this package successfully, you need:

- A working Kotlin/Native (konanc) toolchain

- A custom Swift build system (swift-build)

This fork will **not** build with unmodified SwiftPM/Xcode.

## JetBrains Notice

This repository is an experimental integration prototype maintained by members of the Kotlin Build Tools team.
It is intended solely for evaluating Kotlin/Native interoperability within the Swift build system.

This is not an official distribution of okio.
Functionality, structure, and stability may change at any time.

For the official project, visit:
[okio]

## Contributing

Read the [Contributing Guidelines](CONTRIBUTING.md).

## Code of Conduct
This project and the corresponding community are governed by the [JetBrains Open Source and Community Code of Conduct][jetbrains-oc-cc]. Please make sure you read it.

## License
okio-fork-for-swift-build is licensed under the [Apache 2.0 License](LICENSE).

[kotlinx-io]: https://github.com/Kotlin/kotlinx-io
[jetbrains-oc-cc]: https://confluence.jetbrains.com/display/ALL/JetBrains+Open+Source+and+Community+Code+of+Conduct
[okio]: https://github.com/square/okio
[okio-fork-for-swift-build]: https://github.com/rbbozkurt/okio-fork-for-swift-build
