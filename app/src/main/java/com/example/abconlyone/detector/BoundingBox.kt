package com.example.abconlyone.detector
/** *Representaunadeteccion:coordenadasenpixelesdelframeanalizado,
 *  *laclase detectadayelscorede confianza. */
data class BoundingBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val score: Float,
    val classId: Int,
    val label: String
)