package org.kirundi.voicestudio.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Projet de collecte (ex. AI0117). */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val notes: String = "",
    val createdAt: Long
)

/** Participant (nom ou identifiant) d'un projet. */
@Entity(tableName = "participants")
data class ParticipantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val code: String,
    val notes: String = "",
    val createdAt: Long
)

/** Session d'enregistrement d'un participant. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val participantId: Long,
    val startedAt: Long,
    val notes: String = ""
)

/** Un fichier WAV de l'application et ses informations techniques. */
@Entity(tableName = "recordings", indices = [Index(value = ["fileName"], unique = true)])
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val sessionId: Long?,
    val createdAt: Long,
    val durationSec: Double,
    val sampleRate: Int,
    val bits: Int,
    val nativeDepth: String,
    val source: String,
    val device: String,
    val rmsDb: Double?,
    val peakDb: Double?,
    val clipEvents: Int,
    val sha256: String?,
    val notes: String = ""
)

/** Historique des mesures et contrôles (analyses et pré-tests). */
@Entity(tableName = "measures")
data class MeasureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val createdAt: Long,
    val kind: String,
    val method: String,
    val noiseDb: Double?,
    val snrDb: Double?,
    val rmsDb: Double?,
    val peakDb: Double?,
    val status: String,
    val summary: String
)

/** Journal des exportations. */
@Entity(tableName = "exports")
data class ExportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val exportedAt: Long,
    val format: String,
    val sha256: String?,
    val verified: Boolean,
    val message: String
)
