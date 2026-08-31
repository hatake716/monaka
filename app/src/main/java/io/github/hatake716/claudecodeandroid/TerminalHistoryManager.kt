package io.github.hatake716.claudecodeandroid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ターミナルのやりとり(出力トランスクリプト)を端末内に保存し、
 * 後日一覧から選んで内容を閲覧・再開できるよう記録を管理する。
 *
 * 保存先: filesDir/terminal-history/
 *   - <id>.log   … トランスクリプト全文(プレーンテキスト)
 *   - index.json … 各記録のメタ情報(id, container, title, updatedAt, preview)
 *
 * PTY プロセスは終了すると同じシェル状態には戻せない(Android の仕様)ため、
 * 「再開」は「保存したログを閲覧しつつ、同じコンテナで新しいシェルを開いて
 * 作業を続ける」形で提供する（[HistoryActivity] / [EmbeddedTerminalActivity]）。
 */
object TerminalHistoryManager {
    private const val DIR = "terminal-history"
    private const val INDEX = "index.json"
    private const val MAX_RECORDS = 100
    private const val PREVIEW_CHARS = 120

    data class Record(
        val id: String,
        val container: String,
        val title: String,
        val updatedAt: Long,
        val preview: String,
        /** ユーザーが付けた名前。空なら自動タイトル([title])を表示に使う。 */
        val name: String = ""
    ) {
        fun logFileName(): String = "$id.log"

        /** 一覧などに表示する名前。ユーザー名があればそれを、なければ自動タイトル。 */
        fun displayName(): String = if (name.isNotBlank()) name else title
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    private fun indexFile(context: Context): File = File(dir(context), INDEX)

    private fun logFile(context: Context, id: String): File = File(dir(context), "$id.log")

    /**
     * 記録を保存/更新する。同じ id があれば上書きし、index を更新する。
     * transcript が空なら記録しない（空セッションを一覧に残さない）。
     * 生成した Record を返す（空スキップ時は null）。
     */
    fun save(context: Context, id: String, container: String, transcript: String): Record? {
        val trimmed = transcript.trimEnd('\n', ' ', '\t')
        if (trimmed.isBlank()) return null

        runCatching { logFile(context, id).writeText(transcript) }
            .onFailure { return null }

        val now = System.currentTimeMillis()
        // 同じ id を再保存する場合、ユーザーが付けた名前は引き継ぐ。
        val existingName = load(context).firstOrNull { it.id == id }?.name ?: ""
        val record = Record(
            id = id,
            container = container,
            title = buildTitle(container, now),
            updatedAt = now,
            preview = buildPreview(trimmed),
            name = existingName
        )

        val records = load(context).filterNot { it.id == id }.toMutableList()
        records.add(0, record)
        // 上限を超えた古い記録はログごと削除する。
        while (records.size > MAX_RECORDS) {
            val dropped = records.removeAt(records.size - 1)
            runCatching { logFile(context, dropped.id).delete() }
        }
        writeIndex(context, records)
        return record
    }

    /** 記録を新しい順に一覧取得する。 */
    fun load(context: Context): List<Record> {
        val raw = indexFile(context).let { if (it.isFile) it.readText() else null } ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        Record(
                            id = o.getString("id"),
                            container = o.optString("container", "?"),
                            title = o.optString("title", "セッション"),
                            updatedAt = o.optLong("updatedAt", 0L),
                            preview = o.optString("preview", ""),
                            name = o.optString("name", "")
                        )
                    )
                }
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    /** 記録のトランスクリプト全文を読む。無ければ null。 */
    fun readTranscript(context: Context, id: String): String? =
        logFile(context, id).let { if (it.isFile) it.readText() else null }

    /** 記録1件を削除する（ログ + index から）。 */
    fun delete(context: Context, id: String) {
        runCatching { logFile(context, id).delete() }
        writeIndex(context, load(context).filterNot { it.id == id })
    }

    /** 記録に名前を付ける/変更する。空文字を渡すと名前を消して自動タイトルに戻す。 */
    fun rename(context: Context, id: String, newName: String) {
        val trimmed = newName.trim()
        val records = load(context).map {
            if (it.id == id) it.copy(name = trimmed) else it
        }
        writeIndex(context, records)
    }

    /** 全記録を削除する。 */
    fun clear(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    /** 新しい記録用の id を採番する（時刻ベース、衝突しにくい）。 */
    fun newId(startedAt: Long): String = "sess-$startedAt"

    private fun writeIndex(context: Context, records: List<Record>) {
        val array = JSONArray()
        records.forEach { r ->
            array.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("container", r.container)
                    put("title", r.title)
                    put("updatedAt", r.updatedAt)
                    put("preview", r.preview)
                    put("name", r.name)
                }
            )
        }
        runCatching { indexFile(context).writeText(array.toString()) }
    }

    private fun buildTitle(container: String, at: Long): String {
        // 端末ロケール依存を避けるため、固定書式で日時を組み立てる。
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val y = cal.get(java.util.Calendar.YEAR)
        val mo = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val mi = cal.get(java.util.Calendar.MINUTE)
        return "%s · %04d-%02d-%02d %02d:%02d".format(container, y, mo, d, h, mi)
    }

    private fun buildPreview(transcript: String): String {
        // 末尾（最新の出力）から意味のある行を拾ってプレビューにする。
        val lines = transcript.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        val tail = lines.takeLast(3).joinToString(" / ")
        return tail.take(PREVIEW_CHARS)
    }
}
