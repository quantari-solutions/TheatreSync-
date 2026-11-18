# Automatic crash reports

TheatreSync records every crash and every problem it recovers from. Once connected to
GitHub, it uploads each one as an issue in this repository (labels `crash` or
`handled-problem`) with no action from the owner. Claude reads them from there.

## One-time setup (about 2 minutes, all on the phone)

1. In TheatreSync: **☰ → Settings → Automatic crash reports**.
2. Tap **Create GitHub App**. GitHub opens with the name, homepage, "no webhook", "only this account"
   and **Issues: Read and write** already filled in. Scroll down, tick **Enable Device Flow**, tap **Create GitHub App**.
   (If GitHub says the name is taken, add anything to it, e.g. "TheatreSync crash reports 2".)
3. On the app's page, tap **Install App** → **Install** → *Only select repositories* → **TheatreSync-**.
4. Copy the **Client ID** (starts with `Iv`; it's public, not a secret), go back to TheatreSync, paste it, tap **Save Client ID**.
5. Tap **Connect GitHub**, then **Open GitHub**, enter the code shown and approve.
6. The app sends a **test report** straight away and shows "Uploaded #N". That issue appears in the repository: setup is proven.

Also send the Client ID to Claude so it's built into future versions (`githubClientId` in `android/gradle.properties`).

## How it's built

* OAuth **device flow**: no secret in the app or the repository. The token is limited to the
  GitHub App's permissions (Issues on this repository only).
* The token is encrypted with an Android Keystore key, and only ever sent to `api.github.com`.
* A crash is uploaded the next time the app opens (a crashing app can't send anything).
  Handled problems upload straight away: at most 5 a day, and each distinct problem only once per version.
* Before connecting, reports stay in **Settings → Crash reports** on the phone.
* If an upload is refused, Settings shows why in plain words (not installed on the repository, login expired, offline)
  and the report stays on the phone to retry.
* `CrashUploadTest` (emulator) runs the whole flow against a fake GitHub: Client ID check, device code, approval polling,
  Keystore token, issue creation with labels, retry without labels, and refusal handling.
