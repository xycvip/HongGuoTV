package com.hongguotv.nativeapp

import com.hongguotv.core.ContentRepository
import com.hongguotv.core.ContentType
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** 仅用于本地预览素材抓取：从真实接口取一批海报并落到磁盘。 */
class CatalogFetchTest {
    @Test
    fun fetchPosters() {
        val repo = ContentRepository()
        val out = File("/workspace/preview/posters").apply { mkdirs() }
        val rows = mutableListOf<String>()
        ContentType.entries.forEach { type ->
            val page = runCatching {
                if (type == ContentType.AI) repo.ranking(com.hongguotv.core.RankBoard.AI, 1) else repo.home(1, type)
            }.getOrNull() ?: return@forEach
            page.items.take(18).forEach { item ->
                rows += "${item.id}\t${item.title}\t${item.cover}\t${item.badge}\t${item.tags}"
            }
        }
        File("/workspace/preview/series.tsv").writeText(rows.joinToString("\n"))
        rows.take(30).forEachIndexed { index, row ->
            val cover = row.split("\t").getOrNull(2).orEmpty()
            if (!cover.startsWith("http")) return@forEachIndexed
            val target = File(out, "p$index.jpg")
            if (target.exists()) return@forEachIndexed
            runCatching {
                java.net.URL(cover).openStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
        }
        println("Fetched ${rows.size} series, ${out.listFiles()?.size ?: 0} posters")
    }
}
