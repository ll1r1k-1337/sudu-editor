# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Sudu Editor is an experimental, portable code editor written in Java/Kotlin that targets both Web (via TeaVM) and Desktop (JVM with native OpenGL ES via ANGLE). The same Java sources compile to JavaScript for the browser and to a native-looking Windows app. The goal is to investigate performance limits of this approach versus editors like Monaco/VSCode.

## Prerequisites (must build locally first)

This project depends on two forks that are NOT on Maven Central — they must be cloned and `mvn install`'d into the local Maven repo before anything in this repo will build:

1. **TeaVM compiler fork** (`kirillp/teavm`, branch `master`): pinned with internal patches. Build with `./gradlew publishToMavenLocal -x test`. The version is referenced as `${teavm.version}` = `0.9.0-SNAPSHOT` in the root `pom.xml`.
2. **ANTLR4 TeaVM-compatible fork** (`pertsevpv/antlr4-teavm-compatible`): build with `mvn install -DskipTests`. Referenced as `${antlr.version}` = `4.13.2-SNAPSHOT`.

The CI workflow `.github/workflows/build.yml` shows the canonical sequence: checkout both forks, `publishToMavenLocal`/`mvn install` them, then build this repo.

JDK 17+ is required for sources (`maven.compiler.source=17`), JDK 21 is used by CI, and the desktop native image profile uses GraalVM.

## Common build commands

All builds are Maven-based from the repo root. Use `-am` to build the dependency chain. Module-specific run configs live in `.run/*.run.xml`.

| Target | Command | Notes |
|---|---|---|
| Web fullscreen editor | `mvn package -am -pl demo-edit-js -P release` | TeaVM → JS, output in `demo-edit-js/target/` |
| Tabbed AI demo (web) | `mvn package -am -pl ai-demo-js -P release` | |
| ESM module (npm `@sudu-ide/editor`) | `mvn package -am -pl demo-edit-es-module -P release` | Produces `module/src/editor.js`, `worker.js` |
| Code review module | `mvn package -am -pl code-review-module` | |
| Desktop (Windows native) | `mvn package -am -pl demo-edit-jvm` | Then run `org.sudu.experiments.DemoEditJvm`; needs MSBuild + ANGLE dll |
| Desktop native (Graal) | `mvn package -am -pl demo-edit-jvm -P native-FolderDiffTestJvm` | Builds `FolderDiffTestJvm.exe` via GraalVM native-image |
| Native graphics-jvm | `mvn package -am -pl graphics-jvm` | Compiles JNI C++ via MSBuild; needs `%MSBuildPath%` on PATH |
| WASM test | `mvn package -am -pl wasm-test antrun:run@create-wasm-dir antrun:run@build-wasm-module` | Needs emscripten/clang on PATH |
| Fonts/icons (one-off) | `mvn package -am -pl :codicon -pl :fonts -P downloadFont` | Downloads font binaries used by JVM/Web targets |
| Clean | `mvn clean` | |

Webpack sample (after building `demo-edit-es-module`):
```
cd demo-edit-es-module/webpack-test
npm run i
npm run sudu-editor-sample-compile
```
Requires Node 18+ (CI uses 20).

## Running tests

JUnit 5 (`jupiter.version=5.9.0`) is used across modules. Tests live in `<module>/src/test/java/`.

- Run all tests: `mvn test`
- Run a single module's tests: `mvn test -pl demo-edit`
- Run a single test class: `mvn test -pl parser -Dtest=IntervalTreeTest`
- Run a single method: `mvn test -pl parser -Dtest=IntervalTreeTest#methodName`

Heaviest test suites are in `demo-edit`, `parser`, and `graphics`. Tests under `parser/src/test/resources/java/` are Java source fixtures used by resolver tests, not standalone tests.

## Architecture — the big picture

### Cross-platform via "stub vs. real" graphics layer

The codebase achieves Web/Desktop portability by splitting the rendering surface into three modules with a single shared API:

- **`graphics`** — Platform-agnostic editor framework: `Scene`, `SceneApi`, input, fonts, text, UI primitives. All editor and demo code targets this. It depends on `graphics-stub` (`<scope>provided</scope>`).
- **`graphics-stub`** — Compile-only stubs for `GLApi`, `Debug`, `TextDecoder`. Lets `graphics` compile without committing to a real GL implementation.
- **`graphics-common`** — Code shared between JS and JVM concrete implementations.
- **`graphics-js`** — TeaVM-specific implementation: WebGL bindings, JS interop (`js/` package), browser font/canvas APIs.
- **`graphics-jvm`** — Desktop implementation: JNI to ANGLE (libGLESv2.dll on Windows), `Application` (the main loop entry point), native window handling. Native C++ sources are built via MSBuild; the build uses `javac -h` to emit JNI headers to `target/javah/`.

`demo-edit` (editor core) only depends on `graphics` + `graphics-stub` (provided). Concrete platform targets (`demo-edit-js`, `demo-edit-jvm`, `demo-edit-es-module`) pull in the appropriate concrete graphics module.

### Main entry points

- **Web (fullscreen, `demo-edit-js`)**: `org.sudu.experiments.WebApp` (main), `org.sudu.experiments.WebWorker` (worker). Compiled by TeaVM. The TeaVM plugin produces `classes.js` and a separate `worker.js`. URL hash (`#wasmDemo`, `#diffDemo`, scene name) selects the scene via `TestSceneSelector`.
- **Web (embeddable ESM, `demo-edit-es-module`)**: `org.sudu.experiments.Editor_d_ts` (main), `WebWorker`. TeaVM output is concatenated with `ES6moduleExport.template.js.0`/`.1` prefix/suffix to form `module/src/editor.js`. The TS type surface is `module/editor.d.ts`. Embedding docs: `embedding.md`.
- **Desktop (`demo-edit-jvm`)**: `org.sudu.experiments.DemoEditJvm` calls `AngleDll.require()` then `Application.run(...)` from `graphics-jvm`.
- **Folder diff CLI (`demo-edit-jvm`)**: `org.sudu.experiments.FolderDiffTestJvm` — runs as a JVM app or via GraalVM native-image (see `runTest.cmd`, `performance.md`). Compared against the Node-based equivalent in `filediff-node-module`.

