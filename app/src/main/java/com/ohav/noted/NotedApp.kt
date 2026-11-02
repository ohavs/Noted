package com.ohav.noted

import android.app.Application
import com.ohav.noted.data.NotedDatabase
import com.ohav.noted.data.NotesRepository
import com.ohav.noted.reminders.Notifier
import com.ohav.noted.reminders.Reminders
import com.ohav.noted.settings.SettingsRepository
import com.ohav.noted.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class NotedApp : Application() {
    val scope = CoroutineScope(SupervisorJob())
    lateinit var notes: NotesRepository private set
    lateinit var settings: SettingsRepository private set
    lateinit var updater: Updater private set
    lateinit var reminders: Reminders private set

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        val db = NotedDatabase.create(this)
        notes = NotesRepository(db)
        reminders = Reminders(this, db)
        Notifier.ensureChannel(this)
        settings = SettingsRepository(this)
        updater = Updater(this)
        scope.launch { notes.seedIfEmpty() }
        scope.launch { updater.check(quiet = true) }
        // anything missed while we weren't running rings now; after that, every change re-arms the alarm
        scope.launch {
            reminders.fireDue()
            reminders.observeTick().debounce(250).collect { reminders.schedule() }
        }
    }
}
