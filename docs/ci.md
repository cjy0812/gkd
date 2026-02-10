# CI buildability contract

This repository includes a lightweight GitHub Actions workflow to validate that the project **compiles** without requiring a local Android environment.

## What CI guarantees
- ✅ Gradle can configure the project.
- ✅ Kotlin/Android sources compile by building the **debug** APK (`:app:assembleDebug`).
- ✅ Obvious type/API errors are caught early.

## What CI does NOT guarantee
- ❌ No emulator/device runs.
- ❌ No UI automation or end-to-end tests.
- ❌ No release signing (secrets are not required).

## How contributors can rely on CI
1. Open a PR or push a branch.
2. The `CI` workflow runs the debug build on GitHub-hosted runners.
3. If `CI` passes, the code at least compiles and is safe to review without local setup.
