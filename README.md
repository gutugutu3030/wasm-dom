# wasm-dom

Kotlin/Wasm で**実 DOM とブラウザ API を触るための土台**。デジタルアルバムアプリ
[Konpetomoide](https://github.com/gutugutu3030/Konpetomoide) の `:wasm-dom` モジュールを、単体
リポジトリ（git submodule）として切り出したものです。

アプリ本体（画面・ドメイン・保存）は一切知りません。入っているのは**シグナル / 画面イベントから
`suspend` を始める口 / 実 DOM の操作 / ブラウザ API の橋**の 4 つだけ。依存は `kotlinx-browser` と
stdlib のみで、`kotlinx-coroutines` も `kotlinx-serialization` も入れていません。

## 何が入っているか

| パッケージ（`app.konpeto.konpetomoide.webapp.`） | 中身 | 例 |
|---|---|---|
| `reactive`（`commonMain`） | シグナルと effect。**純 Kotlin**なので `jvmTest` が緑に出る | `signalOf` / `derivedOf` / `effectOf` / `untracked` |
| `async`（`wasmJsMain`） | 画面イベントから `suspend` を始める `launch`（`startCoroutine` 1 本） | `launch { … }` |
| `dom`（`wasmJsMain`） | 要素生成・スタイル・ピンチ・キーボード inset など実 DOM の操作 | `elementById` / `appendDiv` / `addClass` |
| `js`（`wasmJsMain`） | ブラウザ API の橋（`kotlinx-browser` が宣言していないものを自分で宣言する） | IndexedDB・クリップボード・共有・ダウンロード・canvas・fetch・visual viewport |

package 名に `konpetomoide` が残っているのは、アプリから切り出すときに import を動かさないと
決めた名残です（判断 D-93）。

## 入っていないもの

- **画面・ルーター・アプリ固有の型** … 読む側にある
- **アプリ本体（`:shared`：ドメイン / IndexedDB のスキーマ / `.konpe`）** … **1 回も呼ばない**のが約束
- **`kotlinx-serialization`** … JSON をまたぎたいときは**生 JSON `String` を返す**
  （`clipboardTypesJson` がその形）。encode / decode は呼ぶ側
- **wasm の実行ファイル** … `binaries.executable()` を呼ぶのはアプリ 1 個だけ。ここはライブラリ

## 使い方

```kotlin
// wasmJs 側
val out = elementById("app").appendPre()
val count = signalOf(0)

// 作った瞬間に 1 回走り、中で読んだシグナルが変わるたびに走り直す。後始末の口を返す
val dispose = effectOf {
    out.textContent = "count = ${count.value}"
}

count.value = count.value + 1   // effect が走り直す
dispose()                       // 画面を離れるとき（DOM リスナの積み上がりを防ぐ）
```

`launch { … }` はクリックなどの画面イベントから `suspend` を始める口（dispatcher も job も
無い版）。ブラウザの口を 1 手ずつ測りたいときは、`js/` の橋の関数を直接呼ぶ
（`blobFromBytes` / `readAsDataUrl` など ―― **アプリの実測画面 `#/probe` がこれを 1 手ずつ呼んでいる**）。

## 読み方（ビルド）

**Maven Local に配って、座標で引く。**

```bash
./gradlew publishToMavenLocal    # → ~/.m2/repository/app/konpeto/konpetomoide/wasm-dom/0.1.0/
```

読む側（アプリ本体）は 2 箇所を書く:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenLocal() // ← 追加
        google()
        mavenCentral()
    }
}

