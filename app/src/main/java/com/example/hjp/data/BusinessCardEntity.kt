package com.example.hjp.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.hjp.tool.contact.BusinessCardRecord
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "business_cards")
data class BusinessCardEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_en") val nameEn: String,
    val company: String,
    val title: String,
    val department: String,
    val industry: String,
    val location: String,
    val phone: String,
    val mobile: String,
    val email: String,
    val address: String,
    val website: String,
    val memo: String,
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
)

fun BusinessCardEntity.toBusinessCardRecord(json: Json): BusinessCardRecord =
    BusinessCardRecord(
        id = id,
        name = name,
        nameEn = nameEn,
        company = company,
        title = title,
        department = department,
        industry = industry,
        location = location,
        phone = phone,
        mobile = mobile,
        email = email,
        address = address,
        website = website,
        memo = memo,
        tags = runCatching { json.decodeFromString<List<String>>(tagsJson) }.getOrDefault(emptyList()),
        updatedAt = updatedAt,
    )

fun BusinessCardRecord.toBusinessCardEntity(json: Json): BusinessCardEntity =
    BusinessCardEntity(
        id = id,
        name = name,
        nameEn = nameEn,
        company = company,
        title = title,
        department = department,
        industry = industry,
        location = location,
        phone = phone,
        mobile = mobile,
        email = email,
        address = address,
        website = website,
        memo = memo,
        tagsJson = json.encodeToString(tags),
        updatedAt = updatedAt,
    )
