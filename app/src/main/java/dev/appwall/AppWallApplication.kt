package dev.appwall

import android.app.Application
import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Builds AppWall's shared objects once per process and hands them out: screens, the service and
 * receivers ask for them here instead of reaching for globals. Kept light, since it runs on every
 * process start, boot included: nothing here touches the disk until first used.
 */
class AppWallApplication : Application() {

    /** The blocked traffic log. Opening the database waits for the first query. */
    lateinit var log: BlockLog
        private set

    /** Installed apps, loaded off the UI thread and remembered between screens. */
    lateinit var apps: InstalledAppsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        log = BlockLog(this)
        apps = InstalledAppsRepository(this, namedExecutor("appwall-apps"))
    }

    private fun namedExecutor(name: String): ExecutorService = Executors.newSingleThreadExecutor { Thread(it, name) }
}

/** The process's [AppWallApplication], from any context. */
val Context.appWall: AppWallApplication
    get() = applicationContext as AppWallApplication
