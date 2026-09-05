package it.vito.home

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HomeItaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HomeItaProvider())
    }
}
