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

    @Test fun searchScoringKeepsExactAndPrefixAboveLooseMatches() {
        assertTrue(titleMatchScore("星河故事", "星河故事") > titleMatchScore("星河故事特别篇", "星河故事"))
        assertTrue(titleMatchScore("星河故事", "星河") > titleMatchScore("星河的故事", "星河"))
        assertEquals(0, titleMatchScore("完全无关", "星河"))
    }
}
