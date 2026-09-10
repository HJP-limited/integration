package com.example.hjp.desktop

import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.CardEmbeddingEntity
import com.example.hjp.search.CardStore
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * [CardStore] 의 데스크톱 구현. 안드로이드의 `BusinessCardDao` 와 **같은 SQL 을 같은 엔진
 * (SQLite)** 에 던진다.
 *
 * FTS4 의 MATCH 의미(구문·접두어·unicode61 토크나이즈)를 코틀린으로 흉내 내면 같은 질의가
 * 폰과 노트북에서 다른 결과를 내고, 그 순간부터 노트북에서 잰 지표는 앱을 대변하지 못한다.
 * 그래서 스키마와 질의문을 Room 이 만드는 것과 맞춰 둔다 — 바꿀 때는 양쪽을 같이 고칠 것.
 */
class SqliteCardStore(dbPath: String) : CardStore, AutoCloseable {

    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS business_cards (
                    id TEXT NOT NULL PRIMARY KEY,
                    name TEXT, nameEn TEXT, company TEXT, title TEXT, department TEXT,
                    industry TEXT, location TEXT, phone TEXT, email TEXT, address TEXT,
                    memo TEXT, tags TEXT, updatedAtMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
            // Room 의 @Fts4(tokenizer = unicode61, prefix = {2,3,4}) 와 같은 설정.
            st.executeUpdate(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS business_cards_fts USING fts4(
                    card_id, searchable_text, tokenize=unicode61, prefix='2,3,4'
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS card_embeddings (
                    cardId TEXT NOT NULL, modelName TEXT NOT NULL,
                    vector BLOB, dimensions INTEGER NOT NULL,
                    sourceHash TEXT, updatedAtMillis INTEGER NOT NULL,
                    PRIMARY KEY (cardId, modelName)
                )
                """.trimIndent()
            )
        }
    }

    override fun countCards(): Int =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM business_cards").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }

    override fun allCards(): List<BusinessCardEntity> =
        conn.prepareStatement("SELECT * FROM business_cards ORDER BY name").use { ps ->
            ps.executeQuery().use { it.readCards() }
        }

    override fun findCard(id: String): BusinessCardEntity? =
        conn.prepareStatement("SELECT * FROM business_cards WHERE id = ? LIMIT 1").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { it.readCards().firstOrNull() }
        }

    override fun upsertCards(cards: List<BusinessCardEntity>) {
        transaction {
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO business_cards
                (id,name,nameEn,company,title,department,industry,location,phone,email,address,memo,tags,updatedAtMillis)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                cards.forEach { c ->
                    listOf(
                        c.id, c.name, c.nameEn, c.company, c.title, c.department, c.industry,
                        c.location, c.phone, c.email, c.address, c.memo, c.tags,
                    ).forEachIndexed { i, v -> ps.setString(i + 1, v) }
                    ps.setLong(14, c.updatedAtMillis)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
        rebuildFts()
    }

    override fun replaceAllCards(cards: List<BusinessCardEntity>) {
        transaction {
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM business_cards")
                st.executeUpdate("DELETE FROM card_embeddings")
            }
        }
        upsertCards(cards)
    }

    override fun clearFts() {
        conn.createStatement().use { it.executeUpdate("DELETE FROM business_cards_fts") }
    }

    override fun rebuildFts() {
        transaction {
            clearFts()
            conn.prepareStatement(
                "INSERT INTO business_cards_fts (rowid, card_id, searchable_text) VALUES (?,?,?)"
            ).use { ps ->
                allCards().forEachIndexed { index, card ->
                    ps.setInt(1, index + 1)
                    ps.setString(2, card.id)
                    ps.setString(3, card.searchableText())
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    override fun searchFtsIds(matchQuery: String, limit: Int): List<String> =
        conn.prepareStatement(
            "SELECT card_id FROM business_cards_fts WHERE business_cards_fts MATCH ? LIMIT ?"
        ).use { ps ->
            ps.setString(1, matchQuery)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    override fun searchLikeIds(like: String, limit: Int): List<String> =
        conn.prepareStatement(
            """
            SELECT id FROM business_cards WHERE
            name LIKE ? OR nameEn LIKE ? OR company LIKE ? OR title LIKE ?
            OR department LIKE ? OR industry LIKE ? OR location LIKE ?
            OR phone LIKE ? OR email LIKE ? OR address LIKE ?
            OR memo LIKE ? OR tags LIKE ? LIMIT ?
            """.trimIndent()
        ).use { ps ->
            repeat(12) { ps.setString(it + 1, like) }
            ps.setInt(13, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    override fun countEmbeddingsForModel(modelName: String): Int =
        conn.prepareStatement("SELECT COUNT(*) FROM card_embeddings WHERE modelName = ?").use { ps ->
            ps.setString(1, modelName)
            ps.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
        }

    override fun findEmbedding(cardId: String, modelName: String, sourceHash: String): CardEmbeddingEntity? =
        conn.prepareStatement(
            "SELECT * FROM card_embeddings WHERE cardId = ? AND modelName = ? AND sourceHash = ? LIMIT 1"
        ).use { ps ->
            ps.setString(1, cardId)
            ps.setString(2, modelName)
            ps.setString(3, sourceHash)
            ps.executeQuery().use { it.readEmbeddings().firstOrNull() }
        }

    override fun upsertEmbedding(embedding: CardEmbeddingEntity) {
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO card_embeddings
            (cardId, modelName, vector, dimensions, sourceHash, updatedAtMillis) VALUES (?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, embedding.cardId)
            ps.setString(2, embedding.modelName)
            ps.setBytes(3, embedding.vector)
            ps.setInt(4, embedding.dimensions)
            ps.setString(5, embedding.sourceHash)
            ps.setLong(6, embedding.updatedAtMillis)
            ps.executeUpdate()
        }
    }

    override fun embeddingsForModel(modelName: String): List<CardEmbeddingEntity> =
        conn.prepareStatement("SELECT * FROM card_embeddings WHERE modelName = ?").use { ps ->
            ps.setString(1, modelName)
            ps.executeQuery().use { it.readEmbeddings() }
        }

    override fun close() = conn.close()

    private fun transaction(body: () -> Unit) {
        val previous = conn.autoCommit
        conn.autoCommit = false
        try {
            body()
            conn.commit()
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = previous
        }
    }

    private fun ResultSet.readCards(): List<BusinessCardEntity> = buildList {
        while (next()) {
            add(
                BusinessCardEntity(
                    getString("id"), getString("name"), getString("nameEn"),
                    getString("company"), getString("title"), getString("department"),
                    getString("industry"), getString("location"), getString("phone"),
                    getString("email"), getString("address"), getString("memo"),
                    getString("tags"), getLong("updatedAtMillis"),
                )
            )
        }
    }

    private fun ResultSet.readEmbeddings(): List<CardEmbeddingEntity> = buildList {
        while (next()) {
            add(
                CardEmbeddingEntity(
                    getString("cardId"), getString("modelName"), getBytes("vector"),
                    getInt("dimensions"), getString("sourceHash"), getLong("updatedAtMillis"),
                )
            )
        }
    }
}
