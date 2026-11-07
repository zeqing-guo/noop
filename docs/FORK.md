# FORK.md — working on the zeqing-guo/noop fork

This repository is a fork of [`ryanbr/noop`](https://github.com/ryanbr/noop). [`AGENTS.md`](../AGENTS.md)
is upstream's guidance and still applies in full. This file adds what is specific to the fork: which
branch is which, how code gets merged, how a build reaches the phone, and the mistakes that made these
rules necessary. `CLAUDE.md` imports it, so every agent session reads it.

## Branches and remotes

| Name | What it is |
|---|---|
| `upstream` → `ryanbr/noop` | The original project. Fetch only (push is disabled). |
| `origin` → `zeqing-guo/noop` | The fork. |
| `main` (fork) | **The base for all work.** `upstream/main` plus the fork's own commits: the NOOP Dark redesign and the Simplified Chinese localization (merged 2026-11-07). |
| topic branches | One concern each, cut from an up-to-date `origin/main`, merged back into `main` when verified. |

`upstream/main` does **not** contain the redesign. Never start fork work from it, and never assume a
branch named in conversation is merged: check.

## Before starting any change

```bash
git fetch origin upstream
git status -sb                                   # which branch am I on, is it clean
git log --oneline origin/main..HEAD              # commits here that main does not have
git log --oneline upstream/main..origin/main     # the fork's own commits on main
```

If the current branch has commits that `main` lacks, stop and say so before building on it or moving
work off it. "Branch off main" is only correct when `main` contains everything the user is running.

## Developing

- Cut the branch from the fork's main: `git switch -c <topic> origin/main`.
- **Do not break the redesign's UI/UX.** Build on its components and tokens (`NoopCard`, `SectionHeader`,
  `SegmentBar`, `Palette`, `NoopType`, `Metrics`). Note that the redesigned Android `SectionHeader` draws
  no overline, so information that must be visible cannot live only in an overline.
- New user-facing copy needs every locale, including `values-zh` and the `zh-Hans`/`zh-Hant` entries in
  `Strand/Resources/Localizable.xcstrings`; `Tools/i18n_audit.py --ci origin/main` is the gate.
- Cross-platform parity from `AGENTS.md` still holds: an analytics or data change lands on Android and
  Swift in the same branch, with mirrored tests.

## Merging a topic branch into main

```bash
git fetch origin
git rebase origin/main                 # on the topic branch
# re-run the verification for what changed (below), then:
git switch main && git merge --ff-only <topic> && git push origin main
```

Fast-forward only, so `main` stays linear and nothing merges that was not verified on top of the
current `main`.

## Syncing upstream into main

```bash
git switch main && git fetch upstream
git merge upstream/main                # a merge, not a rebase: main is published
```

Upstream does not have the redesign, so conflicts in `android/app/src/main/java/com/noop/ui/**`, the
`strings*.xml` files and the design system are expected. Resolve them keeping the redesign's UI/UX and
the Chinese translations, while taking upstream's logic, analytics and protocol changes. Then build,
test, install on the phone (below), and push.

## Installing on the phone

The phone (Pixel 9 Pro XL, serial `49071FDAS003K0`) runs the **staging** app, `com.noop.whoop.staging`,
signed with the committed `android/fork-debug.keystore` (certificate SHA-256 `4511d903…a2fee3ab`).
Updating in place keeps the app's data; uninstalling wipes it.

```bash
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # no system JDK
./gradlew assembleFullRelease -PstagingRelease
# confirm the signer matches before installing:
~/Library/Android/sdk/build-tools/<ver>/apksigner verify --print-certs app/build/outputs/apk/full/release/app-full-release.apk
~/Library/Android/sdk/platform-tools/adb -s 49071FDAS003K0 install -r app/build/outputs/apk/full/release/app-full-release.apk
```

Never uninstall the staging app to get past a signature mismatch. A mismatch means the APK was signed
with the wrong key: find the right one.

## Verification notes for this machine

- Android: the JDK is Android Studio's (above). After switching branches, run
  `./gradlew syncRoomSchemaSnapshot … --no-build-cache --rerun-tasks`, or the Room schema oracle
  directory is missing and the test task fails at configuration.
- `xcodegen generate` rewrites `StrandiOS/Resources/Info.plist`; revert it unless the change is intended.
- `NOOPiOS` does not build locally (its scheme embeds a watch app that needs the watchOS 26.2 SDK), so the
  iOS leg of `app-build.yml` is the only iOS compile evidence.
- `Tools/test_check_source_references.py` fails 4 tests on macOS only (`/var` vs `/private/var` temp
  paths); CI on Ubuntu is unaffected.
- **Do not touch the working tree (stash, checkout, switch) while a background build or test run is in
  progress.** That run then tests whatever was on disk at the time, and its result proves nothing.

## Known red checks on main

As of the redesign merge (2026-11-07), `main` carries failures introduced by the redesign commits:
`tools-python` (three `test_home_i18n` tests: the `SectionHeader` overline assertion, raw UI copy in display
helpers, and Home resources missing de/es/fr/pt-PT) and `i18n-coverage` (a localized string concatenated at
`SleepScreen.kt:1415`). Fixing these is open work; update this section when they are fixed.

## Mistakes log

### 2026-11-07: work moved off the branch the user was running

- **What happened.** The weekly HR-zone change was written on `redesign/noop-dark`, the branch the
  installed phone build came from. When it was moved to "a new branch off main", nobody checked that
  `main` lacked the redesign: the redesign had been pushed only as a branch, never merged. The new branch
  carried the pre-redesign UI, and installing it would have rolled back the phone's UI. It was caught only
  at install time, when the user asked why the redesign was not in `main`.
- **Fix.** The change was rebased onto `redesign/noop-dark`, and the redesign was fast-forwarded into
  `main` and pushed.
- **Rule.** Before choosing a base branch, compare it with what the user is running (the section
  "Before starting any change" above), and say plainly what the chosen base lacks.

### 2026-11-07: a verification run whose evidence did not count

- **What happened.** The working tree was stashed and restored for a quick comparison while a background
  `xcodebuild` was running, so that build compiled a mix of old and new files.
- **Fix.** The build was re-run with the tree untouched.
- **Rule.** See the last bullet under "Verification notes".

### 2026-11-07: the phone's signing key was not known

- **What happened.** The installed staging app's certificate did not match `~/.android/debug.keystore`,
  and the first instinct was a separate debug app or a reinstall, which would have wiped data. The real
  key was `android/fork-debug.keystore`, which the Gradle debug signing config picks up automatically.
- **Rule.** See "Installing on the phone".
