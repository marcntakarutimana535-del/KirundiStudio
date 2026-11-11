package org.kirundi.voicestudio.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AppDao {

    @Insert
    fun insertProject(p: ProjectEntity): Long

    @Delete
    fun deleteProject(p: ProjectEntity)

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun projects(): List<ProjectEntity>

    @Insert
    fun insertParticipant(p: ParticipantEntity): Long

    @Delete
    fun deleteParticipant(p: ParticipantEntity)

    @Query("SELECT * FROM participants ORDER BY createdAt DESC")
    fun participants(): List<ParticipantEntity>

    @Insert
    fun insertSession(s: SessionEntity): Long

    @Delete
    fun deleteSession(s: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun sessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    fun session(id: Long): SessionEntity?

    @Query("SELECT * FROM participants WHERE id = :id LIMIT 1")
    fun participant(id: Long): ParticipantEntity?

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    fun project(id: Long): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertRecording(r: RecordingEntity): Long

    @Update
    fun updateRecording(r: RecordingEntity)

    @Delete
    fun deleteRecording(r: RecordingEntity)

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun recordings(): List<RecordingEntity>

    @Insert
    fun insertMeasure(m: MeasureEntity): Long

    @Query("SELECT * FROM measures ORDER BY createdAt DESC")
    fun measures(): List<MeasureEntity>

    @Insert
    fun insertExport(e: ExportEntity): Long

    @Query("SELECT * FROM exports ORDER BY exportedAt DESC")
    fun exports(): List<ExportEntity>
}
