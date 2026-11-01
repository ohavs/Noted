package com.ohav.noted

import android.app.Application
import com.ohav.noted.data.NotedDatabase
import com.ohav.noted.data.NotesRepository
import com.ohav.noted.settings.SettingsRepository
import com.ohav.noted.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotedApp : Application() {
    val scope = CoroutineScope(SupervisorJob())
    lateinit var notes: NotesRepository private set
    lateinit var settings: SettingsRepository private set
    lateinit var updater: Updater private set

    override fun onCreate() {
        super.onCreate()
        notes = NotesRepository(NotedDatabase.create(this).notes())
        settings = SettingsRepository(this)
        updater = Updater(this)
        scope.launch { notes.seedIfEmpty() }
        scope.launch { updater.check(quiet = true) }
    }
}
