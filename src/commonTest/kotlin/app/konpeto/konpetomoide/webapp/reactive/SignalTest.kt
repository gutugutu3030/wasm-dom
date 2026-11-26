package app.konpeto.konpetomoide.webapp.reactive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * 極小シグナルランタイムのテスト（K2-1・`:webapp:jvmTest` で走る）。
 *
 * 画面の多重実行（リスナの積み上がり・指 1 本に反応が 2 回出る）は**この核のバグ**なので、
 * DOM に触れないここで緑を出す（判断 D-12）。
 */
class SignalTest {
    /** 作った瞬間に 1 回走り、依存が変わるたびに走り直す。 */
    @Test
    fun effectRunsOnCreationAndOnEachChange() {
        val count = signalOf(0)
        val seen = mutableListOf<Int>()
        effectOf { seen.add(count.value) }

        assertEquals(listOf(0), seen)
        count.value = 1
        count.value = 2
        assertEquals(listOf(0, 1, 2), seen)
    }

    /** **同じ値を代入しても走らない**（`==` で見る）。編集のたびに描き直さないための前提。 */
    @Test
    fun effectSkipsWhenValueUnchanged() {
        val name = signalOf("a")
        var runs = 0
        effectOf {
            name.value
            runs++
        }

        name.value = "a"
        assertEquals(1, runs)
        name.value = "b"
        assertEquals(2, runs)
    }

    /** 派生（`derivedOf`）は式の中で読んだシグナルに依存し、下流へ値を渡す。 */
    @Test
    fun derivedFollowsItsSource() {
        val width = signalOf(3)
        val doubled = derivedOf { width.value * 2 }
        val seen = mutableListOf<Int>()
        effectOf { seen.add(doubled.value) }

        assertEquals(listOf(6), seen)
        width.value = 5
        assertEquals(listOf(6, 10), seen)
    }

    /** `dispose` のあとは走り直さない（**画面を離れた派生が依存を握り続けない**・判断 D-120）。 */
    @Test
    fun derivedStopsFollowingAfterDispose() {
        val width = signalOf(3)
        val doubled = derivedOf { width.value * 2 }
        val seen = mutableListOf<Int>()
        effectOf { seen.add(doubled.value) }

        doubled.dispose()
        width.value = 5

        // 派生は走り直さないので下流も走らない。**今の値は読める**（壊れた値にはならない）
        assertEquals(listOf(6), seen)
        assertEquals(6, doubled.value)
        // 2 回呼んでも壊れない
        doubled.dispose()
    }

    /** **派生の結果が変わらなければ下流は走らない**（`width` を動かしても `parity` が同じなら描き直さない）。 */
    @Test
    fun derivedDoesNotNotifyWhenResultUnchanged() {
        val index = signalOf(0)
        val parity = derivedOf { index.value % 2 }
        var runs = 0
        effectOf {
            parity.value
            runs++
        }

        assertEquals(1, runs)
        index.value = 2
        assertEquals(1, runs)
        index.value = 3
        assertEquals(2, runs)
    }

    /** 走り直す**前に**後始末が走る（DOM のリスナ剥がし。これを忘れると反応が積み上がる）。 */
    @Test
    fun effectRunsCleanupsBeforeRerunning() {
        val count = signalOf(0)
        val log = mutableListOf<String>()
        effectOf {
            val current = count.value
            log.add("run $current")
            onCleanup { log.add("clean $current") }
        }

        count.value = 1
        assertEquals(listOf("run 0", "clean 0", "run 1"), log)
    }

    /** やめる口を呼んだら、後始末が 1 回走って以後は走らない。 */
    @Test
    fun disposeStopsTheEffectAndCleansUpOnce() {
        val count = signalOf(0)
        val log = mutableListOf<String>()
        val stop =
            effectOf {
                val current = count.value
                log.add("run $current")
                onCleanup { log.add("clean $current") }
            }

        stop()
        count.value = 1
        assertEquals(listOf("run 0", "clean 0"), log)
    }

    /** 画面の差し替え（旧画面の effect を捨てたあと新画面が同じシグナルを見る）で**多重実行にならない**。 */
    @Test
    fun swappingEffectsKeepsOneSubscriber() {
        val route = signalOf("list")
        var firstRuns = 0
        var secondRuns = 0
        val stopFirst =
            effectOf {
                route.value
                firstRuns++
            }

        stopFirst()
        effectOf {
            route.value
            secondRuns++
        }
        // 捨てた effect は同じシグナルを見ない
        route.value = "viewer"
        assertEquals(1, firstRuns)
        assertEquals(2, secondRuns)
    }

