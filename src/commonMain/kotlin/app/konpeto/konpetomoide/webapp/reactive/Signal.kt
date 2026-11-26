package app.konpeto.konpetomoide.webapp.reactive

/*
 * 極小のシグナル / エフェクト（K2-1・`doc/kb-kmp-webview.md` §1-3）。
 *
 * 完コピの対象が DOM なので Compose（skiko）は採らないが、**DOM を命令的に組むと再描画の面倒を
 * 誰も見ない**。なので「値 → 反応」の 1 組だけを入れる（Solid の `createSignal` / `createMemo` /
 * `createEffect` を Kotlin に写したもの）。**依存は読み取りから自動で集める** ―― 手で
 * `observe(a, b)` を書かせると、必ず増やし忘れ・減らし忘れが出る。
 *
 * ## 入れていないもの（要るものだけ足す）
 *
 * - `batch`（1 フレームにまとめて流す）: 代わりに**代入 1 回（1 流れ）で同じ反応は 1 回しか走らない**
 *   （[flush]）。画面の多重実行は編集画面で指の挙動に出るので、ここで止める
 * - マイクロタスクへの予約: **同期的に走る**方が DOM の見えと合って楽
 * - 例外の扱い: 反応の中で出た例外を画面に出すのは呼び出し側の責務（`App.kt`）
 *
 * **純 Kotlin**（DOM を触らない）。なので `:webapp:jvmTest` で緑が出る（判断 D-12・
 * 「テストの走らない `webapp` に純ロジックを置くと無検証になる」は K1-2 で踏んだ教訓）。
 *
 * ## 外に出す型は `ReadSignal` / `DerivedSignal` / `MutableSignal` の 3 個だけ
 *
 * 購読者の付け外し（`SignalNode`）は内側の作り。**public のクラスが internal の型を引数に持つと
 * コンパイルが落ちる**（実測: `EXPOSED_PARAMETER_TYPE`）ので、実体の `Signal` / `Derived` は internal。
 */

/** 反応をやめる口（画面を差し替えるとき・アプリを閉じるときに呼ぶ）。 */
typealias Dispose = () -> Unit

/**
 * 読み取り専用のシグナル。
 *
 * 反応（[derivedOf] / [effectOf]）の中で [value] を読むと、**その反応が自動でこのシグナルを購読する**。
 *
 * @param T 値の型
 */
interface ReadSignal<out T> {
    /** 現在の値。反応の中で読むと、その反応の依存に載る。 */
    val value: T
}

/**
 * [derivedOf] が返す読み取り専用シグナル（**後始末の口付き**・判断 D-120）。
 *
 * 派生は作った瞬間から**依存を購読し続ける**（読んでいない間も式が走る）。なので画面で作った派生は、
 * 画面を離れるときに [dispose] しないと依存を握り続ける。`effectOf` と同じ作法にする
 *
 * @param T 値の型
 */
interface DerivedSignal<out T> : ReadSignal<T> {
    /**
     * 依存を全部離す（以後は依存が変わっても走り直さない。**今の値は読める**）。
     *
     * 2 回呼んでも壊れない。自分の値を読んでいる下流の購読までは外さない ―― 下流は自分の寿命で
     * 後始末するもの
     */
    fun dispose()
}

/**
 * 読み書きできるシグナルの**形**。**値が変わったときだけ**反応が走る。
 *
 * 作るなら [signalOf] を使う（実体は `reactive` の内側に閉じている）。
 *
 * @param T 値の型
 */
interface MutableSignal<T> : ReadSignal<T> {
    /** 現在の値。代入すると依存している反応が走る（**同じ値なら走らない**）。 */
    override var value: T
}

/**
 * 読み書きできるシグナルを作る。
 *
 * @param T 値の型
 * @param initial 初期値
 * @return 作ったシグナル
 */
fun <T> signalOf(initial: T): MutableSignal<T> =
    Signal(initial)

/**
 * 式から作る読み取り専用シグナル（式の中で読んだシグナルに依存する）。Solid の `createMemo` 相当。
 *
 * **作った派生は依存を購読し続ける**ので、画面で使うなら [DerivedSignal.dispose] を後始末に登録する
 * （`effectOf` の戻り値と同じ作法。現状の呼び出しはテストのみ）。画面の外で作り続けると
 * 冊を渡るたびに派生が積まる
 *
 * @param T 値の型
 * @param compute 式。中で読んだシグナルが依存になる
 * @return 作り直されるシグナル（後始末の口付き）
 */
fun <T> derivedOf(compute: () -> T): DerivedSignal<T> =
    Derived(compute)

/**
 * 副作用を作る（**作った瞬間に 1 回走り**、依存が変わるたびに走り直す）。**DOM を触るのはここだけ**。
 *
 * 走り直す**前に** [EffectScope.onCleanup] で登録した後始末が走る。DOM のリスナを貼る画面は
 * これを忘れるとリスナが積み上がって、編集画面で指 1 本に反応が 2 回出る。
 *
 * @param body 副作用
 * @return 反応をやめる口（呼ぶと後始末が走って、以後は依存が変わっても走らない）
 */
