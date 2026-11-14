package com.example

import android.app.Application
import com.example.repository.MediaNotificationRepository

class BitChordApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MediaNotificationRepository.initialize(this)
    }
}
