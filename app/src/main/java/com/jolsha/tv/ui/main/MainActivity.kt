package com.jolsha.tv.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jolsha.tv.R
import com.jolsha.tv.data.model.Category
import com.jolsha.tv.data.model.Channel
import com.jolsha.tv.databinding.ActivityMainBinding
import com.jolsha.tv.ui.common.FocusHighlightHelper
import com.jolsha.tv.ui.dialog.SettingsDialog
import com.jolsha.tv.ui.main.adapter.CategoryAdapter
import com.jolsha.tv.ui.main.adapter.ChannelAdapter
import com.jolsha.tv.ui.player.PlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Ignore if persistable permission is not granted
            }
            viewModel.loadFromUri(uri)
            Toast.makeText(this, "Loading M3U playlist…", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupCategoriesRecyclerView()
        setupChannelsRecyclerView()
        observeViewModel()
    }

    private fun setupHeader() {
        FocusHighlightHelper.attachFocusAnimation(binding.buttonSearch, scale = 1.05f, focusedElevation = 8f)
        FocusHighlightHelper.attachFocusAnimation(binding.buttonSettings, scale = 1.05f, focusedElevation = 8f)
        FocusHighlightHelper.attachFocusAnimation(binding.buttonEmptyAction, scale = 1.05f, focusedElevation = 8f)

        binding.buttonSearch.setOnClickListener {
            toggleSearch()
        }

        binding.buttonClearSearch.setOnClickListener {
            binding.inputSearch.text.clear()
            viewModel.clearSearch()
            binding.searchContainer.visibility = View.GONE
            binding.buttonSearch.requestFocus()
        }

        binding.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.buttonSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.buttonEmptyAction.setOnClickListener {
            viewModel.loadSamplePlaylist()
        }
    }

    private fun toggleSearch() {
        if (binding.searchContainer.visibility == View.VISIBLE) {
            binding.searchContainer.visibility = View.GONE
            viewModel.clearSearch()
            binding.buttonSearch.requestFocus()
        } else {
            binding.searchContainer.visibility = View.VISIBLE
            binding.inputSearch.requestFocus()
        }
    }

    private fun showSettingsDialog() {
        val dialog = SettingsDialog(this, viewModel) {
            openDocumentLauncher.launch(arrayOf("audio/*", "video/*", "application/*", "text/*", "*/*"))
        }
        dialog.show()
    }

    private fun setupCategoriesRecyclerView() {
        categoryAdapter = CategoryAdapter(
            onCategoryClick = { category ->
                viewModel.selectCategory(category)
            },
            onCategoryFocus = { category ->
                // Smooth TV UX: preview channels as user navigates through categories
                viewModel.selectCategory(category)
            }
        )

        binding.recyclerCategories.apply {
            adapter = categoryAdapter
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
        }
    }

    private fun setupChannelsRecyclerView() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                openPlayer(channel)
            },
            onChannelLongClick = { channel ->
                val isFav = viewModel.toggleFavorite(channel)
                val msg = if (isFav) getString(R.string.favorite_added) else getString(R.string.favorite_removed)
                Toast.makeText(this@MainActivity, "${channel.displayName}: $msg", Toast.LENGTH_SHORT).show()
                true
            },
            onChannelFocused = { _, position ->
                viewModel.lastFocusedChannelPosition = position
            }
        )

        val gridLayoutManager = GridLayoutManager(this, 4)
        binding.recyclerChannels.apply {
            adapter = channelAdapter
            layoutManager = gridLayoutManager
            setHasFixedSize(true)
        }
    }

    private fun openPlayer(channel: Channel) {
        val intent = PlayerActivity.createIntent(
            context = this,
            channelId = channel.id,
            categoryId = viewModel.selectedCategory.value.id
        )
        startActivity(intent)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.categories.collectLatest { categories ->
                        categoryAdapter.submitList(categories)
                    }
                }

                launch {
                    viewModel.selectedCategory.collectLatest { selectedCat ->
                        categoryAdapter.selectedCategoryId = selectedCat.id
                    }
                }

                launch {
                    viewModel.totalChannelCount.collectLatest { count ->
                        binding.badgeChannelCount.text = "$count CHANNELS"
                    }
                }

                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        binding.loadingView.visibility = if (loading) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.displayedChannels.collectLatest { channels ->
                        channelAdapter.submitList(channels) {
                            if (channels.isEmpty()) {
                                binding.emptyStateView.visibility = View.VISIBLE
                                binding.recyclerChannels.visibility = View.GONE
                            } else {
                                binding.emptyStateView.visibility = View.GONE
                                binding.recyclerChannels.visibility = View.VISIBLE

                                // Restore focus if needed
                                if (viewModel.lastFocusedChannelPosition in channels.indices) {
                                    binding.recyclerChannels.post {
                                        binding.recyclerChannels.layoutManager?.findViewByPosition(
                                            viewModel.lastFocusedChannelPosition
                                        )?.requestFocus()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If categories or channels are present, ensure first item or last focused item has focus
        if (binding.recyclerChannels.hasFocus() || binding.recyclerCategories.hasFocus()) return
        binding.recyclerCategories.post {
            binding.recyclerCategories.layoutManager?.findViewByPosition(0)?.requestFocus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSettingsDialog()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            toggleSearch()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
