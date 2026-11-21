/*
 * Open TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 */
package io.timelimit.android.agent

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.timelimit.android.R
import io.timelimit.android.async.Threads

/**
 * The visual half of the hard lock.
 *
 * [HardLockGuard] refuses the edit, but the screen still looks editable: pressing a switch produces a
 * toast and nothing moves, which reads as a bug rather than as a rule. This puts a dim, non-interactive
 * veil over a hard category's screens so the controls cannot be touched at all and plainly look off.
 *
 * A veil rather than greying out each control on purpose. The screens are built from preferences,
 * recycled list rows and dialogs that are all created later, so disabling widget by widget would leave
 * whatever is added next live -- and the one control somebody forgot is the only one that matters. One
 * view above everything cannot be forgotten. It sits inside the activity content so the toolbar and
 * back navigation still work: the user can leave, they just cannot edit.
 *
 * The veil removes itself with the view's lifecycle, because the host activity is shared between
 * screens and a leaked overlay would freeze unrelated pages.
 */
object HardLockUi {
    private const val VEIL_TAG = "hard_lock_veil"

    /**
     * Covers the screen if [categoryId] is a hard category. Safe to call unconditionally from a
     * fragment's `onCreateView`: it does nothing when the guard is off or the category is soft.
     */
    fun veilIfNeeded(fragment: Fragment, categoryId: String, root: View) {
        val context = fragment.context ?: return
        if (!HardLockGuard.isEnabled(context)) return

        Threads.database.submit {
            val title = HardLockGuard.hardCategoryTitle(context, categoryId) ?: return@submit

            Threads.mainThreadHandler.post {
                val activity = fragment.activity ?: return@post
                if (!fragment.isAdded) return@post

                attach(activity, title, root)

                fragment.viewLifecycleOwner.lifecycle.addObserver(object : LifecycleEventObserver {
                    override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                        if (event == Lifecycle.Event.ON_DESTROY) {
                            source.lifecycle.removeObserver(this)
                            clear(activity)
                        }
                    }
                })
            }
        }
    }

    /** Removes a veil this screen left behind, if any. */
    fun clear(activity: androidx.fragment.app.FragmentActivity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<View>(VEIL_TAG)?.let { content.removeView(it) }
    }

    private fun attach(activity: androidx.fragment.app.FragmentActivity, title: String, root: View) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(VEIL_TAG) != null) return

        val hint = activity.getString(R.string.hard_lock_tap_hint, title)

        val veil = FrameLayout(activity).apply {
            tag = VEIL_TAG
            // Dim rather than opaque: the point is "you are looking at real, locked settings", not to
            // hide what the category contains.
            setBackgroundColor(0x8A000000.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Toast.makeText(activity, hint, Toast.LENGTH_SHORT).show()
            }
        }

        val label = TextView(activity).apply {
            text = activity.getString(R.string.hard_lock_screen_locked, title)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            val padH = (20 * activity.resources.displayMetrics.density).toInt()
            val padV = (14 * activity.resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 20 * activity.resources.displayMetrics.density
                setColor(0xF0202020.toInt())
                setStroke((1 * activity.resources.displayMetrics.density).toInt(), 0x50FFFFFF)
            }
        }

        val margin = (24 * activity.resources.displayMetrics.density).toInt()
        veil.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                setMargins(margin, margin, margin, margin)
            }
        )

        content.addView(
            veil,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }
}
