/*
 * Open TimeLimit Copyright <C> 2019 - 2024 Jonas Lochmann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.timelimit.android.ui.pause

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import io.timelimit.android.agent.Interruptions
import io.timelimit.android.agent.PausePoint
import io.timelimit.android.async.Threads
import io.timelimit.android.data.model.CategoryPauseConfig
import io.timelimit.android.databinding.ActivityPauseBinding
import io.timelimit.android.logic.DefaultAppLogic

/**
 * The pause itself.
 *
 * Two acts. First the wait: a ring that empties, the check-in content, and no button. Then the
 * button appears, and pressing it is the only thing that lets the app through.
 *
 * Leaving does not help. Back and the Close button both go home without marking anything, and the
 * gate stays armed in [PausePoint], so opening the app again lands here with the countdown already
 * part-way down. That asymmetry — the button is a decision, leaving is not — is the whole feature;
 * without it this is a ten second animation people tap through.
 */
class PauseActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PACKAGE = "pkg"
        private const val EXTRA_CATEGORY_ID = "cid"
        private const val EXTRA_CATEGORY_TITLE = "ctitle"
        private const val EXTRA_WAIT_SECONDS = "wait"
        private const val EXTRA_CHECK_IN_TYPE = "type"
        private const val EXTRA_FOLDER_URI = "folder"
        private const val EXTRA_COPY = "copy"
        private const val EXTRA_QUOTE_TEXT = "qtext"
        private const val EXTRA_QUOTE_AUTHOR = "qauthor"
        private const val EXTRA_STARTED_AT = "started"

        val currentInstances = mutableSetOf<PauseActivity>()

        /** True while a pause is on screen; the loop uses it so it never re-launches over itself. */
        val isShowing: Boolean get() = synchronized(currentInstances) { currentInstances.isNotEmpty() }

        fun start(context: Context, gate: PausePoint.Gate) {
            context.startActivity(
                Intent(context, PauseActivity::class.java)
                    .putExtra(EXTRA_PACKAGE, gate.packageName)
                    .putExtra(EXTRA_CATEGORY_ID, gate.categoryId)
                    .putExtra(EXTRA_CATEGORY_TITLE, gate.categoryTitle)
                    .putExtra(EXTRA_WAIT_SECONDS, gate.waitSeconds)
                    .putExtra(EXTRA_CHECK_IN_TYPE, gate.checkInType)
                    .putExtra(EXTRA_FOLDER_URI, gate.folderUri)
                    .putExtra(EXTRA_COPY, gate.copy)
                    .putExtra(EXTRA_QUOTE_TEXT, gate.quoteText)
                    .putExtra(EXTRA_QUOTE_AUTHOR, gate.quoteAuthor)
                    .putExtra(EXTRA_STARTED_AT, gate.startedAt)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var binding: ActivityPauseBinding

    private lateinit var packageName: String
    private var waitSeconds = CategoryPauseConfig.DEFAULT_WAIT_SECONDS
    private var checkInType = CategoryPauseConfig.CHECK_IN_BREATH
    private var startedAt = 0L

    private var entered = false

    private val tick = object : Runnable {
        override fun run() {
            val remaining = remainingMillis()

            binding.pauseProgress.progress =
                (100 - (remaining * 100 / (waitSeconds * 1000L).coerceAtLeast(1))).toInt()

            binding.pauseCountdown.text = ((remaining + 999) / 1000).toString()

            if (remaining > 0) {
                handler.postDelayed(this, 250)
            } else {
                revealContinue()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPauseBinding.inflate(layoutInflater)
        setContentView(binding.root)

        synchronized(currentInstances) { currentInstances.add(this) }

        packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: run {
            finish()
            return
        }
        waitSeconds = intent.getIntExtra(EXTRA_WAIT_SECONDS, CategoryPauseConfig.DEFAULT_WAIT_SECONDS)
            .coerceIn(CategoryPauseConfig.MIN_WAIT_SECONDS, CategoryPauseConfig.MAX_WAIT_SECONDS)
        checkInType = intent.getIntExtra(EXTRA_CHECK_IN_TYPE, CategoryPauseConfig.CHECK_IN_BREATH)
        startedAt = intent.getLongExtra(EXTRA_STARTED_AT, uptimeNow())

        binding.pauseCategory.text = intent.getStringExtra(EXTRA_CATEGORY_TITLE) ?: ""

        showContent()

        binding.pauseContinue.setOnClickListener { continueToApp(null) }
        binding.pauseClose.setOnClickListener { leave() }

        binding.pauseTimer5.setOnClickListener { continueToApp(5) }
        binding.pauseTimer15.setOnClickListener { continueToApp(15) }
        binding.pauseTimer30.setOnClickListener { continueToApp(30) }

        if (checkInType == CategoryPauseConfig.CHECK_IN_TIMER) {
            binding.pauseContinue.visibility = View.GONE
        }

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        handler.post(tick)
    }

    override fun onStop() {
        super.onStop()

        // Leaving — home, back, another app, the wall coming up — ends this launch's gate, so the
        // next launch starts the countdown from the top. Continue is the only exit that does not
        // come through here with [entered] still false.
        if (!entered && !isChangingConfigurations) {
            PausePoint.markLeft(this, packageName)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        handler.removeCallbacksAndMessages(null)

        synchronized(currentInstances) { currentInstances.remove(this) }
    }

    private fun remainingMillis(): Long {
        val elapsed = (uptimeNow() - startedAt).coerceAtLeast(0)

        return (waitSeconds * 1000L - elapsed).coerceAtLeast(0)
    }

    private fun revealContinue() {
        binding.pauseCountdown.text = "0"
        binding.pauseProgress.progress = 100

        if (checkInType == CategoryPauseConfig.CHECK_IN_TIMER) {
            binding.pauseTimerRow.visibility = View.VISIBLE

            return
        }

        binding.pauseContinue.visibility = View.VISIBLE

        // A line that has to be typed is a check-in only if it must be typed before the way opens.
        if (checkInType == CategoryPauseConfig.CHECK_IN_WRITE) {
            binding.pauseContinue.isEnabled = binding.pauseNote.text.isNotBlank()

            binding.pauseNote.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    binding.pauseContinue.isEnabled = s?.isNotBlank() == true
                }
            })
        }
    }

    private fun showContent() {
        val copy = intent.getStringExtra(EXTRA_COPY)
        val quoteText = intent.getStringExtra(EXTRA_QUOTE_TEXT)
        val quoteAuthor = intent.getStringExtra(EXTRA_QUOTE_AUTHOR)

        when (checkInType) {
            CategoryPauseConfig.CHECK_IN_IMAGES -> {
                binding.pauseImage.visibility = View.VISIBLE
                binding.pauseBody.text = copy ?: ""
                loadImage(intent.getStringExtra(EXTRA_FOLDER_URI))
            }

            CategoryPauseConfig.CHECK_IN_QUOTE -> {
                binding.pauseBody.text = quoteText ?: copy ?: "Breathe out."
                binding.pauseAuthor.text = quoteAuthor
                binding.pauseAuthor.visibility =
                    if (quoteAuthor.isNullOrBlank()) View.GONE else View.VISIBLE
            }

            CategoryPauseConfig.CHECK_IN_TIMER ->
                binding.pauseBody.text = copy ?: "How long do you need, really?"

            CategoryPauseConfig.CHECK_IN_WRITE -> {
                binding.pauseBody.text = copy ?: "What are you opening this for?"
                binding.pauseNote.visibility = View.VISIBLE
            }

            else ->
                binding.pauseBody.text = copy ?: "Breathe in.\nAnd out."
        }
    }

    /**
     * The pictures come from a folder the user picked, read through the tree URI rather than a path:
     * this app holds no storage permission and on Android 11+ there is no permission that grants a
     * plain path anyway.
     */
    private fun loadImage(folderUri: String?) {
        if (folderUri.isNullOrEmpty()) {
            binding.pauseBody.text = "No folder picked yet."

            return
        }

        Threads.database.execute {
            val bitmap = runCatching { readImageFromFolder(folderUri) }.getOrNull()

            if (bitmap != null) {
                runOnUiThread { binding.pauseImage.setImageBitmap(bitmap) }
            }
        }
    }

    private fun readImageFromFolder(folderUri: String): Bitmap? {
        val folder = DocumentFile.fromTreeUri(this, Uri.parse(folderUri)) ?: return null

        val images = folder.listFiles().filter { file ->
            file.isFile && file.type?.startsWith("image/") == true
        }

        if (images.isEmpty()) return null

        // Same picture all day, a different one tomorrow: enough variety to notice, not enough to
        // become wallpaper.
        val index = ((System.currentTimeMillis() / (24 * 60 * 60 * 1000)) % images.size).toInt()
        val uri = images[index].uri

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) > 1080) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }

        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun continueToApp(minutes: Int?) {
        if (entered) return

        if (checkInType == CategoryPauseConfig.CHECK_IN_WRITE && binding.pauseNote.text.isBlank()) {
            Toast.makeText(this, "One line first.", Toast.LENGTH_SHORT).show()

            return
        }

        entered = true

        PausePoint.markEntered(this, packageName, uptimeNow())

        if (minutes != null) {
            Interruptions.record(
                context = this,
                kind = "pause",
                outcome = "timer-$minutes",
                category = intent.getStringExtra(EXTRA_CATEGORY_TITLE),
                packageName = packageName,
                reason = null
            )
        }

        finish()
    }

    /** Leaving is not a decision: go home, keep the gate armed, let the next launch meet it again. */
    private fun leave() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { startActivity(home) }

        finish()
    }

    private fun uptimeNow(): Long = DefaultAppLogic.with(this).timeApi.getCurrentUptimeInMillis()
}
