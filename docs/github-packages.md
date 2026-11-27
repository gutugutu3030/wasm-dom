# GitHub Packages 配給（**自分のマシンを結ぶ**配給）

`./gradlew publishToMavenLocal` は**同じマシン**に配るだけで、機械をまたがない。
機械をまたぐとき（ノート / デスクトップ / 未来の自分）に置くのがこの配給。

**見知らぬ他人に 1 手で引かせる配給ではない。** GitHub Packages は**公開パッケージでも匿名
ダウンロードできない**（匿名読み取りを許しているのは Container registry だけ）ので、
clone した人は必ず 401 を受ける。他人に引かせる日が来たら Maven Central へ移る
（下の「やめ方 / 移り方」）。

---

## 前提の 3 条件（崩すと危険度が跳ねる）

| 条件 | 崩すと |
| --- | --- |
| **リポジトリは public** | private だとパッケージも private で、読む側のトークンに **`repo` scope が要る**。`repo` を入れた瞬間、漏洩時の被害が「公開 klib が読める」から「**自分の全リポジトリが読み書きできる**」に跳ね上がる |
| **読む側のトークンは `read:packages` 単独** | `repo` も `write:packages` も入れない。この形なら漏れても実質ゼロ被害なので、短期ローテーションが効く |
| **公開（publish）は `GITHUB_TOKEN` のみ** | personal access token に書き込み権限を持たせない。公開側の長期秘密が 0 本になる（`.github/workflows/publish.yml`） |

この 3 条件だと、長寿命の秘密は「**`read:packages` だけの短期トークン 1 本**」に収まる。
ここが崩れたら（private リポジトリ / `repo` 付き / 無期限）この文書の前提が消える。

---

## 公開側

### CI（既定。ここは何もしない）

Release を **Publish** した瞬間に `.github/workflows/publish.yml` が走る:

- `on: release: types: [published]` ―― 今の「Publish は人」方針にそのまま乗る
- `permissions: packages: write` ―― `GITHUB_TOKEN`（ジョブ毎のエフェメラル）にこの
  リポジトリのパッケージ書き込みを与える。**personal access token は要らない**
- タグのコミットを checkout し、**タグと `gradle.properties` の `version` を突合**してから配る
  （ズレると「どのコミットがどの版か」が配給の実体と食い違う）
- `GITHUB_ACTOR` / `GITHUB_TOKEN` / `GITHUB_REPOSITORY` は Actions が全ステップに注入するので、
  シークレット登録も `env:` も要らない

### 手元（原則やらない。CI が配ってくれない版を試すときだけ）

```bash
./gradlew publishAllPublicationsToGithubPackagesRepository -Pgpr.key=<write:packages の token>
```

認証情報が無いとビルド側で日本語の案内付きで止まる（401 で終わらせない ―― 期限切れの朝に
「とりあえず無期限のトークンを作る」に流れるのが、この配給の一番の失敗パターン）。

---

## 読む側の設定

### 1. 認証情報を Keychain に入れる（平文を磁盘に作らない）

```bash
# 1 回だけ。値はプロンプトで入力させ、画面にも履歴にも残さない
security add-generic-password -a <自分の GitHub 名> -s github-packages-wasm-dom-read -w

# ~/.zshrc
export GPR_TOKEN="$(security find-generic-password -a <自分の GitHub 名> -s github-packages-wasm-dom-read -w)"
```

`~/.gradle/gradle.properties` に書くと**平文で常時磁盘に寝る**。Keychain → 環境変数にすると
その経路が消える（プロセスから読めるのは同じなので、漏洩面は「常時平文」→「実行中のみ」に減る）

