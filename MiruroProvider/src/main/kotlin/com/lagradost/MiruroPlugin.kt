package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MiruroPlugin : Plugin() {
    override fun load(context: Context) {
        PipeBridge.init(context)
        registerMainAPI(MiruroProvider())
    }

    override fun beforeUnload() {
        PipeBridge.destroy()
    }
}
