# Automatic crash reports

TheatreSync records every crash and every problem it recovers from. Once connected to
GitHub, it uploads each one as an issue in this repository (labels `crash` or
`handled-problem`) with no action from the owner. Claude reads them from there.

## One-time setup (about 3 minutes, on a computer or phone browser)

1. Open <https://github.com/settings/apps/new>.
2. **GitHub App name:** `TheatreSync crash reports` (any unique name).
3. **Homepage URL:** `https://github.com/quantari-solutions/TheatreSync-`
4. Untick **Webhook → Active**.
5. Tick **Enable Device Flow**.
6. **Repository permissions → Issues:** *Read and write*. Leave everything else at *No access*.
7. **Where can this GitHub App be installed?** *Only on this account*. Tap **Create GitHub App**.
8. On the next page, copy the **Client ID** (it looks like `Iv23li…`; it's public, not a secret) and send it to Claude.
9. On the left, tap **Install App**, then **Install** on `quantari-solutions`, and choose *Only select repositories → TheatreSync-*.

Claude then puts the Client ID into the next build (`githubClientId` in `android/gradle.properties`).
In the app: **☰ → Settings → Automatic crash reports → Connect GitHub**, enter the code shown, and approve. Done.

## How it's built

* OAuth **device flow**: no secret in the app or the repository. The token is limited to the
  GitHub App's permissions (Issues on this repository only).
* The token is encrypted with an Android Keystore key, and only ever sent to `api.github.com`.
* A crash is uploaded the next time the app opens (a crashing app can't send anything).
  Handled problems upload straight away: at most 5 a day, and each distinct problem only once per version.
* Before connecting, reports stay in **Settings → Crash reports** on the phone.
