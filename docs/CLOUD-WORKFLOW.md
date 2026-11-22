# Cloud-first workflow

The Windows PC is **optional**. Everything below runs in Claude Code Cloud or GitHub Actions.

## Your normal loop

1. Open <https://claude.ai/code>, pick the TheatreSync repo, describe the task.
2. Claude works on a feature branch, runs `./scripts/run-tests.sh`, updates
   `PROJECT-STATUS.md`, pushes, and opens a pull request.
3. On the PR page, GitHub Actions runs:
   * **Android** — unit tests, lint, debug APK build ([workflow](../.github/workflows/android.yml)).
   * **Contracts & iOS source** — schema validation + iOS static checks ([workflow](../.github/workflows/shared.yml)).
4. Review, then merge. Nothing is merged to `main` without you.
5. Only when asked: install the APK on the Motorola, or open the iOS package on the iPad.

## Getting the APK onto the phone (no PC)

1. On the phone, open the PR in the GitHub app or browser → **Checks** → **Android** → run summary.
2. Under **Artifacts**, download `TheatreSync-apk` (a `.zip`; you must be signed in to GitHub). Install `TheatreSync.apk`, the optimised build.
3. Open the zip in Files, tap `TheatreSync-debug.apk`, allow "Install unknown apps" for that app once.

## Scripts

| Script | Purpose |
|---|---|
| `scripts/setup-android.sh` | Installs Android SDK cmdline-tools, platform 35, build-tools (idempotent) |
| `scripts/test-android.sh` | JVM unit tests + Android lint |
| `scripts/build-android.sh` | Debug APK → `dist/TheatreSync-debug.apk` |
| `scripts/validate-contracts.sh` | JSON Schema validation of `contracts/` |
| `scripts/validate-ios-source.sh` | Static iOS checks (**not a compile**) |
| `scripts/ios-sim.sh` | **Real iOS compile** with Xcode on GitHub's Mac (workflow `iOS`), then launch on simulated iPhone and iPad with screenshots (a small copy in the log). No Bluetooth or Chromecast in the simulator |
| `scripts/run-tests.sh` | All of the above except the APK build |

## Network & secrets

* Builds need only: Google Maven / dl.google.com, Maven Central, Gradle Plugin Portal,
  services.gradle.org, PyPI.
* No secrets are needed yet. Future signing keys / API keys go in **GitHub Actions
  secrets** or the Claude environment's secret store — never in the repository.
  `.gitignore` blocks `*.jks`, `*.keystore`, `.env`, `*.p12`.

## When a Mac would be needed

Only for: App Store / TestFlight distribution, signing with a paid developer account,
or iOS features Swift Playgrounds doesn't support. Smallest option at that point: a
macOS GitHub Actions runner (pay-per-minute) for builds, no Mac purchase required.
