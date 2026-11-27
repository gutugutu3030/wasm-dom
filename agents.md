# wasm-dom

Kotlin/Wasm で**実 DOM とブラウザ API を触るための土台**。アプリ本体（画面・ドメイン・保存）は知らない。
Konpetomoide の `:wasm-dom` として開発し、2026-11-26 に**別リポジトリへ出した**（アプリは
Maven Local 配給のライブラリとして引く）。

| 置き場 | 役割 |
| --- | --- |
| `src/commonMain/…/reactive/` | シグナルと effect。**純 Kotlin**で、`jvmTest` が唯一のテスト実行の口 |
| `src/wasmJsMain/…/async/` | 画面イベントから `suspend` を始める `launch`（`kotlinx-coroutines` は入れない） |
| `src/wasmJsMain/…/dom/` | 要素の生成・スタイル・ピンチ・キーボード inset など実 DOM の操作 |
| `src/wasmJsMain/…/js/` | ブラウザ API の橋（`kotlinx-browser` が宣言していないものを自分で宣言する） |
| `docs/kb-wasm-bridge.md` | **橋の教訓**（`B-n` 番号）。ここでの発見はここに書く |
| `docs/github-packages.md` | **GitHub Packages 配給の運用**（認証情報の条件・ローテーション・落とし穴） |

# 境界（崩してはいけないもの）

- **アプリ本体（ドメイン / 保存 / `.konpe`）を 1 回も呼ばない**。それを知らないから単体で立つ。
  型を 1 個 import した瞬間にこのリポジトリは単体で読めなくなる
- **`kotlinx-serialization` を持ち込まない**。JSON をまたぎたいときは**生 JSON `String` を返す**
  （`ClipboardGlue.clipboardTypesJson` がその形）。encode / decode は呼ぶ側の仕事
- **画面を持たない**。ブラウザで測る口（実測画面）は呼び出し側にある
- **`binaries.executable()` を呼ばない**。wasm の実行ファイルを出すのはアプリ 1 個だけ
- `internal` は Kotlin モジュール単位。呼び出し側の実測画面が 1 手ずつ測る橋
  （`FileGlue` の `blobFromTypedArray` / `blobFromDataUrl` / `readAsDataUrl`）は `public` に開いてある。
  **開けるのは橋の関数だけ**で、`reactive/` の `internal` は閉じたまま

# アプリ（Konpetomoide）との関係（疎結合）

アプリ本体は **Maven Local 配給のライブラリ**としてここを引く
（`app.konpeto.konpetomoide:wasm-dom`・`mavenLocal()` 経由）。**リポジトリは別**で、相互参照しない。
**機械をまたぐときだけ GitHub Packages** に上げる（下の「配給」と `docs/github-packages.md`）。

| やりたいこと | コマンド（ここで打つ） |
| --- | --- |
| 整形 | `./gradlew spotlessApply` |
| ビルド + テスト | `./gradlew build` |
| ロック再生成 | `./gradlew build --write-locks` |
| OSV 監査 | `./gradlew audit`（**このリポジトリの CI が走る唯一の網**） |
| 配給（同じマシン） | `./gradlew publishToMavenLocal`（下の「配給」） |
| 配給（機械をまたぐ） | Release の Publish で CI が GitHub Packages へ配る（下の「配給」） |

- **教訓は `docs/kb-wasm-bridge.md` に書く**（番号 `B-n`・append-only）。見分け方は主語が
  external 宣言 / `unsafeCast` / 待ち（Promise） / 皿 / 版本で、**アプリの型名も画面名も出ないか**
- **アプリの使い方・画面・保存・用紙で決まったことはアプリ側に書く**（アプリのリポジトリの
  `doc/kb-kmp-webview.md`）。**向こうの節番号（`§…`）も判断番号（`D-*`）も引かない** ――
  必要なら「呼び出し側がそうしている」まで書く
- 版本カタログ（`gradle/libs.versions.toml`）はアプリ側と**別ファイルの二重管理**。
  **`kotlin` はアプリと同じ値が命** ―― ここでビルドされた klib はアプリのコンパイラが読むので、
  **新しい Kotlin で作るとメタデータ版本で弾かれる**。アプリ側に CI は無いので**機械の突合は無い**。
  上げる日は**両方を同じ日**に（下の「配給」）

