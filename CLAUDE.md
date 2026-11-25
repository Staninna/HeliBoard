# HeliBoard: droid fork

A personal fork of HeliBorg/HeliBoard, maintained as a droid app: modified the Android way and
shipped as signed GitHub releases. The fork makes HeliBoard feel like Gboard, adds a rewrite and
proofread feature powered by the user's SmolChat server (`../SmolChat`, contract in
`../SmolChat/docs/LOCAL_LLM_API.md`), and adds opt-in translate and GIF search. Load the droid skill
with `/droid` before any branch, sync, signing or release work. When it isn't available, the
ground rules below are enough to work safely.

## This repo

- `origin` is the fork (Staninna/HeliBoard), `upstream` is the original (HeliBorg/HeliBoard).
- `my-main` holds all fork work and is the GitHub default branch. `main` mirrors `upstream/main`.
- Install mode: replace. The fork keeps upstream's application ID `helium314.keyboard` and the name
  "HeliBoard".
- Release build: `:app:assembleRelease`, JDK 21, NDK 28 (pinned in `app/build.gradle.kts`). Unit
  tests: `./gradlew testDebugUnitTest`. AGP only creates unit test tasks for the debug build type,
  so the `testRunTestsUnitTest` that upstream's CI calls no longer exists. Seven tests fail in the
  debug variant by design. Each one returns early only when `BUILD_TYPE == "runTests"`: the
  `*Fails` tests in CharacterIteratorTest, InputLogicTest and StringUtilsTest, and XLinkTest,
  which calls live Codeberg URLs. Any other failure is real.
  `.github/workflows/release.yml` is the only release path. Upstream's other workflows are manual
  or PR-only.
- Local builds on the laptop: the system JDK 21 has no `javac`. Use Gradle's cached JetBrains JDK
  (`JAVA_HOME=~/.gradle/jdks/jetbrains_s_r_o_-21-amd64-linux.2`) and
  `ANDROID_HOME=~/Android/Sdk`.

## Ground rules

- **Commit to `my-main`**, directly or through a short-lived branch merged into it. Upstream reaches
  `my-main` by merge. `my-main` is published, so its history only grows: merge, never rebase.
- **`main` only fast-forwards from `upstream/main`.** If it has diverged, stop and ask.
- **A push to `my-main` is a release.** CI tags it `v<versionName>-fork.<n>` and publishes signed
  APKs. The tip commit's body becomes the release notes. Pushes that only touch `paths-ignore`
  paths or carry `[skip ci]` don't release.
- **Nothing goes upstream.** Upstream's `AI_USAGE.md` bans AI-made PRs and issues, so fork work
  stays in the fork.
- **Fork code lives in `helium314.keyboard.fork.*`** and in new files. Upstream files get small
  hooks marked `// fork:`, so upstream syncs stay easy to merge.
- **Every network request goes through the fork's network gate.** The app has the INTERNET
  permission only for opt-in features: SmolChat on 127.0.0.1, LibreTranslate, and GIF providers.
  The gate checks the feature toggle, the host and the scheme. Cleartext goes only to 127.0.0.1.
- **Test on the emulator, never on a physical phone.**
- **Verify before committing:** build the debug variant and run the unit tests. Before pushing,
  also build `:app:assembleRelease`: R8 problems only show up in the minified build.
- **Tags:** only `-fork.<n>` tags, so they never clash with upstream's. Pushed tags stay where they
  are. A failed release is fixed by pushing a fix to `my-main`.
- **Signing keys are hands-off.** The key lives outside the repo, and only the droid scripts
  (`signing-key.sh`, `build-signed.sh`) touch it. Never read, print or create keystores (`*.jks`,
  `*.keystore`, `keystore.properties`) or anything in the release-keys folder.
- **Commit identity:** pass the GitHub noreply address per commit with
  `git -c user.name=Staninna -c user.email=26458805+Staninna@users.noreply.github.com commit`.
  Never write git config.
