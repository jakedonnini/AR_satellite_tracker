package com.jakedonnini.sattracker.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jakedonnini.sattracker.data.remote.OmmRecord

/** Cached mean elements for one satellite, stored column-per-field so SQL can filter orbits. */
@Entity(
    tableName = "satellite",
    indices = [Index("meanMotion")],
)
data class SatelliteEntity(
    @PrimaryKey val noradId: Int,
    val name: String,
    val objectId: String,
    val epoch: String,
    val meanMotion: Double,
    val eccentricity: Double,
    val inclination: Double,
    val raOfAscNode: Double,
    val argOfPericenter: Double,
    val meanAnomaly: Double,
    val ephemerisType: Int,
    val classificationType: String,
    val elementSetNo: Int,
    val revAtEpoch: Int,
    val bstar: Double,
    val meanMotionDot: Double,
    val meanMotionDdot: Double,
)

/** When each CelesTrak group was last downloaded, to honor the once-per-update policy. */
@Entity(tableName = "catalog_fetch")
data class CatalogFetchEntity(
    @PrimaryKey val group: String,
    val fetchedAtMillis: Long,
)

fun OmmRecord.toEntity() = SatelliteEntity(
    noradId = noradCatId,
    name = objectName.trim(),
    objectId = objectId,
    epoch = epoch,
    meanMotion = meanMotion,
    eccentricity = eccentricity,
    inclination = inclination,
    raOfAscNode = raOfAscNode,
    argOfPericenter = argOfPericenter,
    meanAnomaly = meanAnomaly,
    ephemerisType = ephemerisType,
    classificationType = classificationType,
    elementSetNo = elementSetNo,
    revAtEpoch = revAtEpoch,
    bstar = bstar,
    meanMotionDot = meanMotionDot,
    meanMotionDdot = meanMotionDdot,
)

fun SatelliteEntity.toOmm() = OmmRecord(
    objectName = name,
    objectId = objectId,
    epoch = epoch,
    meanMotion = meanMotion,
    eccentricity = eccentricity,
    inclination = inclination,
    raOfAscNode = raOfAscNode,
    argOfPericenter = argOfPericenter,
    meanAnomaly = meanAnomaly,
    ephemerisType = ephemerisType,
    classificationType = classificationType,
    noradCatId = noradId,
    elementSetNo = elementSetNo,
    revAtEpoch = revAtEpoch,
    bstar = bstar,
    meanMotionDot = meanMotionDot,
    meanMotionDdot = meanMotionDdot,
)