### Editor worker model

Heavy work (parsing, diffing, file walking) runs off the UI thread. Both Web Workers (browser) and dedicated JVM threads use the **same** `EditorWorker.execute(method, args, onResult)` dispatcher in `demo-edit/.../editor/worker/EditorWorker.java`. It dispatches by string method name to:
- Per-language parser proxies (`JavaProxy`, `CppProxy`, `TypeScriptProxy`, …) in `editor/worker/proxy/`
- Diff utilities (`DiffUtils.FIND_DIFFS`, etc.) in `editor/worker/diff/`
- `ScopeProxy.RESOLVE_ALL` for cross-file symbol resolution

`async*` method names indicate async-callback variants. When adding a new worker job, register it in both the `syncMethod`/`asyncMethod` switches and define the method name constant on the corresponding proxy.

Parsing strategy (size-dependent, see `parser/src/ParserScheme.md`): for files >10 KB the worker runs "first 100 lines" + structure parsing + viewport parsing + full parsing as a pipeline; for ≤10 KB it goes directly to full parsing.

### Parsers (ANTLR-generated)

- **`parser-generator`** — Tooling-only module. `GenerateParsers.java` is a `main` that runs ANTLR's `org.antlr.v4.Tool` over `parser-generator/src/main/resources/grammar/<lang>/*.g4` and writes generated lexers/parsers into `parser/src/main/java/org/sudu/experiments/parser/<lang>/gen/`. Run it manually after editing grammars; the generated sources are checked in.
- **`parser`** — Hand-written parser logic plus generated ANTLR output. One package per language (`java/`, `cpp/`, `typescript/`, `javascript/`, `json/`, `html/`). Each language has `gen/` (generated), `parser/` (wrappers), and often `model/` and `walker/` for semantic analysis.
- **`parser-common`** — Shared helpers: `Interval`, `IntervalTree`, error listeners, `SplitToken`. Each language defines a `*SplitRules` class that controls how text is split into lexical tokens for incremental highlighting.
- **`parser-activity`** — A separate (UML) activity-diagram parser; controlled by the `generateActivity` flag in `GenerateParsers`.

### Diff engine

- **`diff-model`** — Pure model classes for folder/file diffs.
- **`filediff-node-module`** — Packages the diff engine as a Node-callable module (`diffEngine.mjs` / `diffEngineWorker.mjs`); exported from `@sudu-ide/editor` under `./engine`.
- Folder-diff perf benchmarks (`runTest.cmd`, `parseLogs.cmd`, `performance.md`) compare Java, GraalVM native, and Node.

### Other modules

- **`common-editor-js`** — Shared TeaVM/JS glue used by both `demo-edit-es-module` and `code-review-module`.
- **`common-ts-types`** — Pure `.d.ts` definitions published as `@sudu-ide/types`; depended on by the ESM module packages.
- **`ai-demo-js`** — Tabbed-editor web demo.
- **`code-review-module`** — Web-based code review view; published as `@sudu-ide/code-review` (see `.github/workflows/build.yml`).
- **`web-console-js`** / **`spring-demo`** / **`ssh-module`** — Console UI, Spring sample backend, and SSH client integration.
- **`demo-test-scenes`** — `TestSceneSelector` registry of demo scenes shared between web and desktop entry points.
- **`angle-dll`** — Houses the bundled `libGLESv2.dll` (gitignored, copy via `CopyAngleToResources`) and `angle-include` carries the GLES headers used by native graphics-jvm code.
- **`fonts`** / **`codicon`** — Font resource modules; the actual TTF files are downloaded by the `downloadFont` profile and not committed.

## Conventions and gotchas

- **All Java packages live under `org.sudu.experiments`**. Stick to that namespace for new code.
- **TeaVM constraints apply to anything reachable from `WebApp`/`WebWorker`/`Editor_d_ts`/etc.** Avoid `java.io.File`, reflection-heavy code, threads, etc. on the web reachability graph. JVM-only utilities should live in `*-jvm` modules.
- **Generated ANTLR sources are committed.** If you change a `.g4`, re-run `GenerateParsers` (the `main` in `parser-generator`) and commit the regenerated `gen/` files.
- **Worker job registration is a two-step pattern**: add a method name constant on the proxy class, then wire it into `EditorWorker.syncMethod`/`asyncMethod`. Arguments are passed as `Object[]` and parsed via `ArgsCast`.
- **TeaVM compile flags** are tuned per profile in each `*-js`/`*-es-module` pom (`teavm.minifying`, `teavm.optimizationLevel`, `teavm.sourceMaps`). The `release` profile sets `minifying=true`, `optimizationLevel=ADVANCED`.
- **The ESM module's `editor.js`/`worker.js` are gitignored build outputs** — never edit them directly; they're regenerated from TeaVM output and the `ES6moduleExport.template.js.*` prefix/suffix.
- **NPM packaging is done in CI only** (publishing tags `v0.X.Y[-rc|-beta|-alphaN]` triggers `.github/workflows/build.yml`). Three packages publish in sequence: `@sudu-ide/types` → `@sudu-ide/editor` → `@sudu-ide/code-review`. Bump versions consistently across `common-ts-types/package.json`, `demo-edit-es-module/module/package.json`, and `code-review-module/module/package.json`.
