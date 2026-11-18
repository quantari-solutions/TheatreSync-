# Quality audit against industry standards

_Audit date: 2026-11-18 · App version 0.3.1_

## Standards used

| Area | Standard |
|---|---|
| Android quality | Google [Core app quality guidelines](https://developer.android.com/docs/quality-guidelines/archive/core/core-app-quality-2026-03-20) (March 2026): 48 dp touch targets, 4.5:1 text contrast, standard back navigation, notifications, performance and battery |
| Accessibility | WCAG 2.1 AA (contrast, labels, headings, live regions, text resize to 200 %), Google Accessibility Test Framework |
| Security | [OWASP MASVS v2](https://opensecurityarchitecture.org/frameworks/owasp-masvs-v2/controls): STORAGE, NETWORK, PLATFORM, CODE, RESILIENCE |
| iOS | Apple Human Interface Guidelines: 44 pt targets, Dynamic Type, VoiceOver labels |

## Findings and fixes

| # | Area | Finding | Fix | Verified by |
|---|---|---|---|---|
| 1 | Contrast | Footer text 2.9:1 (needs 4.5:1) | Full-strength colour (6.0:1) | Contrast calculation for every colour pair |
| 2 | Touch targets | Round buttons 44 dp, some pills 36–40 dp | Minimum 48 dp everywhere | Accessibility Test Framework on emulator |
| 3 | Screen reader | Emoji read aloud; play/pause unlabeled; no headings; errors silent | Decorative emoji hidden; labels ("Pause", "Back 10 seconds"); heading semantics; error card is a polite live region | Accessibility Test Framework |
| 4 | Large text | At 200 % font, title collided with "Change" and words broke mid-word | Adaptive row: action moves under the text at large sizes | Paparazzi screenshot at 200 % font |
| 5 | Tablets | Content stretched edge to edge | Centred column, max 600 dp | Paparazzi tablet screenshot |
| 6 | Back | No predictive-back opt-in | `enableOnBackInvokedCallback`; BackHandler for inner pages | Emulator tests (arrow + system back) |
| 7 | Icon & launch | Non-adaptive icon; no splash branding | Adaptive + round + themed (monochrome) icon; branded Android 12+ splash; dark window background (no white flash) | Build + lint |
| 8 | Battery | Screen kept on whenever the app was open | Removed; playback runs in a foreground service | Code review |
| 9 | Battery | Active network scan for TVs never stopped | Active only while choosing a TV; passive otherwise | Code review |
| 10 | Notification | No controls while the screen is locked | Play/Pause and Stop actions (to the non-exported service only) | Build + lint |
| 11 | MASVS-NETWORK | Movie server would answer non-local addresses | Only loopback, link-local and private ranges are served | Unit tests (allowed/refused address lists) |
| 12 | MASVS-CODE | Release builds not shrunk/obfuscated; release never built in CI | R8 minify + resource shrinking with keep rules; CI builds release | `assembleRelease` in build script and CI |
| 13 | Localisation | Some user-facing text written in code | Moved to `strings.xml` | Code review |
| 14 | iOS HIG | Titles didn't follow Dynamic Type; icon-only elements unlabeled; footer contrast | `@ScaledMetric` titles + header trait; decorative icons hidden; hints on lip-sync buttons; footer fixed | Static checks (iOS compile happens in Swift Playgrounds) |

## Deliberate deviations (documented, not defects)

| Guideline | Decision | Reason |
|---|---|---|
| Light *and* dark theme | Dark only | Used in a dark theatre room, where a light screen would dazzle; dark is also the Quantari brand. |
| MASVS-NETWORK-1 (TLS everywhere) | The movie server uses plain HTTP on the home network | Google's Chromecast Default Media Receiver and AVPlayer load the movie from a URL; a home network has no certificate to offer. It's limited to local addresses (#11) and serves only the chosen movie while it's playing. |
| MASVS-NETWORK-1 (certificate validation) | Chromecast TLS connections accept the device's self-signed certificate | Chromecasts use self-signed certificates on the LAN, and every Cast client does the same. The trust manager is scoped to that one socket and is never used for internet traffic. |
| MASVS-RESILIENCE | No anti-tamper or root detection | No accounts, payments or secrets. MASVS scopes resilience controls to high-value apps. |
| MASVS-STORAGE | Nothing sensitive stored | Only a lip-sync number and random device IDs. Backups and device transfer are disabled. |

## Automated quality gates (run on every pull request)

* Unit tests: sync engine, HTTP server, Cast protocol (including a fake-Chromecast end-to-end test), local-network filter
* Android lint with `abortOnError` (0 issues)
* Paparazzi: 15 screen states, including 200 % font and tablet, compared pixel by pixel (0.01 % tolerance)
* Emulator (Android 14, Pixel 6): UI flows, back navigation, on-device server, real audio sync, Google Accessibility Test Framework on every screen
* Release build with R8
* Contract schema validation; iOS shared code compiled and tested with Swift for Linux; documentation link check
