package com.mockgeo.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import androidx.room.Room
import com.mockgeo.core.data.db.AppDatabase
import com.mockgeo.core.data.repo.LocationRepository
import com.mockgeo.core.model.HistorySource
import com.mockgeo.core.model.LatLng
import com.mockgeo.core.model.RouteMode
import com.mockgeo.core.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

/**
 * Android SQLite/Room integration checks. Only a newly created, randomly named
 * database is used; this helper never opens the application's ordinary database.
 * The instrumentation entry point can delegate `-e mode data` to [run].
 */
class DataSmokeChecks(private val host: Instrumentation) {
    private var passed = 0
    private var step = "create-v1-fixture"
    private val results = Bundle()

    fun run() {
        val context = host.targetContext
        val name = "mockgeo-data-smoke-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        var ownsDatabase = false
        var database: AppDatabase? = null
        var success = false

        runBlocking(Dispatchers.IO) {
            try {
                check(name.startsWith("mockgeo-data-smoke-") && name.endsWith(".db"))
                file.parentFile?.mkdirs()
                // Atomic reservation avoids reusing even an unexpectedly existing test name.
                check(file.createNewFile()) { "Temporary database name already exists" }
                ownsDatabase = true
                createVersionOne(context, name)
                pass("create-v1-fixture", "independent SQLite v1 fixture created")

                step = "room-migration-1-2-3"
                val opened = openRoom(context, name)
                database = opened
                check(opened.openHelper.writableDatabase.version == 3) { "Room did not migrate to schema v3" }
                val repository = LocationRepository(opened)
                val legacy = repository.routeById(OLD_ROUTE_ID) ?: error("Legacy route was lost")
                check(legacy.name == "legacy route" && legacy.createdAt == 100L && legacy.updatedAt == 200L)
                check(legacy.mode == RouteMode.PING_PONG && legacy.distanceM == 123.5 && legacy.durationS == null)
                check(legacy.profile == "drive") { "Legacy route did not receive default drive profile" }
                check(legacy.controlPoints.isEmpty()) { "Legacy track was incorrectly assigned control points" }
                check(legacy.points.map { it.seq } == listOf(0, 1))
                check(legacy.points.map { LatLng(it.lat, it.lng) } == listOf(LatLng(30.0, 120.0), LatLng(30.1, 120.1)))
                check(legacy.points.map { it.altitude } == listOf(10.0, 20.0))
                check(legacy.points.map { it.speedMps } == listOf(null, 1.5))
                check(legacy.points.all { it.routeId == OLD_ROUTE_ID })
                val oldFavorite = opened.favoriteDao().byId(OLD_FAVORITE_ID) ?: error("Legacy favorite was lost")
                check(oldFavorite.name == "legacy favorite" && oldFavorite.createdAt == 300L)
                check(oldFavorite.lat == 30.0 && oldFavorite.lng == 120.0)
                check(oldFavorite.groupName == "work" && oldFavorite.accuracy == 3.5 && oldFavorite.altitude == 25.0)
                val oldHistory = repository.observeHistory(100).first().single()
                check(oldHistory.id == OLD_HISTORY_ID && oldHistory.source == HistorySource.MANUAL &&
                    oldHistory.lat == 30.0 && oldHistory.lng == 120.0 &&
                    oldHistory.createdAt == 400L && oldHistory.extra == "legacy note")
                pass("room-migration-1-2-3", "Room validated schema; all four tables retained legacy values")

                step = "route-profile-and-geometry"
                val controls = listOf(LatLng(39.90, 116.39), LatLng(39.92, 116.41))
                val geometry = listOf(
                    RoutePoint(seq = 2, lat = 39.90, lng = 116.39, altitude = 30.0),
                    RoutePoint(seq = 5, lat = 39.91, lng = 116.405, altitude = 40.0, speedMps = 1.3),
                    RoutePoint(seq = 9, lat = 39.92, lng = 116.41, altitude = 50.0, speedMps = 1.1),
                )
                val savedId = repository.saveDetailedRoute("walking route", geometry, RouteMode.ONCE, controls, "walk")
                check(savedId != OLD_ROUTE_ID)

                // Close and reopen so this check proves persisted values, not a ViewModel/cache copy.
                opened.close()
                database = null
                val reopened = openRoom(context, name)
                database = reopened
                check(reopened.openHelper.writableDatabase.version == 3)
                val reloadedRepository = LocationRepository(reopened)
                val saved = reloadedRepository.routeById(savedId) ?: error("New route was not persisted")
                check(saved.name == "walking route" && saved.profile == "walk" && saved.mode == RouteMode.ONCE)
                check(saved.controlPoints == controls) { "Control points were changed or lost" }
                check(saved.points == geometry.mapIndexed { i, point -> point.copy(routeId = savedId, seq = i) }) {
                    "Geometry, elevation, speed, order or route identity was changed"
                }
                check(saved.points.size == 3 && saved.controlPoints.size == 2)
                check(reloadedRepository.routeById(OLD_ROUTE_ID) == legacy) { "Saving a new route altered the old one" }
                pass("route-profile-and-geometry", "walk profile, two controls and three detailed geometry points survived reopen")

                step = "favorite-edit-preservation"
                val editedPosition = LatLng(31.2304, 121.4737)
                reloadedRepository.updateFavorite(OLD_FAVORITE_ID, "  renamed favorite  ", editedPosition, 88.0)
                val edited = reopened.favoriteDao().byId(OLD_FAVORITE_ID) ?: error("Edited favorite was lost")
                check(edited == oldFavorite.copy(name = "renamed favorite", lat = editedPosition.lat,
                    lng = editedPosition.lng, altitude = 88.0)) {
                    "Favorite editing changed creation time, grouping, accuracy or another unrelated field"
                }
                pass("favorite-edit-preservation", "editable columns changed; createdAt, groupName and accuracy retained")

                step = "invalid-edits-are-atomic"
                val invalidName = runCatching {
                    reloadedRepository.updateFavorite(OLD_FAVORITE_ID, "  ", editedPosition, 10.0)
                }
                check(invalidName.exceptionOrNull() is IllegalArgumentException) { "Blank name was accepted" }
                val invalidAltitude = runCatching {
                    reloadedRepository.updateFavorite(OLD_FAVORITE_ID, "should not persist", editedPosition, Double.NaN)
                }
                check(invalidAltitude.exceptionOrNull() is IllegalArgumentException) { "Non-finite altitude was accepted" }
                check(reopened.favoriteDao().byId(OLD_FAVORITE_ID) == edited) { "Rejected favorite edit changed stored data" }
                val invalidProfile = runCatching {
                    reloadedRepository.saveDetailedRoute("invalid profile", geometry, RouteMode.ONCE, controls, "unknown")
                }
                check(invalidProfile.exceptionOrNull() is IllegalArgumentException) { "Unknown route profile was accepted" }
                check(reloadedRepository.observeRoutes().first().size == 2) { "Rejected route left partial data" }
                pass("invalid-edits-are-atomic", "invalid name, altitude and profile rejected without changing data")

                step = "history-delete-isolated"
                reloadedRepository.addHistory(LatLng(32.0, 122.0), HistorySource.SEARCH, "new history")
                val beforeDelete = reloadedRepository.observeHistory(100).first()
                check(beforeDelete.size == 2)
                val survivor = beforeDelete.single { it.id != OLD_HISTORY_ID }
                reloadedRepository.deleteHistory(OLD_HISTORY_ID)
                check(reloadedRepository.observeHistory(100).first() == listOf(survivor)) {
                    "Single history deletion removed or changed another record"
                }
                check(reloadedRepository.observeRoutes().first().size == 2)
                check(reloadedRepository.routeById(OLD_ROUTE_ID) == legacy)
                check(reopened.favoriteDao().byId(OLD_FAVORITE_ID) == edited)
                pass("history-delete-isolated", "only the selected history row removed; routes and favorite unchanged")
                success = true
            } catch (error: Throwable) {
                success = false
                results.putString("failureStep", step)
                results.putString("failure", "${error.javaClass.simpleName}: ${error.message}")
                emit("FAIL", "$step: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                var cleanupFailure: Throwable? = null
                try { database?.close() } catch (error: Throwable) { cleanupFailure = error }
                if (ownsDatabase) {
                    try {
                        check(context.deleteDatabase(name)) { "Temporary database could not be deleted" }
                        check(!file.exists() && listOf("-journal", "-wal", "-shm").none {
                            File(file.path + it).exists()
                        }) { "Temporary database sidecars remain after cleanup" }
                    } catch (error: Throwable) {
                        if (cleanupFailure == null) cleanupFailure = error
                    }
                }
                if (cleanupFailure != null) {
                    success = false
                    results.putString("cleanupFailure", "${cleanupFailure.javaClass.simpleName}: ${cleanupFailure.message}")
                    emit("FAIL", "temporary-db-cleanup: ${cleanupFailure.javaClass.simpleName}: ${cleanupFailure.message}")
                } else if (ownsDatabase) pass("temporary-db-cleanup", "temporary database and sidecars removed")
            }
        }
        results.putString("mode", "data")
        results.putInt("passedSteps", passed)
        results.putString("stream", if (success) "PASS: $passed Android data smoke checks\n"
            else "FAIL: Android data smoke checks; see failureStep/failure/cleanupFailure\n")
        host.finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, results)
    }

