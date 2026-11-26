package app.konpeto.konpetomoide.webapp.async

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * 画面のイベントから `suspend` の仕事を始める（K1-3）。
 *
 * **`kotlinx-coroutines` は入れていない**（`doc/plan-kmp-local-rewrite.md` §9 D-8）。wasm は単一スレッドで、
 * 保存の待ち合わせに dispatcher も job も要らない。`startCoroutine` だけで足りない用件
 * （同時・取り消し・タイムアウト）が出たときに入れる
 *
 * **例外は握りつぶす**。保存層は失敗を `null` / 空で返す作り（§S2-5）なので、ここまで上がってくる
 * 想定が無い。万一の切り分けは呼び出し側が `runCatching` で画面に出す
 *
 * @param block 走らせる仕事
 */
fun launch(block: suspend () -> Unit) {
    block.startCoroutine(Continuation(EmptyCoroutineContext) { })
}