### 2. ビルド定義に足す（読む側リポジトリの `settings.gradle.kts`）

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()          // Maven Local 配給も併存させる
        google()
        mavenCentral()        // kotlinx-browser など推移依存に必須
        maven {
            url = uri("https://maven.pkg.github.com/gutugutu3030/wasm-dom")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GPR_TOKEN")).orNull
            }
        }
    }
}
```

- **`providers.*` を使う**（`System.getenv()` じゃない）。設定キャッシュが環境変数を入力として
  追跡できるので、トークン差し替え後に古い値が残りづらい。怪しければ `./gradlew --stop`
- **URL に `user:token@` を埋め込まない**。`--info` ログに認証情報が混ざる経路ができる
- 依存の書き方は Maven Local と同じ（`app.konpeto.konpetomoide:wasm-dom:<version>`）。
  klib は `wasm-dom-wasm-js` に `available-at` で飛ぶ

---

## トークン運用

| 項目 | 値 |
| --- | --- |
| 種類 | **personal access token (classic)** のみ。GitHub Packages は fine-grained PAT 非対応 |
| scope | **`read:packages` だけ**（public リポジトリならこれで通る） |
| 期限 | **30 日**（長くても 90 日）。作り直しは 20 秒 |
| 名前 | 機械を入れる（`wasm-dom-read-mbp` / `wasm-dom-read-stand`）。破棄したとき「どれが漏れたか」が分かる |
| 更新 | 期限が来る前に作り直す → Keychain の 1 エントリを差し替え → `./gradlew --stop` |

### 期限切れの朝の手順（これを固めておかないと無期限に逃げる）

1. `read:packages` だけ / 30 日で新しいトークンを作る
2. `security add-generic-password … -U` で Keychain の 1 エントリを上書き
3. `./gradlew --stop`（daemon が古い環境変数を持つ経路がある）
4. `./gradlew build`

**注意:** GitHub は **1 年使っていない classic PAT を自動削除する**。無期限を選んでも死ぬし、
開発が止まっていると勝手に消える。逆に言うと、短期でも毎週ビルドしていればローテーションは
自然に回る

---

## 落とし穴

- **401 はビルドをハードに落とす。** Gradle は認証に失敗したリポジトリを素通りしないので、
  `build` も `--write-locks` も止まる。Maven Central にある依存まで全部解決不能に見える
- **STRICT ロックでも認証は要る。** ロックファイルにリポジトリ URL は載らないが、
  解決は実際にその URL へ行く。クローン直後の環境は凭证が無いと落ちる
- **公開パッケージでも認証必須。** ここを「公開したのに引けない」と誤解しやすい
- **Maven レジストリは repository-scoped 限定。** 送り先が `OWNER/REPOSITORY` 形なのはそれで、
  **リポジトリを移設・改名すると配給 URL が壊れる**。リポジトリ自体は移さないと決めていても、
  **アカウントの改名 / 削除**をすると `gutugutu3030/wasm-dom` という URL が他人に取られうる
  （GitHub の tombstone は人気リポジトリ基準なので個人ライブラリは保護されない側）。
  対策は「アカウントを改名しない」+ 下の検証
- **リモート publish タスクは設定キャッシュ非対応。** Gradle が `PublishToMavenRepository` を
  「Explicit credentials are unsupported with the Configuration Cache」で CC から外すので、
  **そのビルドだけ設定キャッシュが降りる**（`BUILD SUCCESSFUL` になる。失敗ではない）。
  1 リリース 1 回の CI でしか起きない
- **KMP の 3 publication は `pom.url` で 1 リポジトリにまとめている**（`build.gradle.kts`）。
  外すと `wasm-dom-jvm` / `wasm-dom-wasm-js` が同名のリポジトリに飛んで klib が解決できない
- **同一版の再公開はしない。** 読み手はキャッシュ済みで、差し替えたかどうかが切り分けられない。
  作り直したいなら版本を上げる（`agents.md`「リリース」）

---

## 読む側に必ず入れておく検証（この配給の実質的な唯一の網）

```bash
./gradlew --write-verification-metadata sha256
```

`gradle/verification-metadata.xml` を置いた瞬間から Gradle は**全依存を毎回**検査する。
トークンが漏れて配布物が差し替えられても、**ビルドが loud に落ちる**。名前を他人に取られた
ときも sha256 が一致しない。リポジトリ側の可変性はこの 1 本で潰せる

---

## やめ方 / 移り方

- **機械間だけ欲しい** → 読む側の `maven { … }` を消して `mavenLocal()` に戻す。以上
- **他人に引かせたい** → Maven Central へ**追加**する（同じ `build.gradle.kts` に publication を
  足すだけ・既存の読む側は repo を 1 行差し替えるだけ）。GitHub Packages を止めても壊れない。
  Central は認証ゼロ・不可逆・OSV/Dependabot 等のスキャナが全部効くので、公開配給はそっち
- **B → C は安いが、C → B は「秘密を配る」方向なので誰も喜ばない**。だから B から始めても
  ロックインにならない