// webapp/build.gradle.kts
wasmJsMain.dependencies {
    implementation("app.konpeto.konpetomoide:wasm-dom:0.1.0")
}
```

`publishToMavenLocal` が出すのは 3 個（KMP が target ごとに自動で作る）: `wasm-dom`（ルート・
`available-at` で下に飛ぶ）/ `wasm-dom-jvm`（jar）/ **`wasm-dom-wasm-js`（klib）**。POM だけでなく
Gradle Module Metadata（`.module`）も出る ―― **klib はそちらでしか解決できない**。

**いまは親がまだ `include(":wasm-dom")` している**（未切替）。切るときは親の `settings.gradle.kts`
から `include(":wasm-dom")` を消し、`webapp/build.gradle.kts` の
`implementation(project(":wasm-dom"))` を座標に替えて、`./gradlew build --write-locks` を忘れない。
初めての publish は `./gradlew publishToMavenLocal --write-locks` が要る（POM 書き換えが解決しに
行く classpath がロックに無い・`agents.md`「配給」）。

## 手元で動かす

```bash
./gradlew build            # 整形検査 + `jvmTest`（+ jvm / wasmJs の klib）
./gradlew spotlessApply    # 整形
./gradlew build --write-locks   # 依存を上げたらロック更新
./gradlew audit            # OSV-Scanner による依存の脆弱性スキャン
./gradlew publishToMavenLocal   # Maven Local に配る（座標は上の「読み方」）
```

必要なものは **JDK だけ**（`./gradlew` が Gradle 9.8.1 を落とす）。**toolchain は固定していない**
ので、Gradle を起動した JDK でそのまま走ります（生成するバイトコードは JVM 17 目標。
GitHub ランナー既定の Java 17 でも手元の Java 27 でも同じ緑）。

**CI があります**（`.github/workflows/ci.yml` = `build` + `audit`）。親リポジトリには CI が無いので、
**機械が走る依存監査はここが唯一**です（親の `./gradlew audit` は `--recursive` でここも洗うが、
それは誰かが手元で叩いたときだけ）。

## 橋の緑は目視で見る

wasm のテストは Karma + Chrome が要り、開発環境にヘッドレス Chrome が無いので `wasmJs` のテストは
明示的に止めてあります。`js/` の橋は**アプリ側の `#/probe`** で 1 行ずつ測る
（`reactive/` だけ `jvmTest` が緑）。

## 触る前に知っておくこと

- **`./gradlew clean build` を 1 回で叩かない。** `clean` が spotless の jar を消して交互に
  `NoClassDefFoundError` になる。`clean` は単独で叩く
- 整形の実効ルールは `build.gradle.kts` の `editorConfigOverride`。**`.editorconfig` と
  親の `build.gradle.kts` と 3 箇所に同じ値を書いておく**
- **`gradle.lockfile` は親ビルドと 1 個のファイルを共有する。** 版本を親からズラすとロックが
  ping-pong してどちらかが落ちる ―― これが `kotlin` 版本の自動突合になっている
- **アプリ本体（`:shared`）を import しない。** 1 個型を持ってきた瞬間に、このリポジトリは
  単体で読めなくなる
- `internal` は Kotlin モジュール単位。呼び出し側の実測画面が見る橋（`FileGlue` の 3 関数）は
  `public` に開いてある。**開けるのは橋の関数だけ**
- `.gitignore` の `build` を消さない。osv-scanner は `.gitignore` に従うので、`build/` が見えると
  Kotlin/JS が置く `build/js/yarn.lock` まで洗って無関係な赤が出る
- 教訓の本文は**親リポジトリの `doc/` にある**（wasm の口の穴は `doc/kb-kmp-webview.md` §13〜§18、
  整形と監査は `doc/kb-toolchain.md`、切り出しの判断は `doc/plan-k5-1-wasm-dom-module.md`）。
  ここに書いた判断番号（D-*）も親の `doc/PROGRESS.md` のもの

## リリース

PR のブランチ名から semver ラベルを自動付与 → main マージで採番 + ドラフト Release 更新 →
**Publish は人**（ここで初めてタグが切れる）。採番は `gradle.properties` の `version` に書き、
**それが配給の版本そのもの**（`app.konpeto.konpetomoide:wasm-dom:<version>` を
`publishToMavenLocal` で `~/.m2` に出す）。GitHub Release には成果物を載せず、「どのコミットが
どの版か」を書くタグと changelog だけを作ります。

## 関連

- **[`agents.md`](agents.md)** — 制約と規約の本体（崩してはいけない境界・フォーマット・ロック・
  CI・リリースの手順）
- **[Konpetomoide](https://github.com/gutugutu3030/Konpetomoide)** — この土台を使っているアプリ本体。
  教訓と判断記録はそこの `doc/` にある