# フォーマット規則

- コミット前に `./gradlew spotlessApply`。**効くルールは `build.gradle.kts` の `editorConfigOverride`**
  （spotless は `.editorconfig` を自動で読まない。`setEditorConfigPath` は全ファイルが
  `NoClassDefFoundError` になったので不採用・実測）。`.editorconfig` は IDE / ktlint CLI 向けの
  同じ値を書いておくファイルなので、ルールを変えるときは**両方を揃える**
- **`./gradlew clean build` を 1 回で叩かない**（spotless が ktlint の jar を置く `build/spotless-*` を
  `clean` が消し、交互に `NoClassDefFoundError` になる・実測）。`clean` は単独で打つか
  `--rerun-tasks` を使う
- **ディレクトリを移動・コピーしたら `.gradle` を消す。** 実行履歴
  （`.gradle/*/executionHistory.bin`）に**移動前の絶対パス**が残っていて、spotless が
  `Spotless error! All target files must be within the project dir` で落ちる（実測: アプリから
  移したとき、居たはずの `…/Konpetomoide/wasm-dom/build.gradle.kts` が target として出てきた）。
  `.gradle` は消しても再生成される（`build/` は消さなくてよい）
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
- `gradle.lockfile` は**このリポジトリだけのもの**（アプリは publish された成果物を引くので、
  アプリ側のロックには載らない）。STRICT なので「ロックが無い」も素通ししない
- `audit` は `check` に絡げない（osv-scanner はネットワーク必須なので、絡げるとオフラインの
  `build` が止まる）。CI が明示的に 1 本走る。除外は `osv-scanner.toml` に**理由 + 期限**で書く。
  入っている 3 件は logback の socket server 経由のみ（ktlint が引く）で、**このリポジトリの使い方で
  到達しない**から除外。読む側の監査は読む側の `osv-scanner.toml` が持つ（**ここは写さない**）
- osv-scanner は `.gitignore` に従う。**`.gitignore` の `build` を消すと `build/js/yarn.lock`
  （Kotlin/JS が置く karma / webpack 系）まで洗って無関係な赤が出る**
- ローカルに osv-scanner が無いと `audit` は日本語の案内で落ちる（macOS は `brew install osv-scanner`。
  CI は GitHub Releases から SHA-256 検証付きで `~/.local/bin` に置く）
- **`--write-locks` を中断しない。** ロックは途中書きされるので、次回以降「解決結果が空」で化ける。
  実測: 中断したあと `compileCommonMainKotlinMetadata` が
  `Unresolved reference 'mutableListOf'` / `'let'` / `'error'` を並べた（＝**stdlib が無い**顔をした
  コンパイルエラーで、コードは悪くない）。`./gradlew build --write-locks` を**走り切るまで**待って直す

# 配給（Maven Local + GitHub Packages）

- 座標は **`app.konpeto.konpetomoide:wasm-dom:<version>`**。`group` と `version` は
  `gradle.properties` に置く（Gradle がそのまま `project.group` / `project.version` に読む）。
  `version` は `.github/workflows/release.yml` が sed で更新する行なので、**ここに手を置くのは
  リリースのときだけ**
- `maven-publish` を適用すると KMP が target ごとに publication を自動で作る。
  `./gradlew publishToMavenLocal` が出すのは 3 個:
  `wasm-dom`（ルート・`available-at` で下に飛ぶ）/ `wasm-dom-jvm`（jar）/ **`wasm-dom-wasm-js`（klib）**
- **`api(libs.kotlinx.browser)` は公開でも効く**。publish された `.module` の
  `wasmJsApiElements` に `kotlinx-browser` が載る。`implementation` にすると読む側が
  `HTMLElement` を見失うので動かさない
- **初めての `publishToMavenLocal` は `--write-locks` が要る**。POM 書き換え（`rewritePomXml`）が
  `jvmRuntimeClasspath` / `wasmJsRuntimeClasspath` を解決しに行くが、**`build` では解決されない
  のでロックに無い**。STRICT なので「`Configuration ':jvmRuntimeClasspath' is locked but does not
  have lock state`」で落ちる → `./gradlew publishToMavenLocal --write-locks` を 1 回打つ