    private fun openRoom(context: Context, name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()

    private fun createVersionOne(context: Context, name: String) {
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            check(db.version == 0) { "Fixture is not a new database" }
            db.beginTransaction()
            try {
                // Exact v1 entity schema, including nullability, column names and the composite primary key.
                db.execSQL("CREATE TABLE `favorite` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `altitude` REAL, `accuracy` REAL, `createdAt` INTEGER NOT NULL, `groupName` TEXT)")
                db.execSQL("CREATE TABLE `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `extra` TEXT)")
                db.execSQL("CREATE TABLE `route` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `distanceM` REAL NOT NULL, `durationS` REAL, `mode` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE `route_point` (`routeId` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `altitude` REAL, `speedMps` REAL, PRIMARY KEY(`routeId`, `seq`))")
                db.execSQL("INSERT INTO route VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(OLD_ROUTE_ID, "legacy route", 123.5, null, "PING_PONG", 100L, 200L))
                db.execSQL("INSERT INTO route_point VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(OLD_ROUTE_ID, 0, 30.0, 120.0, 10.0, null))
                db.execSQL("INSERT INTO route_point VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(OLD_ROUTE_ID, 1, 30.1, 120.1, 20.0, 1.5))
                db.execSQL("INSERT INTO favorite VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(OLD_FAVORITE_ID, "legacy favorite", 30.0, 120.0, 25.0, 3.5, 300L, "work"))
                db.execSQL("INSERT INTO history VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(OLD_HISTORY_ID, 30.0, 120.0, "MANUAL", 400L, "legacy note"))
                db.version = 1
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    private fun pass(name: String, detail: String) {
        step = name
        passed++
        emit("PASS", "$name: $detail")
    }

    private fun emit(kind: String, message: String) {
        host.sendStatus(0, Bundle().apply {
            putString("step", step)
            putString("kind", kind)
            putString("stream", "$kind $message\n")
        })
    }

    companion object {
        private const val OLD_ROUTE_ID = 11L
        private const val OLD_FAVORITE_ID = 21L
        private const val OLD_HISTORY_ID = 31L
    }
}
