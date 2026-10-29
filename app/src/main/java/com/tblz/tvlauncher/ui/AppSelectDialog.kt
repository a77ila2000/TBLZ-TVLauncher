package com.tblz.tvlauncher.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import com.tblz.tvlauncher.databinding.DialogAppSelectBinding
import com.tblz.tvlauncher.engine.ConfigStore

class AppSelectDialog(
    context: Context,
    private val onAppSelected: () -> Unit
) : Dialog(context) {

    private lateinit var binding: DialogAppSelectBinding
    private lateinit var adapter: AppSelectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = DialogAppSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.85).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.85).toInt()
        )

        adapter = AppSelectAdapter { count ->
            binding.btnConfirm.text = "확인 (${count}개 선택)"
        }

        binding.rvAllApps.layoutManager = GridLayoutManager(context, 4)
        binding.rvAllApps.adapter = adapter

        val apps = loadInstalledApps()
        val currentAdded = ConfigStore.getUserApps().toSet()
        adapter.submitList(apps, currentAdded)

        binding.btnCancel.setOnClickListener { dismiss() }

        binding.btnConfirm.setOnClickListener {
            val selected = adapter.selectedPackages
            var addedCount = 0
            for (pkg in selected) {
                if (ConfigStore.addUserApp(pkg)) {
                    addedCount++
                }
            }

            if (selected.isNotEmpty()) {
                Toast.makeText(context, "${selected.size}개 앱이 홈 화면에 적용되었습니다.", Toast.LENGTH_SHORT).show()
            }
            onAppSelected()
            dismiss()
        }
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
