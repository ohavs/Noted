package com.ohav.noted

import android.app.Application
import com.ohav.noted.data.NotedDatabase
import com.ohav.noted.data.NotesRepository
import com.ohav.noted.places.Places
import com.ohav.noted.reminders.Notifier
import com.ohav.noted.reminders.Reminders
import com.ohav.noted.settings.SettingsRepository
import com.ohav.noted.sync.Account
import com.ohav.noted.sync.Sync
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
    lateinit var places: Places private set
    lateinit var account: Account private set
    lateinit var sync: Sync private set

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        val db = NotedDatabase.create(this)
        notes = NotesRepository(db)
        reminders = Reminders(this, db)
        places = Places(this, db)
        Notifier.ensureChannel(this)
        settings = SettingsRepository(this)
        updater = Updater(this)
        account = Account(this, scope)
        sync = Sync(this, db, account, scope).also { it.start() }
        scope.launch { notes.seedIfEmpty() }
        scope.launch { updater.check(quiet = true) }
        // anything missed while we weren't running rings now; after that, every change re-arms the alarm
        scope.launch {
            reminders.fireDue()
            reminders.observeTick().debounce(250).collect { reminders.schedule() }
        }
        // fences follow every change to places / place reminders / notes (throttled inside)
        scope.launch { places.observeTick().debounce(400).collect { places.sync() } }
    }
}
