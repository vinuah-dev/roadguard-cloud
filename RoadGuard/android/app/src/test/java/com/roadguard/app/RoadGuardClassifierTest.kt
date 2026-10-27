package com.roadguard.app

import com.roadguard.app.domain.model.RoadEventType
import com.roadguard.app.sensor.RoadGuardClassifier
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RoadGuardClassifierTest {

    private lateinit var classifier: RoadGuardClassifier

    @Before
    fun setUp() {
        classifier = RoadGuardClassifier()
    }

    @Test
    fun testClassifyNormalRoad() {
        // Flat road: small variations around 9.8 m/s² (1G)
        val normalSamples = List(50) { 9.8f + (it % 3 - 1) * 0.1f }
        val result = classifier.classifyWindow(normalSamples)

        assertEquals(RoadEventType.NORMAL, result.eventType)
        assertTrue("Confidence should be high for normal road", result.confidence >= 0.70f)
    }

    @Test
    fun testClassifyPothole() {
        // Pothole signature: sharp negative dip followed by strong rebound spike
        val potholeSamples = MutableList(50) { 9.8f }
        potholeSamples[20] = 1.2f  // sudden drop (free-fall into hole)
        potholeSamples[21] = 0.5f
        potholeSamples[23] = 24.5f // severe impact hit at bottom of hole
        potholeSamples[24] = 16.0f

        val result = classifier.classifyWindow(potholeSamples)

        assertEquals(RoadEventType.POTHOLE, result.eventType)
        assertTrue("Confidence should be significant", result.confidence >= 0.60f)
    }

    @Test
    fun testClassifySpeedBreaker() {
        // Speed breaker signature: upward heave followed by gradual dip
        val speedBreakerSamples = MutableList(50) { 9.8f }
        speedBreakerSamples[18] = 13.8f // upward slope
        speedBreakerSamples[19] = 14.5f // peak
        speedBreakerSamples[20] = 13.9f
        speedBreakerSamples[22] = 7.5f  // descent dip

        val result = classifier.classifyWindow(speedBreakerSamples)

        assertEquals(RoadEventType.SPEED_BREAKER, result.eventType)
        assertTrue("Confidence should be above threshold", result.confidence >= 0.60f)
    }

    @Test
    fun testEmptyOrInsufficientSamplesDefaultsToNormal() {
        val emptySamples = listOf(9.8f, 9.8f)
        val result = classifier.classifyWindow(emptySamples)
        assertEquals(RoadEventType.NORMAL, result.eventType)
    }
}
