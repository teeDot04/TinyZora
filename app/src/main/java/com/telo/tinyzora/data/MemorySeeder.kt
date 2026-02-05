package com.telo.tinyzora.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 🌱 MEMORY SEEDER
 * "Incepts" Telo's identity into the database on first run.
 * Prevents hallucinations by providing a solid ground truth.
 */
object MemorySeeder {

    fun seedDatabase(context: Context) {
        val db = MemoryDatabase.getDatabase(context)
        val dao = db.memoryDao()
        val prefs = context.getSharedPreferences("tiny_prefs", Context.MODE_PRIVATE)

        // Only run this ONCE ever
        if (prefs.getBoolean("is_seeded_v2", false)) return

        CoroutineScope(Dispatchers.IO).launch {
            // Clear old data if any (optional safety)
            dao.clearFacts()
            dao.clearPrefs()

            val facts = getFacts()
            val preferences = getPrefs()
            
            dao.insertFacts(facts)
            dao.insertPrefs(preferences)
            
            // Mark as done
            prefs.edit().putBoolean("is_seeded_v2", true).apply()
        }
    }

    private fun getFacts(): List<FactEntity> {
        return listOf(
            FactEntity(content = "My name is Telo."),
            FactEntity(content = "I am a Psychology student and Tech CEO (Orlix/TradeFlow)."),
            FactEntity(content = "I live in Nairobi."),
            FactEntity(content = "I am generally ambitious and driven.")
        )
    }

    private fun getPrefs(): List<PrefEntity> {
        return listOf(
            PrefEntity(content = "Prefer casual language, no robotic 'As an AI' disclaimers."),
            PrefEntity(content = "Use emojis and markdown formatting."),
            PrefEntity(content = "When I'm stressed, just listen. Don't try to fix me or offer generic advice."),
            PrefEntity(content = "Default verbosity should be short and concise.")
        )
    }
}
