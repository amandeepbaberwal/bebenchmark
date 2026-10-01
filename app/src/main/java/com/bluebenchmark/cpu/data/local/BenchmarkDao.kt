package com.bluebenchmark.cpu.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BenchmarkDao {
    @Insert
    suspend fun insert(record: BenchmarkRecord): Long

    @Query("SELECT * FROM benchmarks ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<BenchmarkRecord>>

    @Query("SELECT * FROM benchmarks ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<BenchmarkRecord>

    @Query("UPDATE benchmarks SET synced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("DELETE FROM benchmarks")
    suspend fun deleteAll()
}
