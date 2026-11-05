package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.types.Mieru

class MieruSettingsActivity : BindingSettingsActivity<Mieru>() {

    override fun createEntity() = Mieru()
    override val preferencesResource = R.xml.mieru_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("server_ports")
        pbm.text("transport")
        pbm.text("username")
        pbm.text("password")
        pbm.text("multiplexing")
        pbm.text("traffic_pattern")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
    }

}
