package com.kotsukotsu.core

import com.kotsukotsu.core.points.AnswerIn
import com.kotsukotsu.core.points.PlayInput
import com.kotsukotsu.core.points.PointEngine
import com.kotsukotsu.core.points.PointRules
import com.kotsukotsu.core.points.PriorState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** spec/point-rules-cases.json（サーバと共有）の TC1〜TC13 を Kotlin の実装で実行し、サーバと同じ期待値になることを確認する。 */
class PointRulesCasesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val spec: JsonObject = json.parseToJsonElement(
        javaClass.classLoader!!.getResource("point-rules-cases.json")!!.readText()
    ).jsonObject
    private val rules = json.decodeFromJsonElement(PointRules.serializer(), spec["settings"]!!)

    private data class P(val date: LocalDate, val mode: String, val answers: List<AnswerIn>)

    private fun expand(case: JsonObject): List<P> {
        val out = mutableListOf<P>()
        for (stepEl in case["steps"]!!.jsonArray) {
            val step = stepEl.jsonObject
            val dates = step["date_range"]?.jsonObject?.let { r ->
                val from = LocalDate.parse(r["from"]!!.jsonPrimitive.content)
                val to = LocalDate.parse(r["to"]!!.jsonPrimitive.content)
                generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
            } ?: listOf(LocalDate.parse(step["date"]!!.jsonPrimitive.content))
            val correct = step["correct"]?.jsonPrimitive?.int ?: 9
            val dan = step["dan"]?.jsonPrimitive?.intOrNull
            val pairs = when (step["pattern"]!!.jsonPrimitive.content) {
                "dan" -> (1..9).map { dan!! to it }
                "one_per_dan" -> (1..9).map { it to 1 }
                else -> error("unknown pattern")
            }
            val answers = pairs.mapIndexed { i, (a, b) -> AnswerIn(a, b, i < correct) }
            val repeat = step["repeat"]?.jsonPrimitive?.int ?: 1
            for (d in dates) repeat(repeat) { out += P(d, step["mode"]!!.jsonPrimitive.content, answers) }
        }
        return out
    }

    @Test
    fun allCasesMatchServerExpectations() {
        val cases = spec["cases"]!!.jsonArray.map { it.jsonObject }
        assertEquals((1..16).map { "TC$it" }, cases.map { it["id"]!!.jsonPrimitive.content })
        for (case in cases) runCase(case)
    }

    private fun runCase(case: JsonObject) {
        val id = case["id"]!!.jsonPrimitive.content
        val plays = expand(case)
        val init = case["init"]?.jsonObject
        val played = init?.get("played_dates")?.jsonArray?.map { LocalDate.parse(it.jsonPrimitive.content) }?.toMutableSet() ?: mutableSetOf()
        val monthEarned = mutableMapOf<Pair<Int, Int>, Int>()
        init?.get("month_earned")?.jsonPrimitive?.int?.let { monthEarned[plays[0].date.year to plays[0].date.monthValue] = it }
        val dayQ = mutableMapOf<LocalDate, Int>()
        val dayPts = mutableMapOf<LocalDate, Int>()
        var state = PriorState()
        val results = mutableListOf<Map<String, Int>>()
        var capReachedOn: LocalDate? = null
        for (p in plays) {
            val key = p.date.year to p.date.monthValue
            val prior = state.copy(
                dayQuestions = dayQ[p.date] ?: 0,
                dayStudyPoints = dayPts[p.date] ?: 0,
                monthEarned = monthEarned[key] ?: 0,
                playedDates = played.toSet(),
            )
            val input = PlayInput(p.mode, p.date, p.answers)
            val r = PointEngine.processPlay(rules, prior, input)
            state = PointEngine.advance(prior, input, r)
            played += p.date
            dayQ[p.date] = (dayQ[p.date] ?: 0) + r.overCap.count { !it }
            dayPts[p.date] = (dayPts[p.date] ?: 0) + r.study
            monthEarned[key] = (monthEarned[key] ?: 0) + r.total
            if (capReachedOn == null && monthEarned[key]!! >= rules.monthlyCap) capReachedOn = p.date
            results += mapOf("study" to r.study, "streak_bonus" to r.streakBonus, "self_best" to r.selfBestTotal,
                "over_cap" to r.overCap.count { it })
        }
        case["expect"]?.jsonArray?.forEach { e ->
            val exp = e.jsonObject
            val n = exp["play"]!!.jsonPrimitive.int
            for (k in listOf("study", "streak_bonus", "self_best", "over_cap")) {
                exp[k]?.let { assertEquals("$id play $n $k", it.jsonPrimitive.int, results[n - 1][k]) }
            }
        }
        case["expect_totals"]?.jsonObject?.let { t ->
            val last = plays.last().date
            assertEquals("$id month_total", t["month_total"]!!.jsonPrimitive.int, monthEarned[last.year to last.monthValue])
            assertEquals("$id cap_reached_on", t["cap_reached_on"]!!.jsonPrimitive.content, capReachedOn.toString())
        }
    }

    @Test
    fun streakHelpers() {
        val d = { m: Int, day: Int -> LocalDate.of(2026, m, day) }
        assertEquals(5, PointEngine.computeStreak(d(12, 5), setOf(d(12, 1), d(12, 2), d(12, 3), d(12, 4))))
        assertEquals(1, PointEngine.computeStreak(d(12, 1), setOf(d(11, 30))))
        assertEquals(2, PointEngine.computeCurrentStreak(d(12, 5), setOf(d(12, 3), d(12, 4))))
        assertEquals(0, PointEngine.computeCurrentStreak(d(12, 1), setOf(d(11, 30))))
    }

    @Test
    fun graduationComesFromSettings() {
        // docs/03 5.2.1：卒業済みの段は段選択で設定値の単価。null なら卒業なし。順番モードは対象外
        val prior = PriorState(achieved = setOf(7 to 80, 7 to 90, 7 to 100))
        val sel = PlayInput("training_select", LocalDate.of(2026, 12, 1), (1..9).map { AnswerIn(7, it, true) })
        assertEquals(0, PointEngine.processPlay(rules, prior, sel).study)
        assertEquals(9, PointEngine.processPlay(rules.copy(graduation = null), prior, sel).study)
        assertEquals(9, PointEngine.processPlay(rules, prior, sel.copy(mode = "training_sequential")).study)
        val g = rules.graduation!!.copy(rate = 90, pointsPerCorrect = 1)
        assertEquals(9, PointEngine.processPlay(rules.copy(graduation = g), PriorState(achieved = setOf(7 to 90)), sel).study)
        // 古い版の設定（graduation なし）も読める
        val old = json.decodeFromString(PointRules.serializer(),
            """{"points_per_correct":{"training_sequential":1,"training_select":1,"challenge":2},"streak_bonus_per_day":1,
               "daily_point_cap":40,"daily_question_cap":117,"monthly_cap":1500,"self_best":{"window":27,"thresholds":[]}}""")
        assertEquals(null, old.graduation)
    }
}