- **配給は 2 系統**。同じマシンは Maven Local（`publishToMavenLocal`）、**機械をまたぐときだけ**
  GitHub Packages（`publishAllPublicationsToGithubPackagesRepository`）。**公開は CI の
  `GITHUB_TOKEN` だけ**（`.github/workflows/publish.yml`）で、personal access token に書き込み
  権限を持たせない。読む側は **`read:packages` 単独の短期 classic PAT**（fine-grained は
  GitHub Packages 非対応）。条件と運用・落とし穴は `docs/github-packages.md`
- **GitHub Packages は公開パッケージでも匿名で引けない**（匿名読み取りは Container registry だけ）。
  つまりこの配給は「自分の機械を結ぶ」もので、**見知らぬ他人に 1 手で引かせる配給ではない**。
  その日が来たら Maven Central に**追加**する（B → C は安いが C → B は秘密を配る方向）
- **Maven レジストリは repository-scoped 限定**。送り先が `OWNER/REPOSITORY` 形なのはそれで、
  **リポジトリの移設・改名で配給 URL が壊れる**。KMP の 3 publication は `pom.url` で
  1 リポジトリにまとめている（外すと `wasm-dom-wasm-js` の klib が別の場所へ飛ぶ）
- **成果物を張り替えるたびに読む側の `--write-locks` は要らない**（同じ版本ならロックは不変）。
  **版本を上げた日だけ**両方（読む側の `libs.versions.toml` とロック）を触る

# テストと CI

- **`jvmTest` が唯一の緑の口**。`reactive/` の純 Kotlin をここで走らせる
- **wasm のテストは走らない**（Karma + Chrome が要り、開発環境にヘッドレス Chrome が無い）。
  `wasmJs { browser { testTask { enabled = false } } }` を消すと `commonTest` が wasm 側でも走ろう
  として落ちる
- 橋（`js/`）は単体で緑にできない。**呼び出し側の実測画面で目視**が実測（ここは画面を持たない）
- CI は `.github/workflows/ci.yml` 1 ジョブ（`build` = spotlessCheck + `jvmTest` → osv-scanner を
  SHA-256 検証付きで `~/.local/bin` に置く → `audit` → `publishToMavenLocal`）。アプリ側リポジトリに
  CI は無いので、**機械が走る依存監査はここが唯一**
- 配給は別ワークフロー（`.github/workflows/publish.yml`）。**Release の Publish で発火**し、
  タグと `gradle.properties` の `version` を突合してから GitHub Packages へ配る。
  `permissions: packages: write` の `GITHUB_TOKEN` を使うので**このリポジトリの公開側に
  長期の秘密は無い**。`ci.yml` には絡げない（`push` のたびに配ると版の意味が壊れる）
- **ランナーは `ubuntu-latest`**（`ubuntu-slim` は 1 core / 5 GB で `org.gradle.jvmargs=-Xmx3g` ＋
  `kotlin.daemon.jvmargs=-Xmx2g` に載らない・実測）
- **JDK は固定しない**。`jvmToolchain` を使わず `jvm { compilerOptions { jvmTarget = JVM_17 } }`
  で生成するバイトコードの目標だけ決める。動かす JDK は Gradle を起動した JDK に任せるので、
  CI はランナー既定の JDK（Java 17）で走り、手元の Java 27 でも同じ緑になる

# リリース

- PR → **ブランチ名から semver ラベルを自動付与**（`.github/workflows/pr-labels.yml` の autolabeler）
  → 採番根拠の検証（同じ workflow の check ジョブ）→ main マージで採番 + ドラフト更新
  （`.github/workflows/release.yml`）→ **Publish は人**（ここで初めてタグが切れる）
- **autolabeler の `branch` は正規表現リテラル `/…/`**（末尾の `/` が必須・中の `/` は `\/`）。
  閉じ `/` を忘れた `'/fix/.+'` は**その文字列を含むか**の比較に化け、**警告も出ず二度と当たらない**
  （実測: PR を建ててもラベルが 1 枚も付かず、人が `semver:*` を付けていた）。照合は部分一致なので
  `^` と `$` も付ける
- **`semver:major|minor|patch` と `skip-changelog` はリポジトリのラベルとして先に作る**。
  API はラベルを作らないので、存在しない名前を付けに行った autolabel は 422 で赤くなる
  （UI で初めて付けるとそのときだけ成功して、CI が壊れる）
