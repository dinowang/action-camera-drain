# Action Camera Drain

A fast content-delivery app for action-camera users — offload footage from the camera's SD card onto the phone and then empty the card.

Target usage flow:

1. The user removes the memory card from the action camera.
2. The user inserts the card into a card reader.
3. The card reader is plugged into a USB peripheral that supports PD (Power Delivery).
4. The USB peripheral is connected to the phone.
5. The system auto-launches the Action Camera Drain app, or the user opens it manually.
6. The user picks or creates an upload destination.
7. The app uploads the card's contents, verifies them on completion, and asks the user whether to empty the card.
8. The card is ejected.

## Repository Layout

- `src/android/` — the only code module; a standard Gradle Android project (`:app`).
  - Package: `net.dinowang.actioncameradrain`
  - UI: Jetpack Compose (Material 3). Entry point: `app/src/main/java/.../MainActivity.kt`.
  - Theme code lives under `.../ui/theme/`.
- `docs/` — long-form documentation, with `docs/images/` and `docs/diagrams/` for assets.
- `.changes/` — curated change notes (see workflow below). Indexed by `.changes/README.md`.
- `.prompt/workflows/` — prompt files the developer `@`-references during Copilot sessions. Treat each invocation as a diff against the previous one.
- `action-camera-drain.code-workspace` — the canonical multi-root VS Code workspace; open this rather than the repo root for the correct project view.

## Build / Test / Lint

All Gradle commands must be run from `src/android/` (that is where `gradlew` and `settings.gradle.kts` live).

```bash
cd src/android
./gradlew assembleDebug              # build debug APK
./gradlew test                       # all JVM unit tests
./gradlew :app:testDebugUnitTest     # debug-variant unit tests
./gradlew connectedAndroidTest       # instrumented tests (requires device/emulator)
./gradlew lint                       # Android Lint
```

Run a single unit test:

```bash
./gradlew :app:testDebugUnitTest --tests "net.dinowang.actioncameradrain.ExampleUnitTest.<methodName>"
```

Run a single instrumented test:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=net.dinowang.actioncameradrain.ExampleInstrumentedTest#<methodName>
```

## Toolchain Constraints

These are pinned in `src/android/gradle/libs.versions.toml` and `app/build.gradle.kts`; do not bump casually:

- AGP `9.2.1`, Kotlin `2.2.10`, Compose BOM `2026.02.01`
- `compileSdk = 36` (with `minorApiLevel = 1`), `targetSdk = 36`, `minSdk = 34`
- `JavaVersion.VERSION_11` for `sourceCompatibility` / `targetCompatibility`
- All dependency versions go through the `libs.versions.toml` version catalog — add to the catalog first, then reference as `libs.xxx` in `app/build.gradle.kts`. Do not hard-code coordinates in `build.gradle.kts`.

## Conventions Specific To This Repo

### Change notes (`.changes/`)

- Filenames: `YYYYMMDD-NN-PURPOSE.md`, uppercase purpose (e.g., `20240624-01-API-OVERVIEW.md`). `NN` starts at `01` per day.
- `.changes/README.md` is the index, with **Active / Canonical** and **Obsolete / Merged** sections — update it whenever you add, merge, or retire a note.
- Consolidation follows `.prompt/workflows/changes-consolidation.md`: a two-phase flow (Analyze & Propose → Apply only after explicit approval). When acting as the consolidator, do not modify anything outside `.changes/`.
- Legacy: if a `.history/` directory ever appears, rename it to `.changes/`.

### Prompt files (`.prompt/`)

- The developer authors prompts here and `@`-references them in chat. The same file may be re-sent across sessions, so diff against the previous revision and plan work based on the delta.
- When the developer commits work triggered by a prompt file, include that prompt file in the same commit.

### Markdown docs

- Use GFM. For ordered lists with sub-content (code blocks, tables), indent the sub-content by **3 spaces** under `N. ` so numbering does not reset.
- Place referenced images at `docs/images/<doc-basename>/...` (or alongside the doc under an `images/<basename>/` subfolder for non-`docs/` markdown). Keep Diagram-as-Code sources next to their rendered images.
- Never use Mermaid inside a fenced code block (renders poorly in dark themes), and avoid ASCII art — use the `diagram-maker` skill instead.
- Documents with `convertTo: html` or `html-embedded` in front-matter must be re-rendered to a sibling `.html` after every edit.

### Git

- **Never** run `git push` autonomously. Do not auto-`commit && push`; let the developer review and decide.
- Co-author trailer (when you do create a commit on request):
  `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`

### Safety

- Never print passwords, API keys, or other secrets to the screen — mask them or point the developer at the `.env` file.
- Fact-check assertions about API support, version compatibility, and conventions against official docs or source before writing them down; mark anything you cannot verify as "unverified" or leave it out.
