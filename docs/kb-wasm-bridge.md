# kb: wasm の橋（実 DOM / ブラウザ API）の落とし穴

**この文書は土台（`reactive` / `async` / `dom` / `js`）自身の教訓だけ**を書く。主語は
external 宣言・`unsafeCast`・待ち（Promise）・皿・版本。

- **アプリ（Konpetomoide）の使い方・画面・保存・用紙で決まったことはアプリ側に書く**。
  ここにアプリの型名や画面名は出さない
- **相互参照しない**（疎結合）。アプリ側の判断番号（`D-*`）や教訓ファイルの節番号も引かない。
  必要なら「呼び出し側がそうしている」まで書く
- 実測で分かったことは**日付と数値つき**で書く。再測の口はアプリ側にありますが、ここには
  結果だけ写す（土台は画面を持たない）
- 番号 `B-n` は**append-only**。移設・追加で振り直さない（アプリ側からこの番号を指すため）

---

## B-1. **`String` は `JsAny` のサブタイプではない**（`unsafeCast<String>` は落ちる）

`unsafeCast` の型引数は `T : JsAny` なので、Promise の解決値（`JsAny`）を `String` に**言い直せない**
（実測: `Candidate 'fun <T : JsAny> JsAny.unsafeCast(): T' is inapplicable because of a receiver type
mismatch`）。`kotlinx-browser` の external 宣言の中に `String` が現れるので「型として使える」が、
**`JsAny` から下りてこない**。

- `external interface` の**引数・戻り値・コールバック引数**に `String` は書ける（kotlinx-browser が
  そうしている）
- しかし `JsAny` からのキャストは `JsAny` サブタイプにしかできない
- → **文字列を読む場所（`Blob.text()` 等）は `JSON.stringify` して 1 本の JSON 文字列で返す**。
  割り方は呼び出し側に任せる（この土台は JSON の意味を知らない・`ClipboardGlue` の `clipboardText` と
  `IdbGlue` の配列読みが同じ手）

## B-2. `DOMException` は型として受けられない／失敗は `name` で割る

`then(ok, err)` の `err` は `JsAny`。**`name` を引く external 宣言**を作って名前だけ取る
（`JsValueGlue.kt` の `jsErrorName`）。既存の `awaitJs()` は失敗を `null` に丸めるので、
**「失敗の中身で挙動が変わる」場所では使えない**:

| 場所 | 名前で見分けるもの |
|---|---|
| `navigator.share` | **`AbortError` ＝ シートを閉じただけ（失敗ではない）** |
| `navigator.clipboard.read` | `NotAllowedError`（拒否）と `NotFoundError`（空）で文言が違う |
| 画像の `load` / `error` | 白紙を防ぐ（`error` は即失敗） |

→ `awaitOrThrow()`（失敗は `JsReject(errorName)` を上げる）を作って、そこに寄せる。
**失敗名を文字列のまま純関数に渡すと、呼び出し側のテストは JVM で走る**（wasm の口をまたがない）。

## B-3. `querySelectorAll` は `ParentNode` のメソッド（`Element` でも `Document` でも同じ）

`document.querySelectorAll(...)` と `el.querySelectorAll(...)` は**同じ `ParentNode` 宣言**から来る。
なので Kotlin 側で列に写す拡張は **`ParentNode` に受ける**（`Element` にすると `document` で使えない
＝ `Elements.kt` の `querySelectorAllList` が `ParentNode` レシーバなのがこの理由）。
`NodeList` は `item(index)` で 1 個ずつ引すもの（`asList()` も有るが `List<Node>` なので `Element` へ
言い直す）。

## B-4. `{ once: true }` は options 形なので、boolean 形（`useCapture`）では表現できない

「1 回だけ」受けたい listener があるが、`kotlinx-browser` の `addEventListener(type, cb, Boolean)` は
capture 段の真偽だけ。**options 形の宣言を自分で足すか**（`DomGlue.kt` の `passiveFalseOptions` がその
形）、**`settled` 印＋手動 `removeEventListener`＋`clearTimeout` の保険**で代用する。どちらでも
**先に着いたほうが 1 回だけ終わらせる**作りが要る（保険側は発火しないブラウザ向け）。

