package com.jakedonnini.sattracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One CCSDS Orbit Mean-Elements Message (OMM) as served by CelesTrak's GP API in JSON.
 *
 * Units follow CelesTrak/TLE conventions: angles in degrees, mean motion in rev/day,
 * MEAN_MOTION_DOT = ṅ/2 (rev/day²), MEAN_MOTION_DDOT = n̈/6 (rev/day³), EPOCH in UTC.
 * Unlike the TLE text format, NORAD_CAT_ID is not limited to 5 digits.
 */
@Serializable
data class OmmRecord(
    @SerialName("OBJECT_NAME") val objectName: String,
    @SerialName("OBJECT_ID") val objectId: String = "",
    @SerialName("EPOCH") val epoch: String,
    @SerialName("MEAN_MOTION") val meanMotion: Double,
    @SerialName("ECCENTRICITY") val eccentricity: Double,
    @SerialName("INCLINATION") val inclination: Double,
    @SerialName("RA_OF_ASC_NODE") val raOfAscNode: Double,
    @SerialName("ARG_OF_PERICENTER") val argOfPericenter: Double,
    @SerialName("MEAN_ANOMALY") val meanAnomaly: Double,
    @SerialName("EPHEMERIS_TYPE") val ephemerisType: Int = 0,
    @SerialName("CLASSIFICATION_TYPE") val classificationType: String = "U",
    @SerialName("NORAD_CAT_ID") val noradCatId: Int,
    @SerialName("ELEMENT_SET_NO") val elementSetNo: Int = 999,
    @SerialName("REV_AT_EPOCH") val revAtEpoch: Int = 0,
    @SerialName("BSTAR") val bstar: Double,
    @SerialName("MEAN_MOTION_DOT") val meanMotionDot: Double,
    @SerialName("MEAN_MOTION_DDOT") val meanMotionDdot: Double = 0.0,
)
