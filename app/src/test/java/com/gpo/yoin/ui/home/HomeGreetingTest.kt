package com.gpo.yoin.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeGreetingTest {

    @Test
    fun should_greetTheMorning_when_hourIsFiveToTen() {
        (5..10).forEach { assertEquals(HomeDayPart.Morning, homeDayPartAt(it)) }
    }

    @Test
    fun should_greetNoon_when_hourIsElevenOrTwelve() {
        listOf(11, 12).forEach { assertEquals(HomeDayPart.Noon, homeDayPartAt(it)) }
    }

    @Test
    fun should_greetTheAfternoon_when_hourIsOneToFivePm() {
        (13..17).forEach { assertEquals(HomeDayPart.Afternoon, homeDayPartAt(it)) }
    }

    @Test
    fun should_greetTheEvening_when_hourIsSixPmToFourAm() {
        (listOf(18, 19, 20, 21, 22, 23) + (0..4)).forEach { assertEquals(HomeDayPart.Evening, homeDayPartAt(it)) }
    }
}