## B-5. **`Promise<void>` の `undefined` 解決を非 null `JsAny` で受けて coroutine がハングする**（2026-11-25 実測）

**エラーも出ない**で待ち続ける。`navigator.share()` が `Promise<void>`（解決値が `undefined`）だった。

- `DomGlue.kt` の `JsPromise.then` の `onFulfilled` / `onRejected` を**非 null の `JsAny`** 引数で
  宣言していた
- Kotlin/Wasm は `undefined` を非 null `JsAny` の引数に渡すと**内部で例外にしてコールバックを止める**。
  なので `continuation.resume` が呼ばれず、**待っている coroutine が永久に復帰しない**（例外すら上がらない）
- 直し: **`then` の引数を `((JsAny?) -> Unit)?`**、`awaitJs` / `awaitOrThrow` の `onOk` / `onErr` を
  `(JsAny?) -> Unit` に。**`undefined` → `null` で素通りする**

**教訓**: void 解決の Promise を待つ口は **nullable 受け取りが必須**。`createImageBitmap`（解決値が実体）や
`clipboard.read` は元から効いていたので、**「待ちの口が壊れている」は void 解決のときだけ顕在化する** ――
既存の待ちが通っているからといって `then` の宣言が正しい証拠にはならない。

**切り分け**: 宿主（WebView）の壁なのか実装なのかは、**ブラウザの dev で同じ症状が再現するか**を先看。
再現するなら宿主は無関係。

## B-6. JS の配列・皿は `JSON.parse` で作る（2026-11-22 実測）

`@JsFun` が無い（Kotlin 2.4.21 の wasm-js）ので **Kotlin 側にリテラルが書けない**。

- **`JSON.parse("[]")` / `JSON.parse("{}")` を皿にする**（`newJsArray` / `newSharePayload`）。
  **`push` は呼べるが、要素を読むのはまた別**（読むには `length` 等と同じ言い直し口が要る）
- **共有 payload の `text` / `url` は触らない ―― `undefined` のままにする。**`null` を入れると
  **Safari が `navigator.share` を弾く**（`ShareGlue.kt` の実測）。files だけ渡す共有では
  「空文字も `null` も同じ」ではない

## B-7. `ClipboardItem` は `@JsFun` が無いと作れない（2026-11-22 実測）

クリップボードの**書き込み**は、橋として作れる形が無い:

- `ClipboardItem` のコンストラクタを呼ぶ手が無い（`JsGlue.kt` 実測）ので、**JS shim の追加が前提**
- 読み取り（`clipboard.read`）はユーザー操作・権限・例外名に縛られる
- shim を足すかどうかは、**要求が出てから**判断する（無いものを完コピで作らない）

## B-8. 派生を経由する effect が **1 回の代入で 2 回走る**（`flush` 番号で止める）

- **症状**: 派生を経由する effect が 1 回の代入で 2 回走った（`["2/4", "3/9", "3/9"]`）
- **原因**: 反応は走り直すと**自分の依存をもう一度購読に載せる**。通知は購読者の**写し**を回して
  いるので、派生 → 下流の順で走ったあと、元の写しに残っていた下流をもう一度起こす
- **手**: **代入 1 回を数える `flush` 番号**を持ち、反応が「この流れで既に走った」かを印で見る
  （同じ流れでは 1 回だけ）。**派生が値を変えたら新しい流れにする** ―― 忘れると、先に走った
  下流が**古い派生の値で固まる**（下流がもう走らないので直しに来ない）
- **教訓**: 反応の多重実行は「編集画面で指 1 本に反応が 2 回出る」ように効く。**DOM に触れない所で
  緑を出しておく**（`reactive/Signal.kt` の `claimFlush`。回帰テストを `commonTest` に置いた）

## B-9. **反応の核に DOM を入れない**（純 Kotlin にするとテストの口が残る）

