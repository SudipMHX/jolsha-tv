package com.jolsha.tv.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.jolsha.tv.R
import com.jolsha.tv.data.model.AspectRatio
import com.jolsha.tv.databinding.DialogSettingsBinding
import com.jolsha.tv.ui.common.FocusHighlightHelper
import com.jolsha.tv.ui.main.MainViewModel

class SettingsDialog(
    context: Context,
    private val viewModel: MainViewModel,
    private val onImportM3UClick: () -> Unit
) : Dialog(context, R.style.Theme_Jolsha_TV_Dialog) {

    private lateinit var binding: DialogSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogSettingsBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        setupFocusAnimations()
        setupListeners()
        updateAspectRatioDisplay()
    }

    private fun setupFocusAnimations() {
        val focusScale = 1.03f
        val elevation = 8f

        FocusHighlightHelper.attachFocusAnimation(binding.rowImportM3U, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.rowReloadPlaylist, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.rowLoadSample, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.rowDefaultAspectRatio, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.rowClearHistory, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.rowResetFavorites, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.rowDeveloper, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.rowAbout, focusScale, elevation)
        FocusHighlightHelper.attachFocusAnimation(binding.dialogButtonClose, 1.1f, 6f)
    }

    private fun setupListeners() {
        binding.dialogButtonClose.setOnClickListener {
            dismiss()
        }

        // 1. Import M3U via SAF
        binding.rowImportM3U.setOnClickListener {
            dismiss()
            onImportM3UClick()
        }

        // 2. Reload Playlist
        binding.rowReloadPlaylist.setOnClickListener {
            viewModel.reloadPlaylist()
            Toast.makeText(context, "Reloading playlist…", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        // 3. Load Default Sample
        binding.rowLoadSample.setOnClickListener {
            viewModel.loadSamplePlaylist()
            Toast.makeText(context, "Loaded default sample playlist", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        // 4. Default Aspect Ratio
        binding.rowDefaultAspectRatio.setOnClickListener {
            val current = viewModel.getDefaultAspectRatio()
            val next = current.next()
            viewModel.setDefaultAspectRatio(next)
            updateAspectRatioDisplay()
        }

        // 5. Clear Watch History
        binding.rowClearHistory.setOnClickListener {
            viewModel.clearWatchHistory()
            Toast.makeText(context, context.getString(R.string.history_cleared), Toast.LENGTH_SHORT).show()
        }

        // 6. Reset Favorites
        binding.rowResetFavorites.setOnClickListener {
            viewModel.resetFavorites()
            Toast.makeText(context, context.getString(R.string.favorites_cleared), Toast.LENGTH_SHORT).show()
        }

        // 7. Developer Info
        binding.rowDeveloper.setOnClickListener {
            AlertDialog.Builder(context, R.style.Theme_Jolsha_TV_Dialog)
                .setTitle(context.getString(R.string.developer_dialog_title))
                .setMessage(context.getString(R.string.developer_dialog_message))
                .setPositiveButton("OK", null)
                .show()
        }

        // 8. About Jolsha
        binding.rowAbout.setOnClickListener {
            AlertDialog.Builder(context, R.style.Theme_Jolsha_TV_Dialog)
                .setTitle("Jolsha for Android TV")
                .setMessage("Version 1.0.0\n\nA modern, cinematic, high-performance IPTV player crafted exclusively for Android TV screens with full remote control navigation.\n\n© 2026 Jolsha TV")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun updateAspectRatioDisplay() {
        val ratio = viewModel.getDefaultAspectRatio()
        binding.textCurrentAspectValue.text = "Current: ${ratio.displayName}"
    }
}
