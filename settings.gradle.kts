/*
 * `wasm-dom` 単体ビルドの入口。**このリポジトリだけで立つ**（アプリ本体の型を知らないのが境界）。
 *
 * アプリ（Konpetomoide）は 2026-11-26 に `include(":wasm-dom")` を外し、**Maven Local 配給の
 * ライブラリ** `app.konpeto.konpetomoide:wasm-dom` を `mavenLocal()` 経由で引く。だから
 * **読む側のビルドはここを通らない** ―― ktlint の値・版本カタログ・wrapper は全部ここにある。
 *
 * 版本カタログ（`gradle/libs.versions.toml`）は読む側と別ファイルになるが、**`kotlin` は同じ値が
 * 命**（ここで作った klib を向こうのコンパイラが読む）。機械の突合は無いので上げる日は両方を
 * 同じ日に。詳細: `agents.md`
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
