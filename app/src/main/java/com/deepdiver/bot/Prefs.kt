package com.deepdiver.bot

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("diverbot", Context.MODE_PRIVATE)
    fun level(c: Context): Int = sp(c).getInt("level", 2)
    fun setLevel(c: Context, v: Int) { sp(c).edit().putInt("level", v).apply() }
}
2
