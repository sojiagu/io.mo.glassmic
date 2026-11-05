package io.mo.glassmic.data.audio

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通过 MediaStore 搜索本地 MP3（依赖 READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE）。
 */
@Singleton
class LocalMp3Scanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Hit(
        val uri: Uri,
        val displayName: String,
        val durationMs: Long,
        val sizeBytes: Long,
    )

    suspend fun search(query: String, limit: Int = 40): List<Hit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
        )
        // 限制为 MP3：mime 为 audio/mpeg，或文件名以 .mp3 结尾；再按显示名模糊匹配。
        val selection = """
            (
              ${MediaStore.Audio.Media.MIME_TYPE} = ? OR
              LOWER(${MediaStore.Audio.Media.DISPLAY_NAME}) LIKE ?
            )
            AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?
        """.trimIndent().replace('\n', ' ')
        val args = arrayOf(
            "audio/mpeg",
            "%.mp3",
            "%$q%",
        )
        val sort = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        val out = ArrayList<Hit>(limit.coerceAtMost(40))
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                sort,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id,
                    )
                    val durationMs = if (durCol >= 0 && !c.isNull(durCol)) c.getLong(durCol) else 0L
                    val sizeBytes = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L
                    out += Hit(uri = uri, displayName = name, durationMs = durationMs, sizeBytes = sizeBytes)
                }
            }
        }.onFailure {
            GlassLog.b("LocalMp3") { "MediaStore 搜索失败: ${it.message}" }
        }
        out
    }
}