fun effectOf(body: EffectScope.() -> Unit): Dispose {
    val effect = Effect(body)
    return { effect.dispose() }
}

/**
 * [effectOf] の中に渡す後始末の登録口。
 */
class EffectScope internal constructor() {
    private val cleanups = mutableListOf<() -> Unit>()

    /**
     * 次回走る前 / [effectOf] の戻り値を呼んだときに走らせる後始末を登録する（DOM のリスナ剥がしなど）。
     *
     * @param block 後始末
     */
    fun onCleanup(block: () -> Unit) {
        cleanups.add(block)
    }

    /** 登録された後始末を走らせて、列表を空にする（同じ後始末を 2 回走らせない）。 */
    internal fun runCleanups() {
        val pending = cleanups.toList()
        cleanups.clear()
        pending.forEach { it() }
    }
}

/** 今「読み取りを誰がしているか」。`null` は反応の外（イベントハンドラや `main`）。 */
private var activeReaction: Reaction? = null

/**
 * **反応が入れ子で何段走っているか**（[Reaction.guarded] が数える）。
 *
 * 反応の中で signal を書くと、その書き込みが次の反応を**今の反応のスタックフレームの内側**で走らせる。
 * なので「自分が読んだ signal を書く effect」は段が伸び続ける ―― ここで検知する（判断 D-119）
 */
private var reactionDepth = 0

/**
 * 入れ子の上限。**アプリの最深は数段**（signal → 派生 → effect）なので余裕のある値。
 * 超えたら**例外**で止める ―― 黙って打ち切ると「効いているのに古い画面」になる
 */
private const val MAX_REACTION_DEPTH = 100

/**
 * **代入 1 回を数える流れ番号**。同じ流れの中では同じ反応を 2 回走らせない。
 *
 * 反応は走り直すと**自分の依存をもう一度購読に載せる**ので、購読者の写しを回すと 1 回の代入で
 * 2 回走る（実測: 派生を経由する effect が `["2/4", "3/9", "3/9"]` になった）。
 * 派生が値を変えたときも新しい流れにする ―― そうしないと、先に走った下流が**古い派生の値で固まる**。
 */
private var flush = 0L

/**
 * 反応の側から見たシグナルの形（購読者の付け外しだけ出してもらう）。
 *
 * [Signal] と [Derived] が同じ購読者リストの作法を共有するための内側の口。
 */
internal interface SignalNode {
    /** 購読者を足す（同じ反応は 1 回だけ）。 */
    fun addObserver(reaction: Reaction)

    /** 購読者を外す。 */
    fun removeObserver(reaction: Reaction)
}

/**
 * 購読者の列（[Signal] と [Derived] が同じ作法を持つための部品）。
 */
internal class Observers {
    private val reactions = mutableListOf<Reaction>()

    /** 購読者を足す（同じ反応は 1 回だけ）。 */
    fun add(reaction: Reaction) {
        if (reaction !in reactions) reactions.add(reaction)
    }

    /** 購読者を外す。 */
    fun remove(reaction: Reaction) {
        reactions.remove(reaction)
    }

    /** 購読している反応を走り直させる（走り直しや破棄で購読が壊れるので**写し**を回す）。 */
    fun notifyObservers() {
        reactions.toList().forEach { react ->
            // 走っている最中に誰かが破棄したら、その反応は起こさない（死んだ effect が復活する）
            if (react in reactions) react.reRun()
        }
    }
}

/**
 * 読みを数えさせずにブロックを走らせる（Solid の `untrack` 相当）。
 *
 * effect の中で「今の値が知りたいだけの読み」をこれをかませずにやると、**その signal が依存に載って**
 * 値が変わるたびに effect が走る。編集画面のメニュー effect が `working` を読むのがそれで、
 * 読んだ瞬間から**ジェスチャ 1 フレームごとにメニューが作り直される**（連打できない・ちらつく）
 *
 * @param R 戻り値の型
 * @param block 読むブロック
 * @return [block] の戻り値
 */
fun <R> untracked(block: () -> R): R {
    val outer = activeReaction
    activeReaction = null
    try {
        return block()
    } finally {
        activeReaction = outer
    }
}

/**
 * シグナルの間の**反応 1 個**。[track] の間で読まれたシグナルを依存として集め、依存が変わると [reRun] が走る。
 *
 * 走り直し・破棄のどちらでも**前の依存を全部外す**のを忘れると、古い値の購読が残って多重実行になる
 * （`:webapp:jvmTest` の「走り直し前に後始末が走る」で見る）。
 */
internal abstract class Reaction {
    /** いま依存しているシグナル。 */
    val dependencies = mutableSetOf<SignalNode>()

    /** [claimFlush] が印を付けた流れ番号。 */
    private var lastFlush = -1L

