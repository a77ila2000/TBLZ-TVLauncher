package com.tblz.tvlauncher.ui

import android.graphics.drawable.Drawable

data class AppItem(
    val id: String,
    val title: String,
    val icon: Drawable? = null,
    val iconRes: Int? = null,
    val isAction: Boolean = false
)