**DOM に触れるのは `effectOf` の中だけ**にして、`reactive/`（`ReadSignal` / `Signal` / `derivedOf` /
`effectOf`）は純 Kotlin の `commonMain` に置く。そうでないと **`jvmTest` が走らず、購読漏れ・
多重実行が無検証になる**（wasm 側のテストは Karma + Chrome が要る・`agents.md`「テストと CI」）。

## B-10. **自分で読んだ signal を書く effect は無限に走ってタブが固まる**（入れ子段で止める）

`claimFlush` は「**同じ流れで 2 回走らない**」だけなので、**流れ番号が増え続ける輪**を止められない。
書き込みは次の反応を**今の反応のスタックフレームの内側**で走らせるので、**入れ子段**を数えて
**100 段で例外**にする。**段で見るのが正しい** ―― 流れ番号のカウントだと「別の signal を書く
正当な鎖」と区別できない。**黙って止めない**（黙って中断すると「効いているのに古い画面」になる）。
Solid も circular dependency は例外。使い手の最深は数段なので 100 段は余裕のある値。

## B-11. 例外のあと**段を 0 に戻さないと全反応が止まる**

`finally` で段を 0 に戻す（実測）。戻していないと**例外のあと全反応が即座に同じ例外で止まる**。
壊れた effect は購読に残す（無限ループの本体を直さずに動かす意味が無い）。例外文に
「自分で読んだ signal を書いていないか」「読むだけなら `untracked`」を出す。

## B-12. **派生は後始末の口を返す**（`derivedOf` が `Dispose` を返す）

派生は作った瞬間から**依存を購読し続ける**（読んでいない間も式が走る）のに外す口が無いと、
**画面を渡るたびに積もる**。`DerivedSignal<T> : ReadSignal<T>` が `dispose` を返し、**依存を離すだけ**
（今の値は読める／下流の購読は下流の寿命に任せる）。`dispose` の意味は「**この派生が**上流を
購読するのをやめる」だけ ―― **dispose 後に下流が反応を作っていたら、その下流が再び購読しに
行く**ので派生が走り直すことはない。**既定の自動後始末は作らない** ―― 派生は親 reaction を
持たないので購読の逆引きが要り、`Observers` より大きい改修になる
（回帰テスト `derivedStopsUpdatingAfterDispose`）。

## B-13. **`public` の関数が `internal` 型を引数に出すとコンパイルが落ちる**

実測: `Kotlin compiler error: 'public' function exposes its 'internal' parameter type 'Reaction'`
（`EXPOSED_PARAMETER_TYPE`）。購読者の付け外しを共有する `SignalNode` を内側に置きたかったので、
**外に出す型は `ReadSignal` / `MutableSignal` の interface 2 個にして実体は `internal`** にした。
**公開 API の形を決めるのはコンパイラ**なので、公開型を増やしたくなったら最初にぶつかる壁。

---

## 移設の記録（append-only）

- **2026-11-26**: アプリ側 `doc/kb-kmp-webview.md` **§17（出力で踏んだ JS 口）の橋部分**
  （17-1〜17-5 / 17-8 / 17-9）を移設 → §B-1〜§B-7。**アプリ側には旧番号のポインタを
  残してある**ので既存の `§17-x` 参照はそこを辿る。アプリに残ったのは 17-6 / 17-7 / 17-10
  （画面とメモリ運用）
- **2026-11-26**: 同 **§1-3（自作シグナルで踏んだ多重実行）と §1-2 の API 設計の穴**を移設
  → §B-8〜§B-13
- **未移設（アプリ側の `doc/kb-kmp-webview.md` に残っている橋の穴）**: §1（interop の形 /
  kotlinx-browser の宣言範囲）、§2（canvas・画像）、§4（IndexedDB を wasm から叩く口）、
  §6（visualViewport・pinch）、§12（`createElementNS` / CSS 変数 / `NodeList` 不在）、
  §13（klib 宣言の穴・リスナ lambda の同一性）、§14（`org.w3c.files`）、§16（`querySelectorAll`）、
  §18（`ByteArray`↔`Blob` 橋・Base64・`Blob.size`）。**この文書の番号は append-only なので、
  移設は B-14 以降に足す**
