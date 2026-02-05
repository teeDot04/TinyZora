package com.telo.tinyzora.data

import android.content.Context
import androidx.room.*

// 1. FACTS: Stable truths about the user or world
@Entity(tableName = "facts")
data class FactEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

// 2. PREFS: User preferences for behavior
@Entity(tableName = "prefs")
data class PrefEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

// 3. REMINDERS: Internal calendar/nudges
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val dueTime: String, // ISO or simple time string
    val isCompleted: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface MemoryDao {
    // --- FACTS ---
    @Insert
    suspend fun insertFact(fact: FactEntity)

    @Insert
    suspend fun insertFacts(facts: List<FactEntity>)

    @Query("SELECT * FROM facts ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomFacts(limit: Int): List<FactEntity>

    @Query("SELECT * FROM facts")
    suspend fun getAllFacts(): List<FactEntity>

    @Delete
    suspend fun deleteFact(fact: FactEntity)

    // --- PREFS ---
    @Insert
    suspend fun insertPref(pref: PrefEntity)

    @Insert
    suspend fun insertPrefs(prefs: List<PrefEntity>)

    @Query("SELECT * FROM prefs ORDER BY RANDOM() LIMIT :limit")
    suspend fun getRandomPrefs(limit: Int): List<PrefEntity>

    @Query("SELECT * FROM prefs")
    suspend fun getAllPrefs(): List<PrefEntity>

    @Delete
    suspend fun deletePref(pref: PrefEntity)

    // --- REMINDERS ---
    @Insert
    suspend fun insertReminder(reminder: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE isCompleted = 0")
    suspend fun getActiveReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders")
    suspend fun getAllReminders(): List<ReminderEntity>

    @Query("UPDATE reminders SET isCompleted = 1 WHERE id = :id")
    suspend fun markReminderComplete(id: Int)
    
    @Delete
    suspend fun deleteReminder(reminder: ReminderEntity)

    @Query("DELETE FROM reminders WHERE dueTime < :currentTimeStr AND dueTime != ''")
    suspend fun deleteExpiredReminders(currentTimeStr: String)
    
    // --- LEGACY CLEANUP (Optional) ---
    @Query("DELETE FROM facts")
    suspend fun clearFacts()
    
    @Query("DELETE FROM prefs")
    suspend fun clearPrefs()
}

@Database(entities = [FactEntity::class, PrefEntity::class, ReminderEntity::class], version = 2)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile private var INSTANCE: MemoryDatabase? = null

        fun getDatabase(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                // Destroy old DB on version change for development speed (since we are changing schema drastically)
                Room.databaseBuilder(context.applicationContext, MemoryDatabase::class.java, "tiny_memory_db")
                    // .fallbackToDestructiveMigration() // DISABLED: Data is now persistent.
                    .build().also { INSTANCE = it }
            }
        }
    }
}
