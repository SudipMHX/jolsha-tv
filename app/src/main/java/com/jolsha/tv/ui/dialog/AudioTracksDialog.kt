package com.jolsha.tv.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import com.jolsha.tv.R
import com.jolsha.tv.data.model.AudioTrackInfo
import com.jolsha.tv.databinding.DialogAudioTracksBinding
import com.jolsha.tv.ui.common.FocusHighlightHelper
import com.jolsha.tv.ui.player.adapter.AudioTrackAdapter

class AudioTracksDialog(
    context: Context,
    private val tracks: List<AudioTrackInfo>,
    private val onTrackSelected: (AudioTrackInfo) -> Unit
) : Dialog(context, R.style.Theme_Jolsha_TV_Dialog) {

    private lateinit var binding: DialogAudioTracksBinding
    private lateinit var adapter: AudioTrackAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogAudioTracksBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)

        FocusHighlightHelper.attachFocusAnimation(binding.audioDialogButtonClose, 1.1f, 6f)
        binding.audioDialogButtonClose.setOnClickListener { dismiss() }

        adapter = AudioTrackAdapter { track ->
            onTrackSelected(track)
            dismiss()
        }
        binding.recyclerAudioTracks.adapter = adapter
        adapter.submitList(tracks)
    }
}