- **採番根拠の検証は `check-pr` に任せない**（`pr-labels.yml` の check が自分で数える）。理由は 2 つ
  - `check-pr` は**イベント payload のラベル snapshot しか見ない**（上流 README 明記）。同じ run の
    autolabeler が付けたラベルが見えない。しかもそのラベル付与が起こす `labeled` は**新しい run を
    作らない**（GITHUB_TOKEN 起源の `pull_request` は `opened` / `synchronize` / `reopened` 以外 run
    にならない・GitHub の再帰防止）→「あとで labeled が走って緑に戻る」が起きない（実測）
  - **payload を書き換えて渡す芸も効かない**。runner は**各 `uses:` ステップの直前に event payload
    ファイルをメモリ上の `github.event` から書き戻す**（actions/runner の `ActionRunner.RunAsync` →
    `ExecutionContext.WriteWebhookPayload`）。`$GITHUB_EVENT_PATH` を塗り替えても action には届かない
    （実測: 塗り替えは成功するのに `check-pr` はラベルを見ていない）
  - よって check は「payload のラベル + autolabel の output」を `run:` の中で数え、`check-pr` には
    **設定ファイルの妥当性**だけ見せる（`continue-on-error` + `labels` output が空のときだけ赤。
    空 = config が読めない / スキーマを通らない。読めていればラベル不一致でも output は出る）。
    PAT でラベルを付ければ `labeled` は走るが、秘密の管理が増えるので不採用
- **最初の 1 版だけは人が Release を手で作る**（title と tag を `v<gradle.properties の version>`、
  Target: main、Publish）。基準になる published Release が無いと Release Drafter は `0.0.0` 起点の
  patch 解決にしかならないので、`release.yml` のダウングレード検査（解決版 < 現値は失敗）が
  **必ず赤くなる**（実測: `0.0.1` vs `0.1.0` で連続失敗・PR に `semver:minor` が付いていても同じ）。
  タグが 1 個できれば以後は自動採番が回る。その最初の Publish が
  `publish.yml`（GitHub Packages への初配給）も発火させる
- 採番は `gradle.properties` の `version` に書く。**この値が配給の版本そのもの**
  （`app.konpeto.konpetomoide:wasm-dom:<version>` を `publishToMavenLocal` で出す）。
  タグ（`v` を付けた名前）とこの値は同じに保つ
- **GitHub Release 自体に成果物は載せない。** Release は「どのコミットがどの版か」を書くタグと
  changelog の場所。成果物は **Maven Local（手元）と GitHub Packages（Publish を合図に CI が
  `GITHUB_TOKEN` で配る）** の 2 系統に出す
- 版を上げる手順: main に `version` を書いたコミットが落ちる → **人が Publish**（タグが切れる）→
  `publish.yml` が GitHub Packages へ配る → 同じマシンで読むなら `./gradlew publishToMavenLocal`
  → 読む側が座標の版本を上げて `--write-locks`
- 判定規則本体は `.github/release-drafter.yml`。**デフォルトブランチ上の設定が読まれる**ので、
  設定変更も必ず PR で main に入れる（機能ブランチで編集しても効かない）
- `docs/*` ブランチは `skip-changelog`（採番も changelog も外れる）

# コメント規則

- Kotlin のクラス・関数・プロパティには、日本語の KDoc を付ける
- KDoc には必要に応じて `@param`、`@return`、`@receiver` を必ず書く
- 橋の関数は「**どのブラウザ API を経由するか**」を書く（同じように見える処理で Safari / Chrome の
  挙動が違う口がここにある）
- コメントは実装の繰り返しではなく、意図と役割を書く
- **このリポジトリには画面が無い**ので、UI の規則（ラベルの動詞化など）は適用しない

# 設計規則

- クラスに属さない関数を定義する場合は、意味合いを考え、拡張関数・拡張プロパティにすること
- 拡張関数と拡張プロパティの使い分けは、引数が必要か + 副作用があるか である
- `try` / `catch` ではなく `runCatching` (`fold` / `orElseGet`) を使用すること
- **`kotlinx-coroutines` は土台に入れない**（`async/Launch.kt` は `startCoroutine` 1 本。wasm は
  単一スレッドで dispatcher も job も要らない）。同時 / 取り消し / タイムアウトが要る日に
  入れるので、そのときは判断を残す
