package com.example.hjp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.data.BusinessCardEntity
import com.example.hjp.data.HjpDatabase
import com.example.hjp.data.RoomBusinessCardRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** v4 사용자의 카드 데이터는 보존하고, v5 규칙으로 FTS만 다시 만드는 경로를 검증한다. */
@RunWith(AndroidJUnit4::class)
class HjpDatabaseV5MigrationInstrumentedTest {
    @Test
    fun migrationFourToFivePreservesCardsAndRebuildsFtsThroughRepository() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "hjp-migration-4-5-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            // 현재 스키마로 v4와 구조가 같은 DB를 만든 뒤 색인만 옛 내용으로 바꾸고 버전을 내린다.
            // 4→5는 테이블 구조 변경이 아니라 색인 규칙 변경이므로 이게 실제 업그레이드 조건이다.
            val before = Room.databaseBuilder(context, HjpDatabase::class.java, databaseName).build()
            before.businessCardDao().insertAllAndReindex(
                listOf(
                    BusinessCardEntity(
                        id = "migration-card",
                        name = "김판교",
                        nameEn = "Pangyo Kim",
                        company = "통합테스트",
                        title = "개발자",
                        department = "플랫폼",
                        industry = "소프트웨어",
                        location = "성남",
                        phone = "02-1234-5678",
                        mobile = "010-1234-5678",
                        email = "migration@example.com",
                        address = "경기도 성남시 분당구 판교역로 123",
                        website = "",
                        memo = "",
                        tagsJson = "[]",
                        updatedAt = "2026-09-14",
                    ),
                ),
            )
            before.openHelper.writableDatabase.execSQL(
                "UPDATE business_cards_fts SET searchable_text = 'legacy_only_token'",
            )
            before.close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { it.version = 4 }

            val migrated = Room.databaseBuilder(context, HjpDatabase::class.java, databaseName)
                .addMigrations(HjpDatabase.MIGRATION_4_5)
                .build()
            try {
                val dao = migrated.businessCardDao()
                assertEquals(1, dao.count())
                assertEquals(0, dao.countFts())

                val repository = RoomBusinessCardRepository(context, dao)
                assertEquals(listOf("migration-card"), repository.loadAll().map { it.id })
                assertEquals(1, dao.countFts())
                assertTrue(dao.searchFtsIds("판교역로", 5).contains("migration-card"))
                assertFalse(dao.searchFtsIds("legacy_only_token", 5).contains("migration-card"))
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
