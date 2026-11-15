package com.autolog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: Trip): Long

    @Insert
    suspend fun insertAll(trips: List<Trip>)

    @Query("UPDATE trips SET purpose = :purpose WHERE id = :id")
    suspend fun setPurpose(id: Long, purpose: Purpose)

    @Query("UPDATE trips SET driverName = :name, driverDept = :dept WHERE id = :id")
    suspend fun setDriver(id: Long, name: String, dept: String)

    @Query("UPDATE trips SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String)

    /** 운행에 지운 표시를 붙인다. 행은 남는다. [reason]은 Trip.DELETED_BY_* 가운데 하나. */
    @Query("UPDATE trips SET deleted = 1, deletedReason = :reason WHERE id IN (:ids)")
    suspend fun markDeleted(ids: List<Long>, reason: String)

    @Query("SELECT * FROM trips WHERE deleted = 0 ORDER BY endAtMs DESC, id DESC")
    fun observeAll(): Flow<List<Trip>>

    /** 가장 최근에 끝난 운행. 없으면 null. */
    @Query("SELECT * FROM trips WHERE deleted = 0 ORDER BY endAtMs DESC, id DESC LIMIT 1")
    suspend fun latest(): Trip?

    /** 지운 표시가 있는 운행까지 모두. 합치기·나누기를 되돌릴 수 있는지 볼 때 쓴다. */
    @Query("SELECT * FROM trips ORDER BY id")
    fun observeEverything(): Flow<List<Trip>>

    /** 지운 표시를 걷어 운행을 되살린다. */
    @Query("UPDATE trips SET deleted = 0, deletedReason = '' WHERE id IN (:ids)")
    suspend fun restore(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun count(): Int

    /** 데이터 전체 삭제. 지운 표시가 있는 행까지 모두 없앤다. */
    @Query("DELETE FROM trips")
    suspend fun deleteAll()
}