    /** 入れ子の反応（effect の中で派生を作る）でも依存が混ざらない。 */
    @Test
    fun nestedReactionsDoNotLeakDependencies() {
        val source = signalOf(2)
        val seen = mutableListOf<String>()
        effectOf {
            val squared = derivedOf { source.value * source.value }
            seen.add("${source.value}/${squared.value}")
        }

        assertEquals(listOf("2/4"), seen)
        source.value = 3
        assertEquals(listOf("2/4", "3/9"), seen)
    }

    /**
     * 同じシグナルに**直接**と**派生を経由して**の両方でぶつかっても、1 回の代入で 1 回しか走らない。
     *
     * 反応は走り直すと自分の依存をもう一度購読に載せるので、購読者の写しを回すと**同じ代入で 2 回**
     * 走った（実測: `["2/4", "3/6", "3/6"]`）。画面なら 1 回の操作で DOM が 2 回組み直る。
     */
    @Test
    fun effectRunsOnceWhenBothDirectAndDerivedSourcesChange() {
        val width = signalOf(2)
        val doubled = derivedOf { width.value * 2 }
        val seen = mutableListOf<String>()
        effectOf { seen.add("${width.value}/${doubled.value}") }

        assertEquals(listOf("2/4"), seen)
        width.value = 3
        assertEquals(listOf("2/4", "3/6"), seen)
    }

    /**
     * [untracked] の中で読んだシグナルは**依存に載らない**。
     *
     * 編集画面のメニュー effect が作業コピーを読むと、ジェスチャ 1 フレームごとにメニューが
     * 作り直される（連打できない・ちらつく）。その事故をここで止める
     */
    @Test
    fun untrackedReadsDoNotBecomeDependencies() {
        val shown = signalOf(true)
        val working = signalOf("a")
        val seen = mutableListOf<String>()
        effectOf {
            val open = shown.value
            seen.add(if (open) untracked { working.value } else "closed")
        }

        assertEquals(listOf("a"), seen)
        // 作業コピーだけ動いても走らない（**ジェスチャ 1 フレーム**に相当）
        working.value = "b"
        assertEquals(listOf("a"), seen)
        // 開閉の合図が動いたときだけ走る（そのとき今の作業コピーを読む）
        shown.value = false
        assertEquals(listOf("a", "closed"), seen)
    }

    /**
     * **自分で読んだ signal を書く effect** は無限に走らず、例外で止まる（判断 D-119）。
     *
     * 守れていないと書き込みが次の走り直しを**自分のスタックフレームの内側**で呼ぶので、
     * 走り直し続けるたびに段が伸びてブラウザのタブが固まる（`claimFlush` は同じ流れの
     * 多重実行しか止められないので、流れ番号が増え続ける輪を止められない）
     */
    @Test
    fun effectWritingItsOwnSignalThrowsInsteadOfLooping() {
        val counter = signalOf(0)

        val failure = assertFails { effectOf { counter.value = counter.value + 1 } }

        // 止めただけでなく「どこを直せばよいか」を出す
        assertTrue("段" in failure.message.orEmpty(), "メッセージが役に立たない: ${failure.message}")
        // **例外のあと入れ子段は 0 に戻る**（`finally` で戻す）。戻っていないと
        // 以後のすべての反応が即座に同じ例外で止まるので、別の signal で確認する
        val flag = signalOf(false)
        val seen = mutableListOf<Boolean>()
        effectOf { seen.add(flag.value) }
        flag.value = true
        assertEquals(listOf(false, true), seen)
    }

    /** 正当な**深い鎖**（派生を 30 個繋ぐ）は上限に当たらない ―― 段の上限に余裕があることの担保。 */
    @Test
    fun deepChainOfDerivedSignalsStillRuns() {
        val source = signalOf(1)
        var last: ReadSignal<Int> = source
        repeat(30) {
            // **前の link をローカルに写してから**繋ぐ（`last` をそのまま式に読むと
            // 走り直したときに自分自身を読んでしまう）
            val previous = last
            last = derivedOf { previous.value + 1 }
        }
        val seen = mutableListOf<Int>()
        effectOf { seen.add(last.value) }

        assertEquals(listOf(31), seen)
        source.value = 10
        assertEquals(listOf(31, 40), seen)
    }
}
