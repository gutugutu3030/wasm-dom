@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // 整形（TL-1）。単体ビルドではここがルートなので、ルール値も自前で持つ
    alias(libs.plugins.spotless)
    // **Maven Local に配る**ための公開（`./gradlew publishToMavenLocal`）。KMP はこのプラグインを
    // 適用すると target ごとに publication を自動で作る（`wasm-dom` / `wasm-dom-jvm` /
    // `wasm-dom-wasm-js`）。`group` と `version` は `gradle.properties` に置く
    // （version は `.github/workflows/release.yml` が sed で更新する行）
    id("maven-publish")
}

// ktlint のルール値は**ここに自前で持つ**（`rootProject.extra` はこのリポジトリに無い。かつて
// 別のビルドのサブモジュールだった頃はルートの `extra` から読んでいて、その外では
// `Cannot get property 'ktlintRules' on extra properties extension` で落ちた）。
// コピーして値を持つので、ルールを変えるときは**ここと `.editorconfig` の 2 箇所を揃える**
// （`.editorconfig` は IDE / ktlint CLI 向けのファイル）
val ktlintRules = mapOf(
    // 既存コードの書き方（`fun f() = 式` を同じ行に置く）に合わせる
    "ktlint_code_style" to "intellij_idea",
    // intellij_idea の `function-signature` は式ボディを**どれだけ長くても 1 行に畳む**
    // （実測で 209 字のコード行が自動生成された）。`always` で常に改行させて畳み込みを止める
    "ktlint_function_signature_body_expression_wrapping" to "always",
    // 日本語コメントが 140 字を超えるのが普通なので行長検査は入れない（-1 = 無効）
    "max_line_length" to "-1",
)

// ktlint は spotless の既定解決に任せずピンする（spotless を上げたときに整形結果が静かに
// 変わって意味の無い巨大差分が出るのを防ぐ）
val ktlintVersion = libs.versions.ktlint.get()

spotless {
    kotlin {
        // KMP は `src/*/java` にも `.kt` を置くので、`kotlin/` だけだとそこが整形から抜ける
        target("src/*/kotlin/**/*.kt", "src/*/java/**/*.kt")
        ktlint(ktlintVersion).editorConfigOverride(ktlintRules)
    }

    // **このプロジェクトがルート**なので、ビルドスクリプトも自分で洗う。
    // target はプロジェクトディレクトリ基準なので、上位に別のビルドがあっても二重で走ることは
    // ない（上の `kotlin` の target は `src/` のみ）
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintRules)
    }
}

// spotless は ktlint の jar を `<project>/build/spotless-*` に解決して使う。**`clean` は他のタスクとの
// 順序を保証しない**ので `./gradlew clean build` を 1 回で叩くと、clean がそれを消したあとに検査が走って
// `NoClassDefFoundError: com/pinterest/ktlint/rule/engine/api/EditorConfigDefaults` になる（実測）。
// **`clean` は別に打つか `--rerun-tasks` を使う**
tasks.matching { it.name.startsWith("spotless") }.configureEach {
    mustRunAfter("clean")
}

// ビルドツール（整形）の依存も OSV 監査に載せる。spotless は ktlint 系を実行時に独自解決するため
// `gradle.lockfile` を通らず、放置するとこのリポジトリの網から完全に外れる（読む側の `audit` は
// 読む側のツリーしか洗わないので、ここは自分で洗う）。
// 実際に使うツールと同じ座標を resolvable 設定に宣言してロックし、`audit` の再帰スキャンに載せる。
// 再生成: `./gradlew dependencies --configuration buildTooling --write-locks`
// （`build` だけでは `buildTooling` が解決されずロックに出ない）。ツールを上げたら実行してコミットする
val buildTooling = configurations.resolvable("buildTooling") {
    description = "OSV 監査対象にするビルドツール（spotless / ktlint）の解決結果"
    // `ktlint-cli` は external（素の依存グラフ）と shadowed（fat jar）の 2 variant を出す。
    // 監査でみたいのは**中身の依存**なので external を明示する（属性を積まないと
    // 「variant が曖昧」として解決自体が失敗する）
    attributes {
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
    }
}

dependencies {
    add(buildTooling.name, libs.spotless.plugin.gradle)
    add(buildTooling.name, libs.ktlint.cli)
}

// 依存の脆弱性スキャン（OSV-Scanner）用に全部の解決結果をロックする。
// `gradle.lockfile` は**このリポジトリだけのもの**（読む側は publish 済みの成果物を引くので、
// 向こうのロックには載らない）。STRICT なので「ロックが無い」も素通ししない
// （`agents.md`「依存ロックと OSV 監査」）
dependencyLocking {
    lockMode = LockMode.STRICT
    lockAllConfigurations()
}

