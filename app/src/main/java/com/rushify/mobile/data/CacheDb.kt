package com.rushify.mobile.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** One JSON blob per response, keyed by "server|userId|name". Not normalised on purpose. */
@Entity(tableName = "cache")
data class CacheEntry(@PrimaryKey val key: String, val json: String, val fetchedAt: Long)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE `key` = :key") suspend fun get(key: String): CacheEntry?
    @Upsert suspend fun put(entry: CacheEntry)
    @Query("DELETE FROM cache") suspend fun clear()
}

@Database(entities = [CacheEntry::class], version = 1, exportSchema = true)
abstract class CacheDb : RoomDatabase() {
    abstract fun dao(): CacheDao
}
