# wasm-dom

Kotlin/Wasm で**実 DOM とブラウザ API を触るための土台**。アプリ本体（画面・ドメイン・保存）は知らない。
Konpetomoide の `:wasm-dom` として開発され、2026-11-26 に submodule 化のためここで `git init` した。

| 置き場 | 役割 |
| --- | --- |
| `src/commonMain/…/reactive/` | シグナルと effect。**純 Kotlin**で、`jvmTest` が唯一のテスト実行の口 |
| `src/wasmJsMain/…/async/` | 画面イベントから `suspend` を始める `launch`（`kotlinx-coroutines` は入れない） |
| `src/wasmJsMain/…/dom/` | 要素の生成・スタイル・ピンチ・キーボード inset など実 DOM の操作 |
| `src/wasmJsMain/…/js/` | ブラウザ API の橋（`kotlinx-browser` が宣言していないものを自分で宣言する） |

# 境界（崩してはいけないもの）

- **アプリ本体（`:shared`）を 1 回も呼ばない**。ドメイン・保存・`.konpe` を知らないから単体で立つ
  （判断 D-95）。型を 1 個 import した瞬間にこのリポジトリは単体で読めなくなる
- **`kotlinx-serialization` を持ち込まない**。JSON をまたぎたいときは**生 JSON `String` を返す**
  （`ClipboardGlue.clipboardTypesJson` がその形）。encode / decode は呼ぶ側の仕事
- **画面を持たない**。`#/probe` のような実測口はアプリ側（D-100）
- **`binaries.executable()` を呼ばない**。wasm の実行ファイルを出すのはアプリ 1 個だけ
- `internal` は Kotlin モジュール単位。アプリの `probe/` が 1 手ずつ測る橋
  （`FileGlue` の `blobFromTypedArray` / `blobFromDataUrl` / `readAsDataUrl`）は `public` に開いてある
  （D-102）。**開けるのは橋の関数だけ**で、`reactive/` の `internal` は閉じたまま

# 親との関係（移行中・ここが一番の落とし穴）

親 Konpetomoide はまだ `include(":wasm-dom")` している。**同じ `build.gradle.kts` を 2 つのビルドが読む**。

| やりたいこと | 親から | ここで（単体） |
| --- | --- | --- |
| 整形 | `./gradlew :wasm-dom:spotlessApply` | `./gradlew spotlessApply` |
| ビルド + テスト | `./gradlew :wasm-dom:build` | `./gradlew build` |
| ロック再生成 | `./gradlew :wasm-dom:build --write-locks` | `./gradlew build --write-locks` |
| OSV 監査 | `./gradlew audit`（ルートがツリー全体を再帰） | `./gradlew audit`（ここだけ） |

- **`rootProject.extra` は読まない**（親の外では読めないので `Cannot get property … on extra
  properties extension` で落ちる）。ktlint のルール値は `build.gradle.kts` に自前で持つ
  → **揃える箇所は 3 つ**: ここ / `.editorconfig` / 親の `build.gradle.kts`
- 版本カタログ（`gradle/libs.versions.toml`）も親と二重管理。**`kotlin` は親と同じ値が命**
  （ズレるとアプリ本体がリンクする stdlib とかみ合わない）。突合は下の「ロックの ping-pong」がやる
- 教訓の本文は**親の `doc/` にある**（`doc/kb-kmp-webview.md` §13〜§18 の wasm の口の穴、
  `doc/kb-toolchain.md` の整形と監査、`doc/plan-k5-1-wasm-dom-module.md` の切り出し判断）。
  **このリポジトリの外なので、単体で読んだときには見えない**。ここに書いた番号（D-xx）は親の
  `doc/PROGRESS.md` の判断番号

# フォーマット規則

- コミット前に `./gradlew spotlessApply`。**効くルールは `build.gradle.kts` の `editorConfigOverride`**
  （spotless は `.editorconfig` を自動で読まない。`setEditorConfigPath` は全ファイルが
  `NoClassDefFoundError` になったので不採用・親で実測）。`.editorconfig` は IDE / ktlint CLI 向けの
  同じ値を書いておくファイルなので、ルールを変えるときは**両方を揃える**
- **`./gradlew clean build` を 1 回で叩かない**（spotless が ktlint の jar を置く `build/spotless-*` を
  `clean` が消し、交互に `NoClassDefFoundError` になる・親で実測）。`clean` は単独で打つか
  `--rerun-tasks` を使う
- 式ボディは `fun f(): T =` の**後ろで改行する**（`ktlint_function_signature_body_expression_wrapping = always`）
- 宣言に付かないコメントは `/* … */`。KDoc（`/** … */`）は宣言に付くものだけ。**宣言じゃない場所に
  KDoc を置くと ktlint `standard:kdoc` が弾く**（実測: `build.gradle.kts` の `kotlin { wasmJs { … } }` に
  付けた KDoc が `A KDoc is not allowed inside 'block'` で `spotlessCheck` を落とした）。
  **ビルドスクリプトの target 説明は `/* … */`**