    /**
     * この反応を「読み手」にして [block] を走らせる（入れ子の反応は、抜けたら前の読み手へ戻す）。
     *
     * @param R 戻り値の型
     * @param block 読むブロック
     * @return [block] の戻り値
     */
    fun <R> track(block: () -> R): R {
        val outer = activeReaction
        activeReaction = this
        try {
            return block()
        } finally {
            activeReaction = outer
        }
    }

    /** 依存を全部外す（走り直しの前・破棄とき）。 */
    fun dropDependencies() {
        dependencies.forEach { it.removeObserver(this) }
        dependencies.clear()
    }

    /**
     * この流れで自分がもう走っていれば `false`。走ってよいなら `true` を返して印を付ける。
     *
     * @return 走らせてよいか
     */
    fun claimFlush(): Boolean {
        if (lastFlush == flush) return false
        lastFlush = flush
        return true
    }

    /**
     * 入れ子段を数えながら [block] を走らせる（**自分で読んだ signal を書く反応を無限に走らせない**・判断 D-119）。
     *
     * 反応の中で signal を書くと、その書き込みが次の反応を今の反応の内側で走らせる（スタックが伸びる）。
     * 上限を超えたら**例外**で止める。無限ループでタブが固まるより、どの反応で止まったか出るほうがいい
     *
     * @param R 戻り値の型
     * @param block 走らせる本体
     * @return [block] の戻り値
     */
    protected fun <R> guarded(block: () -> R): R {
        if (reactionDepth >= MAX_REACTION_DEPTH) {
            error(
                "反応が $MAX_REACTION_DEPTH 段以上入れ子で走りました。" +
                    "effect の中で**自分が読んだ signal を書いていないか**確認してください" +
                    "（「読むだけ」なら `untracked` で読めば依存に載りません）",
            )
        }
        reactionDepth++
        try {
            return block()
        } finally {
            reactionDepth--
        }
    }

    /** 依存が変わったときに呼ばれる。 */
    abstract fun reRun()
}

/**
 * [MutableSignal] の実体。
 *
 * @param T 値の型
 * @param initial 初期値
 */
internal class Signal<T>(
    initial: T,
) : MutableSignal<T>,
    SignalNode {
    private var current: T = initial
    private val observers = Observers()

    /**
     * 現在の値。
     *
     * **反応の中で読むと依存に載る**（[Reaction.dependencies] と 1 対 1 で動く）。
     * 同じ値を代入したときは走らない（`==` で見る）。
     */
    override var value: T
        get() {
            activeReaction?.let { react ->
                if (react.dependencies.add(this)) observers.add(react)
            }
            return current
        }
        set(next) {
            if (next == current) return
            current = next
            flush++
            observers.notifyObservers()
        }

    override fun addObserver(reaction: Reaction) {
        observers.add(reaction)
    }

    override fun removeObserver(reaction: Reaction) {
        observers.remove(reaction)
    }
}

/**
 * [derivedOf] の実体。**読んでいない間も式は走っている**（遅延させると、誰も読んでいない時に
 * 依存が集まらず、読んだ瞬間に古い値が見える）。
 *
 * @param T 値の型
 * @param compute 式
 */
internal class Derived<T>(
    private val compute: () -> T,
) : Reaction(),
    DerivedSignal<T>,
    SignalNode {
    private val observers = Observers()

    private var current: T

    init {
        // 作った瞬間に 1 回走らせて依存を集める
        current = track { compute() }
    }

    override val value: T
        get() {
            activeReaction?.let { react ->
                if (react.dependencies.add(this)) observers.add(react)
            }
            return current
        }

    override fun reRun() {
        if (!claimFlush()) return
        guarded {
            dropDependencies()
            val next = track { compute() }
            // 式の結果が変わらなければ下流は走らない（派生を経由した無駄な再描画を止める）
            if (next != current) {
                current = next
                // 派生が値を変えたことも新しい流れにする（古い派生の値で下流が固まるのを防ぐ）
                flush++
                observers.notifyObservers()
            }
        }
    }

    override fun addObserver(reaction: Reaction) {
        observers.add(reaction)
    }

    override fun removeObserver(reaction: Reaction) {
        observers.remove(reaction)
    }

    /** 依存を全部離す（[DerivedSignal.dispose]）。**今の値はそのまま読める**。 */
    override fun dispose() {
        dropDependencies()
    }
}

/**
 * [effectOf] の実体。
 *
 * @param body 副作用（走るたびに新しい [EffectScope] へ後始末を積める）
 */
internal class Effect(
    private val body: EffectScope.() -> Unit,
) : Reaction() {
    private val scope = EffectScope()

    init {
        runBody()
    }

    override fun reRun() {
        if (!claimFlush()) return
        guarded { runBody() }
    }

    /** 後始末 → 依存を離す → 走り直し、の順（順番を入れ替えると**自分の書いた依存を自分で離す**）。 */
    private fun runBody() {
        scope.runCleanups()
        dropDependencies()
        track { body(scope) }
    }

    /** 反応をやめる（後始末を走らせてから依存を全部離す）。 */
    fun dispose() {
        scope.runCleanups()
        dropDependencies()
    }
}
