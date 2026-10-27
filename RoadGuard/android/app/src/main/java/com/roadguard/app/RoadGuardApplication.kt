package com.roadguard.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * RoadGuardApplication — Application entry point.
 * @HiltAndroidApp triggers Hilt's code generation for dependency injection.
 */
@HiltAndroidApp
class RoadGuardApplication : Application()
