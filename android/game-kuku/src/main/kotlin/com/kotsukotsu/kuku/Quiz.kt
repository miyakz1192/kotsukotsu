package com.kotsukotsu.kuku

import com.kotsukotsu.core.points.Modes
import kotlin.random.Random

data class Question(val a: Int, val b: Int) {
    val answer: Int get() = a * b
}

/** 出題（docs/06 1.3）。 */
object Quiz {
    const val PER_MODE = 9

    /** @param count 出題数（ふだんは9。1日の問題数上限の残りが9未満ならその数） */
    fun questions(mode: String, dan: Int?, count: Int = PER_MODE, random: Random = Random.Default): List<Question> {
        val n = count.coerceIn(0, PER_MODE)
        return when (mode) {
            Modes.SEQUENTIAL -> (1..9).map { Question(dan!!, it) }.take(n) // ×1〜×9 の順
            Modes.SELECT -> (1..9).shuffled(random).map { Question(dan!!, it) }.take(n)
            Modes.CHALLENGE -> (1..9).flatMap { a -> (1..9).map { b -> Question(a, b) } }.shuffled(random).take(n) // 81題から重複なし
            else -> error("unknown mode $mode")
        }
    }

    /** 励ましの言葉（docs/08 A5。20〜30個からランダム。文面は家庭で自由に変えてよい） */
    val ENCOURAGEMENTS = listOf(
        "きょうも いっしょに がんばろう！",
        "まいにち つづけると ちからに なるよ！",
        "まちがえても だいじょうぶ。おぼえる チャンスだよ！",
        "きのうの じぶんより ちょっと つよく なろう！",
        "くくマスターへの みち、いっぽずつ！",
        "ゆっくりでも いいよ。せいかくに いこう！",
        "きょうは どの だんに ちょうせんする？",
        "がんばる きみは かっこいい！",
        "れんしゅうは うそを つかないよ！",
        "さあ、のうみそ ウォーミングアップ！",
        "こつこつが いちばん つよい！",
        "にがてな だんも、きっと とくいに なるよ！",
        "いい ちょうし！ そのまま いこう！",
        "きょうの きみも さいこうだよ！",
        "しゅうちゅう、しゅうちゅう！",
        "1もん 1もん、ていねいに！",
        "こたえが すぐ でたら すごいね！",
        "チャレンジする きもちが たいせつ！",
        "ポイントを ためて おさいふを ふやそう！",
        "つづけた ひの かずが じまんに なるよ！",
        "まちがいは せいちょうの たね！",
        "きょうも くくで あそぼう！",
        "あたまの たいそう、はじめよう！",
        "ひとつ できたら じしんに なるよ！",
        "きみなら できる！",
    )
}
