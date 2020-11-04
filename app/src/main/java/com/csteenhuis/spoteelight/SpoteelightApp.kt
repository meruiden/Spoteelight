package com.csteenhuis.spoteelight

import android.app.Application
import com.bugsnag.android.Bugsnag

class SpoteelightApp : Application() {
    override fun onCreate() {
        super.onCreate()
        //Bugsnag.start(this)
    }
}