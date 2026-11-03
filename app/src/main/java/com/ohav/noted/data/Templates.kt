package com.ohav.noted.data

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.ohav.noted.design.NoteColor
import com.ohav.noted.design.Ph
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * A template of mine: a note's shape (title, rows, colour, category) to start new notes from.
 * Rows are kept without media and with every checkbox clear. [deleted] is a tombstone so the
 * deletion reaches my other devices; the newest [updatedAt] wins between devices.
 */
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val title: String,
    val blocks: String,
    val color: String,
    val categoryId: String,
    val position: Double,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates WHERE deleted = 0 ORDER BY position ASC")
    fun observeLive(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates")
    suspend fun all(): List<TemplateEntity>

    @Query("SELECT * FROM templates WHERE id = :id")
    suspend fun get(id: String): TemplateEntity?

    @Query("SELECT MIN(position) FROM templates WHERE deleted = 0")
    suspend fun minPosition(): Double?

    @Upsert
    suspend fun upsert(t: TemplateEntity)

    @Upsert
    suspend fun upsertAll(t: List<TemplateEntity>)
}

/** What the template sheet shows: one of mine or one that ships with the app. */
data class Template(
    val id: String,
    val name: String,
    val title: String,
    val blocks: List<Block>,
    val color: NoteColor,
    /** Where a note from it lands ("" = wherever + was pressed). */
    val categoryId: String,
    val icon: ImageVector,
    val builtin: Boolean,
)

class Templates(private val db: NotedDatabase) {
    private val dao = db.templates()

    val mine: Flow<List<Template>> = dao.observeLive().map { l -> l.map { it.toTemplate() } }

    /** Every row, tombstones too (sync). */
    val rows: Flow<List<TemplateEntity>> = dao.observeAll()

    suspend fun all(): List<TemplateEntity> = dao.all()
    suspend fun get(id: String): TemplateEntity? = dao.get(id)
    suspend fun put(t: TemplateEntity) = dao.upsert(t)
    suspend fun putAll(t: List<TemplateEntity>) = dao.upsertAll(t)

    /** A note as a new template of mine, first in the list. */
    suspend fun saveFrom(name: String, title: String, blocks: List<Block>, color: NoteColor, categoryId: String): TemplateEntity {
        val t = TemplateEntity(
            id = UUID.randomUUID().toString(), name = name.trim(), title = title.replace(ZW, "").trim(),
            blocks = Blocks.serialize(shape(blocks)), color = color.name, categoryId = categoryId,
            position = (dao.minPosition() ?: 0.0) - 1.0, updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(t)
        return t
    }

    suspend fun rename(id: String, name: String) {
        val t = dao.get(id) ?: return
        dao.upsert(t.copy(name = name.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun setDeleted(id: String, deleted: Boolean) {
        val t = dao.get(id) ?: return
        dao.upsert(t.copy(deleted = deleted, updatedAt = System.currentTimeMillis()))
    }

    companion object {
        private const val ZW = "​"

        /** Text and structure only: no media, every checkbox clear, no stray zero-width marks. */
        fun shape(blocks: List<Block>): List<Block> = blocks
            .filter { it.type !in MEDIA }
            .map { it.copy(text = it.text.replace(ZW, ""), done = false, spans = shift(it.text, it.spans), extra = "") }

        /** Spans counted the text with its zero-width marks; [shape] drops them. */
        private fun shift(text: String, spans: List<Span>): List<Span> {
            if (!text.contains(ZW) || spans.isEmpty()) return spans
            fun at(i: Int) = i - text.take(i).count { it == '​' }
            return spans.map { Span(at(it.start), at(it.end), it.style) }.filter { it.end > it.start }
        }

        private val MEDIA = setOf(BlockType.Images, BlockType.Voice, BlockType.Draw, BlockType.Link)

        /** A note's rows from a template: fresh ids, so two notes never share a row. */
        fun rowsFor(t: Template): List<Block> = t.blocks.map { it.copy(id = UUID.randomUUID().toString(), done = false) }

        fun TemplateEntity.toTemplate() = Template(
            id, name, title, Blocks.parse(blocks), NoteColor.of(color), categoryId, Ph.Cards, builtin = false,
        )

        private fun b(type: BlockType, text: String = "") = Block("", type, text)

        /** The four that ship with the app. Built when asked, so the dates in them are today's. */
        fun builtins(today: LocalDate = LocalDate.now()): List<Template> {
            val he = Locale.forLanguageTag("he")
            val dm = "${today.dayOfMonth}/${today.monthValue}"
            val day = today.dayOfWeek.getDisplayName(TextStyle.FULL, he)
            return listOf(
                Template(
                    "b:shop", "קניות", "קניות", listOf(
                        b(BlockType.H3, "ירקות ופירות"), b(BlockType.Check),
                        b(BlockType.H3, "מקרר"), b(BlockType.Check),
                        b(BlockType.H3, "מזווה"), b(BlockType.Check),
                        b(BlockType.H3, "לבית"), b(BlockType.Check),
                    ), NoteColor.Butter, "", Ph.ShoppingCart, true,
                ),
                Template(
                    "b:pack", "אריזה לטיול", "אריזה לטיול", listOf(
                        b(BlockType.H3, "מסמכים"),
                        b(BlockType.Check, "דרכון / תעודת זהות"), b(BlockType.Check, "כרטיסים והזמנות"), b(BlockType.Check, "ביטוח נסיעות"),
                        b(BlockType.H3, "בגדים"),
                        b(BlockType.Check, "חולצות"), b(BlockType.Check, "מכנסיים"), b(BlockType.Check, "תחתונים וגרביים"),
                        b(BlockType.Check, "פיג׳מה"), b(BlockType.Check, "ז׳קט"),
                        b(BlockType.H3, "רחצה"),
                        b(BlockType.Check, "מברשת ומשחת שיניים"), b(BlockType.Check, "דאודורנט"), b(BlockType.Check, "תרופות"),
                        b(BlockType.H3, "חשמל"),
                        b(BlockType.Check, "מטענים"), b(BlockType.Check, "סוללה ניידת"), b(BlockType.Check, "מתאם לשקע"), b(BlockType.Check, "אוזניות"),
                    ), NoteColor.Sky, "", Ph.Suitcase, true,
                ),
                Template(
                    "b:meet", "ישיבה", "ישיבה · $dm", listOf(
                        b(BlockType.P, "משתתפים: "),
                        b(BlockType.H3, "על הפרק"), b(BlockType.Num),
                        b(BlockType.H3, "החלטות"), b(BlockType.Bullet),
                        b(BlockType.H3, "משימות"), b(BlockType.Check),
                    ), NoteColor.Lilac, "", Ph.UsersThree, true,
                ),
                Template(
                    "b:day", "תוכנית ליום", "$day $dm", listOf(
                        b(BlockType.H3, "שלושת הדברים של היום"), b(BlockType.Num), b(BlockType.Num), b(BlockType.Num),
                        b(BlockType.H3, "משימות"), b(BlockType.Check),
                        b(BlockType.H3, "הערות"), b(BlockType.P),
                    ), NoteColor.Mint, "", Ph.SunHorizon, true,
                ),
            )
        }
    }
}
