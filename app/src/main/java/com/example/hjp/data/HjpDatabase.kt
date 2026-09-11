package com.example.hjp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BusinessCardEntity::class, CardEmbeddingEntity::class, BusinessCardFtsEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class HjpDatabase : RoomDatabase() {
    abstract fun businessCardDao(): BusinessCardDao

    companion object {
        @Volatile private var instance: HjpDatabase? = null

        fun getInstance(context: Context): HjpDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HjpDatabase::class.java,
                    "hjp-agent.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `card_embeddings` (
                        `card_id` TEXT NOT NULL,
                        `model_name` TEXT NOT NULL,
                        `dimension` INTEGER NOT NULL,
                        `vector_blob` BLOB NOT NULL,
                        `source_text_hash` TEXT NOT NULL,
                        `created_at_millis` INTEGER NOT NULL,
                        `updated_at_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`card_id`, `model_name`),
                        FOREIGN KEY(`card_id`) REFERENCES `business_cards`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_embeddings_card_id` " +
                        "ON `card_embeddings` (`card_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_card_embeddings_model_name` " +
                        "ON `card_embeddings` (`model_name`)",
                )
            }
        }

        /**
         * 검색 인덱스에 주소와 이메일을 넣는다. 카드·임베딩은 건드리지 않는다.
         *
         * 빠져 있던 동안 "판교"처럼 **주소에만 있는 낱말**이 키워드로 한 건도 안 잡혔다
         * (지역 칸은 "경기도 성남시 분당구"라 판교가 없다). 그러면 의미검색 결과만 남아
         * 엉뚱한 지역 사람이 나온다 — 실측으로 확인하고 되살렸다.
         *
         * 인덱스만 다시 만들면 되므로 버전을 올려 재구성한다. 문자열 구성은
         * `BusinessCardDao.toFtsEntity` 가 정본이고 이 SQL 은 그것과 같은 순서여야 한다.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM business_cards_fts")
                db.execSQL(
                    """
                    INSERT INTO business_cards_fts(rowid, card_id, searchable_text)
                    SELECT rowid, id,
                        lower(
                            name || ' ' || name_en || ' ' || company || ' ' || title || ' ' ||
                            department || ' ' || industry || ' ' || location || ' ' ||
                            address || ' ' || email || ' ' || memo || ' ' ||
                            tags_json || ' ' || phone || ' ' || mobile || ' ' ||
                            replace(replace(replace(replace(replace(phone, '-', ''), ' ', ''), '(', ''), ')', ''), '+', '') || ' ' ||
                            replace(replace(replace(replace(replace(mobile, '-', ''), ' ', ''), '(', ''), ')', ''), '+', '')
                        )
                    FROM business_cards
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds the Ryeong unicode61/prefix FTS index without touching cards or embeddings.
         * FTS rowids are internal only; card_id remains the stable Room string ID.
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `business_cards_fts`")
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `business_cards_fts` " +
                        "USING FTS4(`card_id` TEXT NOT NULL, `searchable_text` TEXT NOT NULL, " +
                        "tokenize=unicode61, prefix=`2,3,4`)",
                )
                db.execSQL(
                    """
                    INSERT INTO business_cards_fts(rowid, card_id, searchable_text)
                    SELECT rowid, id,
                        lower(
                            name || ' ' || name_en || ' ' || company || ' ' || title || ' ' ||
                            department || ' ' || industry || ' ' || location || ' ' || memo || ' ' ||
                            tags_json || ' ' || phone || ' ' || mobile || ' ' ||
                            replace(replace(replace(replace(replace(phone, '-', ''), ' ', ''), '(', ''), ')', ''), '+', '') || ' ' ||
                            replace(replace(replace(replace(replace(mobile, '-', ''), ' ', ''), '(', ''), ')', ''), '+', '')
                        )
                    FROM business_cards
                    """.trimIndent(),
                )
            }
        }
    }
}