- 依存・プラグインの版本は `gradle/libs.versions.toml` に一元管理する。ktlint は spotless の既定解決に
  任せず `ktlint` でピンする

# 依存ロックと OSV 監査

- 依存を上げたら `./gradlew build --write-locks`。ビルドツール（spotless / ktlint）のロックは
  `./gradlew dependencies --configuration buildTooling --write-locks` で再生成する（`audit` が読む）
- **`gradle.lockfile` は親ビルドと 1 個のファイルを共有する**（親は `:wasm-dom` として、ここはルート
  として読む）。だから**版本を親からズラすとロックが ping-pong してどちらかが落ちる** ―― これが
  `kotlin` 版本の自動突合になっている（STRICT なので「ロックが無い」も素通ししない）。意図せずに
  落ちたら、親と子のカタログの**同じ行を同じタイミングで**上げる
- `audit` は `check` に絡げない（osv-scanner はネットワーク必須なので、絡げるとオフラインの
  `build` が止まる）。CI が明示的に 1 本走る。除外は `osv-scanner.toml` に**理由 + 期限**で書く。
  入っている 3 件は logback の socket server 経由のみ（ktlint が引く）で、**親と同じ 3 件**を揃えて保つ
- osv-scanner は `.gitignore` に従う。**`.gitignore` の `build` を消すと `build/js/yarn.lock`
  （Kotlin/JS が置く karma / webpack 系）まで洗って無関係な赤が出る**
- ローカルに osv-scanner が無いと `audit` は日本語の案内で落ちる（macOS は `brew install osv-scanner`。
  CI は GitHub Releases から SHA-256 検証付きで `~/.local/bin` に置く）

# テストと CI

- **`jvmTest` が唯一の緑の口**。`reactive/` の純 Kotlin をここで走らせる（D-12）
- **wasm のテストは走らない**（Karma + Chrome が要り、開発環境にヘッドレス Chrome が無い）。
  `wasmJs { browser { testTask { enabled = false } } }` を消すと `commonTest` が wasm 側でも走ろう
  として落ちる
- 橋（`js/`）は単体で緑にできない。**アプリ側の `#/probe` で目視**が実測（親の受け入れ条件 V6）
- CI は `.github/workflows/ci.yml`（`build` = spotlessCheck + `jvmTest`、`audit` = OSV 監査）
- **JDK は固定しない**。`jvmToolchain` を使わず `jvm { compilerOptions { jvmTarget = JVM_17 } }`
  で生成するバイトコードの目標だけ決める。動かす JDK は Gradle を起動した JDK に任せるので、
  CI はランナー既定の JDK（Java 17）で走り、手元の Java 27 でも同じ緑になる

# リリース

- PR → **ブランチ名から semver ラベルを自動付与**（`.github/workflows/pr-labels.yml` の autolabeler）
  → `check-pr` が採番根拠を検証 → main マージで採番 + ドラフト更新
  （`.github/workflows/release.yml`）→ **Publish は人**（ここで初めてタグが切れる）
- 採番は `gradle.properties` の `version` に書く。**今のところビルドは値を見ない**（読む側は
  submodule の gitlink / `includeBuild` の座標で版を固定する）。タグと changelog の種を作るための
  採番で、成果物の配給はしない（`maven-publish` は入れていない）
- 判定規則本体は `.github/release-drafter.yml`。**デフォルトブランチ上の設定が読まれる**ので、
  設定変更も必ず PR で main に入れる（機能ブランチで編集しても効かない）
- `docs/*` ブランチは `skip-changelog`（採番も changelog も外れる）

# コメント規則

- Kotlin のクラス・関数・プロパティには、日本語の KDoc を付ける
- KDoc には必要に応じて `@param`、`@return`、`@receiver` を必ず書く
- 橋の関数は「**どのブラウザ API を経由するか**」を書く（同じように見える処理で Safari / Chrome の
  挙動が違う口がここにある）
- コメントは実装の繰り返しではなく、意図と役割を書く
- **このリポジトリには画面が無い**ので、親にある UI 規則（ラベルの動詞化など）は適用しない

# 設計規則

- クラスに属さない関数を定義する場合は、意味合いを考え、拡張関数・拡張プロパティにすること
- 拡張関数と拡張プロパティの使い分けは、引数が必要か + 副作用があるか である
- `try` / `catch` ではなく `runCatching` (`fold` / `orElseGet`) を使用すること
- **`kotlinx-coroutines` は土台に入れない**（`async/Launch.kt` は `startCoroutine` 1 本。wasm は
  単一スレッドで dispatcher も job も要らない・D-8）。同時 / 取り消し / タイムアウトが要る日に
  入れるので、そのときは判断を残す
