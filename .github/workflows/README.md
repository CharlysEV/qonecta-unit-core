# CI

Android CI runs unit tests (`testGithubDebugUnitTest`) and builds the `github` debug and optimized release variants (`assembleGithubDebug`, `assembleGithubRelease`) using JDK 17, SDK 36, NDK 29.0.14206865 and CMake 3.22.1. CI release artifacts are unsigned; the maintainer signs public APKs privately. No signing secrets are available to PRs.
