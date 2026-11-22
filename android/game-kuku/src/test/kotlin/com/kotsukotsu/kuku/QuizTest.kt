package com.kotsukotsu.kuku

import com.kotsukotsu.core.points.Modes
import com.kotsukotsu.core.points.PointRules
import com.kotsukotsu.core.points.PriorState
import com.kotsukotsu.core.points.SelfBest
import com.kotsukotsu.core.points.Threshold
import com.kotsukotsu.core.sync.LocalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class QuizTest {
    @Test
    fun sequentialIsInOrder() {
        assertEquals((1..9).map { Question(4, it) }, Quiz.questions(Modes.SEQUENTIAL, 4))
    }

    @Test
    fun selectIsShuffledSameDan() {
        val q = Quiz.questions(Modes.SELECT, 7, random = Random(1))
        assertEquals(9, q.size)
        assertTrue(q.all { it.a == 7 })
        assertEquals((1..9).toSet(), q.map { it.b }.toSet())
    }

    @Test
    fun challengeIsNineDistinctFrom81() {
        repeat(50) { seed ->
            val q = Quiz.questions(Modes.CHALLENGE, null, random = Random(seed))
            assertEquals(9, q.size)
            assertEquals(9, q.toSet().size)
            assertTrue(q.all { it.a in 1..9 && it.b in 1..9 })
        }
    }

    @Test
    fun fewerQuestionsWhenDailyCapAlmostReached() {
        assertEquals(5, Quiz.questions(Modes.CHALLENGE, null, count = 5).size)
    }

    @Test
    fun encouragementsCount() {
        assertTrue(Quiz.ENCOURAGEMENTS.size in 20..30)
    }

    private val rules = PointRules(
        version = 1, pointsPerCorrect = mapOf("training_sequential" to 1, "training_select" to 1, "challenge" to 2),
        streakBonusPerDay = 1, dailyPointCap = 40, dailyQuestionCap = 117, monthlyCap = 1500,
        selfBest = SelfBest(27, listOf(Threshold(80, 10), Threshold(90, 20), Threshold(100, 30))),
    )

    private fun view(q: Int, pts: Int, month: Int) =
        LocalView(LocalDate.of(2026, 12, 1), 1, 0.9, 0, q, pts, month, 1, 0, PriorState(), rules)

    @Test
    fun bannersOnlyWhenNewlyReached() {
        assertEquals(listOf(Banner.DAY_POINTS_FULL), Banner.compute(view(18, 36, 100), view(27, 40, 104)))
        assertEquals(emptyList<Banner>(), Banner.compute(view(27, 40, 104), view(36, 40, 104))) // 2回目は出さない
        assertEquals(listOf(Banner.DAY_DONE), Banner.compute(view(108, 40, 200), view(117, 40, 200)))
        assertEquals(listOf(Banner.MONTH_DONE), Banner.compute(view(9, 9, 1495), view(18, 14, 1500)))
    }
}
