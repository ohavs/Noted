package com.ohav.noted

import android.app.Application
import com.ohav.noted.data.NotedDatabase
import com.ohav.noted.data.NotesRepository
import com.ohav.noted.places.Places
import com.ohav.noted.reminders.Notifier
import com.ohav.noted.reminders.Reminders
import com.ohav.noted.settings.SettingsRepository
import com.ohav.noted.sync.Account
import com.ohav.noted.sync.Sharing
import com.ohav.noted.sync.Sync
import com.ohav.noted.update.Updater
import com.ohav.noted.widget.NotedWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
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
    lateinit var sharing: Sharing private set

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
        sharing = Sharing(account, sync, notes)
        scope.launch { notes.seedIfEmpty() }
        // a new note left empty when the app went away with it open: gone, like closing it would have
        val launched = System.currentTimeMillis()
        scope.launch {
            notes.leftovers(before = launched).forEach { id ->
                if (reminders.get(id) == null && places.reminder(id) == null) sync.discard(id)
            }
        }
        scope.launch { updater.check(quiet = true) }
        // anything missed while we weren't running rings now; after that, every change re-arms the alarm
        scope.launch {
            reminders.fireDue()
            reminders.observeTick().debounce(250).collect { reminders.schedule() }
        }
        // fences follow every change to places / place reminders / notes (throttled inside)
        scope.launch { places.observeTick().debounce(400).collect { places.sync() } }
        // home-screen widgets follow notes, categories, reminders and the look (a no-op without widgets)
        scope.launch {
            combine(notes.notes, notes.categories, settings.settings, reminders.all, places.reminders) { _, _, _, _, _ -> }.drop(1).debounce(500)
                .collect { NotedWidget.refresh(this@NotedApp) }
        }
    }
}
