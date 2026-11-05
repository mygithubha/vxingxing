package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.ktx.triggerFullRestart

/**
 * The settings root: one entry per sub-screen (io.nekohasekai.sagernet.ui.settings, opened by [SettingsFragment]),
 * the backup screen and the reset action.
 */
class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        addPreferencesFromResource(R.xml.global_preferences)

        findPreference<Preference>(KEY_BACKUP)!!.setOnPreferenceClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_tools)
            true
        }

        findPreference<Preference>(KEY_RESET_SETTINGS)!!.setOnPreferenceClickListener {
            requireContext().confirmAction(
                getString(R.string.confirm_reset_settings),
                getString(R.string.reset_settings_message),
                R.string.confirm_restore,
            ) {
                DataStore.configurationStore.reset()
                triggerFullRestart(requireContext())
            }
            true
        }
    }

    private companion object {
        const val KEY_BACKUP = "settingsBackup"
        const val KEY_RESET_SETTINGS = "resetSettings"
    }
}
