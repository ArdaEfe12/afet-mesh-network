package org.afet.mesh

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt DI kök noktası.
 * AndroidManifest.xml'deki android:name=".AfetMeshApp" ile bağlıdır.
 */
@HiltAndroidApp
class AfetMeshApp : Application()
