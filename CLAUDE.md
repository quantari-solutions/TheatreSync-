# Instructions for Claude Code

## Owner's standing permissions
- **Claude may merge pull requests into `main` itself once all CI checks pass**
  (granted by the owner on 2026-11-18). Before merging: CI green on the PR's
  current head, no merge conflicts, `./scripts/run-tests.sh` passing.
- Never merge with failing or pending checks. Never force-push `main`.
- **Releases go out through the app's own updater** (owner, 2026-11-21: "from now on all updates happen
  from the app unless I ask otherwise"): once a version's PR is green and merged, publish it with `scripts/publish-release.sh releases "notes" https://raw.githubusercontent.com/quantari-solutions/TheatreSync-/main/releases`,
  merge that PR, and the phone offers it in Settings → About → Update. Don't send APK files in chat
  unless the owner asks. Keep only the newest APK in `releases/`. See docs/RELEASING.md.
- **Scheduled tasks / check-ins must be cheap** (owner, 2026-11-21): don't schedule check-ins into this
  (expensive) session. GitHub PR events already wake the session for CI results. If a scheduled task is
  really needed, make it start a fresh session and set its model to Haiku (`claude-haiku-5-5`).
- The owner is not technical: explain results in plain language, and keep
  physical tests to simple steps + a screenshot (see docs/PHYSICAL-TESTING.md).

## Workflow (cloud-first — see docs/CLOUD-WORKFLOW.md)
1. Read the repo and PROJECT-STATUS.md first.
2. Work on a feature branch; never commit directly to `main`.
3. Run `./scripts/run-tests.sh` (and `./scripts/build-android.sh` for Android changes).
4. Update docs and PROJECT-STATUS.md in the same PR.
5. Push, open a PR, wait for CI, then merge per the permission above.
6. Summarise changes, test results and limitations to the owner.

## Honesty rules
- Cloud builds do not verify Bluetooth, audio capture, Cast or latency. Only a
  reported physical test (HW-xx) does.
- iOS code is only statically checked on Linux. Never claim it compiled until
  the owner reports a successful Swift Playgrounds (or Xcode) build.
- No secrets, keystores or API keys in the repo — use GitHub/environment secrets.

## Protocol changes
`contracts/` is the source of truth. Change the schema + fixtures first, then the
Kotlin (`android/sync-core`) and Swift (`ios/TheatreSync.swiftpm/Shared`) mirrors. `scripts/test-ios-shared.sh` compiles and tests the Swift mirror on Linux.
