/*
 * `wasm-dom` 単体ビルドの入口（submodule 化 Step 2・親の `doc/plan-k5-1-wasm-dom-module.md` §5）。
 *
 * 親リポジトリ（Konpetomoide）は 2026-11-26 時点でまだ `include(":wasm-dom")` している。
 * **同じ `build.gradle.kts` を 2 つのビルドが読む**ので、ここは「親がいないとき」の入口に
 * すぎない。親に頼っていたもの（`rootProject.extra` の ktlint 値・版本カタログ・wrapper）は
 * すべて下に自前で持っている。
 *
 * 版本カタログ（`gradle/libs.versions.toml`）は親と**二重管理**になる。`kotlin` がズレると
 * 親の `:webapp` がリンクする stdlib とかみ合わないので、同じ `gradle.lockfile` を読む
 * 2 ビルドの STRICT ロックが突合になる（ズレたらロックが ping-pong して落ちる）。詳細: `agents.md`
 */

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "wasm-dom"