// 依存の脆弱性スキャン（OSV-Scanner）。`check` にはぶら下げない ―― osv-scanner は
// **ネットワーク必須**なので、絡げるとオフラインの `./gradlew build` が止まる。
// CI（`.github/workflows/ci.yml`）が明示的に 1 本走る。
//
// 注意: 参照は全部このブロックの中のローカル変数に置く。スクリプト最上位の `val` を
// タスクアクションから読むと `Build_gradle` への参照として設定キャッシュに載らない
// （`cannot serialize Gradle script object references` で落ちる）
val audit by tasks.registering(Exec::class) {
    group = "verification"
    description = "依存の脆弱性を OSV-Scanner で洗う（`gradle.lockfile`）。手順: agents.md"

    val scanRoot = layout.projectDirectory.asFile
    val scannerConfig = File(scanRoot, "osv-scanner.toml")
    val lockfiles = listOf(
        // 単体ビルドではモジュール分とビルドツール分が**同じ 1 個のロックファイル**に乗る
        // （`buildTooling` もこのプロジェクトの設定なので）。無いと監査が素通りする
        File(scanRoot, "gradle.lockfile"),
    )
    // osv-scanner の候補: PATH → Homebrew → ~/.local/bin の順。PATH は設定時に読む（設定キャッシュが
    // 環境変数の追跡をできるようにするため）。実行ファイルの有無は実行時に判定する
    // —— 未インストールでも `./gradlew build` は止めてはいけない
    val scannerCandidates = (
        providers.environmentVariable("PATH").getOrElse("") + File.pathSeparator +
            "/opt/homebrew/bin" + File.pathSeparator +
            "/usr/local/bin" + File.pathSeparator +
            System.getProperty("user.home") + "/.local/bin"
        )
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { File(it, "osv-scanner") }

    // 「コマンドが見つからない」を Gradle 本来のメッセージで終わらせない。
    // 次に打つコマンドまで日本語で出す
    doFirst {
        val scanner = scannerCandidates.firstOrNull { it.canExecute() }?.absolutePath
            ?: throw GradleException(
                "osv-scanner が見つかりません。macOS は `brew install osv-scanner`、Linux は " +
                    "GitHub Releases からバイナリを `~/.local/bin` に置く（CI はそれをする）。" +
                    "（PATH 以外も /opt/homebrew/bin・/usr/local/bin・~/.local/bin まで見ます）",
            )
        val missing = lockfiles.filterNot { it.isFile }.joinToString(" / ") { it.path }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "ロックファイルがありません: $missing。`./gradlew build --write-locks` と " +
                    "`./gradlew dependencies --configuration buildTooling --write-locks` で生成してコミットしてください。",
            )
        }
        executable = scanner
        args = listOf("scan", "source", "--recursive", "--config", scannerConfig.path, scanRoot.path)
        // 終了コードは下の doLast で日本語の案内に変換する
        isIgnoreExitValue = true
    }

    doLast {
        if (executionResult.get().exitValue != 0) {
            throw GradleException(
                "脆弱性が見つかりました（上の表を参照）。1 件ずつ**この土台の使い方で到達するか**を確認して、" +
                    "依存を上げるか `osv-scanner.toml` に理由と期限（`ignoreUntil`）付きで除外を書く。" +
                    "依存を上げたら `./gradlew build --write-locks` を忘れないでください。",
            )
        }
    }
}

kotlin {
    /*
     * Kotlin/Wasm で**実 DOM を触るための土台**。
     *
     * 入っているのは 3 つだけ:
     *
     * - `reactive/` … シグナルと effect（**純 Kotlin**。`jvmTest` で緑が出る）
     * - `async/` … 画面イベントから `suspend` を始める `launch`（`kotlinx-coroutines` は入れていない）
     * - `dom/` + `js/` … 実 DOM とブラウザ API の橋（`kotlinx-browser` が宣言していないものを自分で宣言する）
     *
     * **読む側（アプリ）の型に依存しない**のが約束。ドメイン・保存・アプリ固有の形式を知らない層に
     * しておくと、単体リポジトリのまま使えて、2 個目のアプリが増えたときにも壁になる。
     * ブラウザ API の橋は wasm の口の穴が集中するので、**教訓は `docs/kb-wasm-bridge.md`** に集める。
     *
     * **画面は持たない**（ブラウザで 1 手ずつ測る実測口は読む側にある）。
     *
     * KDoc でなく `/* … */` にしているのは、`wasmJs { }` が宣言ではなく関数呼び出しなので
     * ktlint の `standard:kdoc` が「block の中の KDoc は不可」として弾くため（実測）
     */
    wasmJs {
        // wasm のテストは Karma + Chrome が要る（開発環境にヘッドレス Chrome が無いことは実測済）。
        // **`commonTest` を置いた瞬間にこのタスクがソースを持って走る**ので明示的に止める。
        // 純 Kotlin の緑は下の `jvm()` に出す
        browser {
            testTask {
                enabled = false
            }
        }
        // **ライブラリ**。`binaries.executable()` を呼ぶのは読む側（アプリ）1 個だけ

        // JS 相互運用（`unsafeCast` / `JsAny` / `JsArray`）は experimental 扱いで opt-in が要る
        compilerOptions {
            optIn.add("kotlin.js.ExperimentalWasmJsInterop")
        }
    }

    /*
     * **`reactive/` の純 Kotlin をテストするための `jvm` ターゲット**。wasm にはテスト実行の口が
     * 無いので、ここに出す純ロジックが `jvmTest` 唯一の緑。CI もこの 1 本を見る。
     *
     * **`jvmToolchain` は使わない**。toolchain は「ビルドに使う JDK」を固定するので、CI のランナーに
     * その JDK を立てる手間が足される。ここでは生成するバイトコードの目標だけ決めて
     * （`JvmTarget.JVM_17`）、動かす JDK は Gradle を起動した JDK に任せる
     * ―― GitHub ランナーの既定（Java 17）でも、手元の Java 27 でも同じ緑になる
     */
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        wasmJsMain.dependencies {
            // **`api` で出す**: 公開 API が `HTMLElement` / `Blob` / `JsAny` を返すので、`implementation` に
            // すると読み手（アプリ本体）が型を見失う。publish された `.module` の
            // `wasmJsApiElements` に載ることも実測で確認している
            api(libs.kotlinx.browser)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
