package com.mockgeo.core.data.db

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ---- 实体（docs/08 §8 的表结构）----

@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val altitude: Double?,
    val accuracy: Double?,
    val createdAt: Long,
    val groupName: String?,
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val source: String,
    val createdAt: Long,
    val extra: String?,
)

@Entity(tableName = "route")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val distanceM: Double,
    val durationS: Double?,
    val mode: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** User-selected waypoints; null marks legacy routes or imported track geometry. */
    val controlPointsJson: String? = null,
    @ColumnInfo(defaultValue = "'drive'")
    val profile: String = "drive",
)

@Entity(tableName = "route_point", primaryKeys = ["routeId", "seq"])
data class RoutePointEntity(
    val routeId: Long,
    val seq: Int,
    val lat: Double,
    val lng: Double,
    val altitude: Double?,
    val speedMps: Double?,
)

data class RouteWithPoints(
    @Embedded val route: RouteEntity,
    @Relation(parentColumn = "id", entityColumn = "routeId")
    val points: List<RoutePointEntity>,
)

// ---- DAO ----

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoriteEntity): Long

    @Delete
    suspend fun delete(entity: FavoriteEntity)

    @Query("SELECT * FROM favorite ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorite WHERE id = :id")
    suspend fun byId(id: Long): FavoriteEntity?

    @Query("UPDATE favorite SET name = :name, lat = :lat, lng = :lng, altitude = :altitude WHERE id = :id")
    suspend fun updateDetails(id: Long, name: String, lat: Double, lng: Double, altitude: Double?): Int
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(entity: HistoryEntity): Long

    @Query("SELECT * FROM history ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEntity>>

    /** 超限裁剪：保留最新 limit 条（docs/08 §8：在 Repository 层做，不用触发器）。 */
    @Query(
        "DELETE FROM history WHERE id NOT IN " +
            "(SELECT id FROM history ORDER BY createdAt DESC, id DESC LIMIT :limit)",
    )
    suspend fun trimTo(limit: Int)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(entity: RouteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<RoutePointEntity>)

    @Transaction
    @Query("SELECT * FROM route ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<RouteWithPoints>>

    @Transaction
    @Query("SELECT * FROM route WHERE id = :id")
    suspend fun byId(id: Long): RouteWithPoints?

    @Query("DELETE FROM route WHERE id = :id")
    suspend fun deleteRoute(id: Long)

    @Query("DELETE FROM route_point WHERE routeId = :routeId")
    suspend fun deletePoints(routeId: Long)

    @Query("UPDATE route SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, name: String, updatedAt: Long): Int
}

@Database(
    entities = [
        FavoriteEntity::class,
        HistoryEntity::class,
        RouteEntity::class,
        RoutePointEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun routeDao(): RouteDao

    companion object {
        /** Add nullable metadata in place; all existing routes, points and user data remain intact. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE route ADD COLUMN controlPointsJson TEXT")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE route ADD COLUMN profile TEXT NOT NULL DEFAULT 'drive'")
            }
        }
    }
}
