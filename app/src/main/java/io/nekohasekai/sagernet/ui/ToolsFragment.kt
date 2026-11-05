package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.backup.BackupEntry

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.setTitle(R.string.menu_tools)

        // A recreated screen keeps its Backup page; a backup opened from outside (BackupEntry) opens its restore dialog.
        if (childFragmentManager.findFragmentById(R.id.tools_content) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.tools_content, BackupFragment.newInstance(BackupEntry.takePending()))
                .commitAllowingStateLoss()
        }
    }

}
