package com.tblz.tvlauncher.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.Toast
import android.view.Window
import androidx.recyclerview.widget.GridLayoutManager
import com.tblz.tvlauncher.databinding.DialogAllAppsBinding

class AllAppsDialog(context: Context) : Dialog(context) {

    private lateinit var binding: DialogAllAppsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogAllAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.85).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.85).toInt()
        )

        val adapter = AppLauncherAdapter(
            onAppClick = { app ->
                // Same leanback-vs-launcher mismatch as the home shortcuts: this list is
                // built from CATEGORY_LEANBACK_LAUNCHER, so it must launch that way too.
                val launchIntent = context.packageManager.getLeanbackLaunchIntentForPackage(app.id)
                    ?: context.packageManager.getLaunchIntentForPackage(app.id)
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    dismiss()
                } else {
                    Toast.makeText(context, "${app.title} 앱을 실행할 수 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        )

        binding.rvAllApps.layoutManager = GridLayoutManager(context, 4)
        binding.rvAllApps.adapter = adapter

        val apps = loadInstalledApps()
        adapter.submitList(apps)

        binding.btnClose.setOnClickListener { dismiss() }
    }

    private fun loadInstalledApps(): List<AppItem> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        var list = pm.queryIntentActivities(mainIntent, 0)
        if (list.isEmpty()) {
            val normalIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            list = pm.queryIntentActivities(normalIntent, 0)
        }

        return list.mapNotNull { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) return@mapNotNull null
            val label = ri.loadLabel(pm).toString()
            val icon = ri.loadIcon(pm)
            AppItem(id = pkg, title = label, icon = icon)
        }.sortedBy { it.title }
    }
}
