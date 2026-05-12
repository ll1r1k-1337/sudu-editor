# Repository Guidelines

## Project Structure & Module Organization
This is a multi-module Maven project for a portable Java/Kotlin-based editor targeting web and desktop. The root `pom.xml` owns the module list. Core editor, parser, and diff logic live in `demo-edit/`, `parser*/`, and `diff-model/`. Graphics are split across `graphics-common/`, `graphics/`, `graphics-js/`, `graphics-jvm/`, and `graphics-stub/`. Web and npm-facing outputs are in `demo-edit-js/`, `ai-demo-js/`, `web-console-js/`, `demo-edit-es-module/`, `code-review-module/`, and `filediff-node-module/`; package metadata is usually under each module's `module/` directory. Desktop and visual demos/tests are in `demo-edit-jvm/`, `demo-test-scenes/`, `graphics-test/`, and `graphics-jvm-tests/`. Java sources use `src/main/java`, tests use `src/test/java`, and web assets use `src/main/webapp`.

## Build, Test, and Development Commands
- `mvn test -pl demo-edit -am`: run JUnit tests for a focused module and required dependencies.
- `mvn package -am -pl demo-edit-es-module -P release`: build the ESM editor package.
- `mvn package -am -pl ai-demo-js -P release`: build the tabbed web demo.
- `mvn package -am -pl :codicon -pl :fonts -P downloadFont`: fetch packaged font/icon assets.
- `npm run i` then `npm run sudu-editor-sample-compile` in `demo-edit-es-module/webpack-test`: install and compile the webpack sample.

Web builds require locally installed patched TeaVM and TeaVM-compatible ANTLR as described in `README.md`. Some JVM/Windows graphics paths require MSBuild, ANGLE DLLs, or Emscripten.

## Coding Style & Naming Conventions
Use Java 17 source compatibility and match nearby formatting: two-space indentation, compact JUnit methods such as `@Test void name()`, and package names under `org.sudu.experiments`. Prefer descriptive class names ending in role nouns (`Parser`, `Model`, `View`, `Test`). No repository-wide formatter or `.editorconfig` is configured, so avoid format-only churn.

## Testing Guidelines
JUnit Jupiter is the primary test framework. Name unit tests `*Test.java` and place them in the matching module under `src/test/java`. Keep visual or manual demo scenarios in the existing demo/test-scene modules. Run the narrowest useful Maven test command before submitting; broaden to affected dependent modules with `-am`.

## Commit & Pull Request Guidelines
Recent history uses short imperative commits, often lowercase, for example `fix bugs related to view dispose/recreate` or `add GlDebugApi`. Keep commits scoped to one behavior or module. Pull requests should target `master`, describe affected modules, list verification commands, link issues when relevant, and include screenshots or recordings for UI/demo changes.
