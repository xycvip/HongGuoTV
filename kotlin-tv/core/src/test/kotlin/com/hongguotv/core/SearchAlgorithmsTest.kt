package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAlgorithmsTest {
    @Test fun seasonNumbersHandleArabicAndChineseForms() {
        assertEquals(1, seasonNumber("星河故事第1季"))
        assertEquals(10, seasonNumber("星河故事第十季"))
        assertEquals(12, seasonNumber("星河故事第十二部"))
        assertEquals(23, seasonNumber("星河故事第23季"))
        assertEquals(Int.MAX_VALUE, seasonNumber("星河故事特别篇"))
    }

    @Test fun seasonBaseNameRemovesOnlySeasonSuffix() {
        assertEquals("星河故事", baseName("星河故事第十二季"))
        assertEquals("星河故事", baseName(" 星河故事 第2部 "))
        assertEquals("星河故事特别篇", baseName("星河故事特别篇"))
    }

    @Test fun seasonLabelsUseChineseNumbers() {
        assertEquals("第一季", seasonLabel(1))
        assertEquals("第九季", seasonLabel(9))
        assertEquals("第十季", seasonLabel(10))
        assertEquals("第十二季", seasonLabel(12))
        assertEquals("第二十季", seasonLabel(20))
        assertEquals("第二十三季", seasonLabel(23))
        assertEquals("系列", seasonLabel(0))
    }
}
