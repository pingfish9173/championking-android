package com.champion.king

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.champion.king.core.config.AppConfig
import com.champion.king.core.ui.BaseBindingFragment
import com.champion.king.data.api.RetrofitClient
import com.champion.king.data.api.dto.NotificationMessageDto
import com.champion.king.databinding.FragmentMessageBinding
import com.champion.king.databinding.ItemMessageBinding
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageFragment : BaseBindingFragment<FragmentMessageBinding>() {

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentMessageBinding {
        return FragmentMessageBinding.inflate(inflater, container, false)
    }

    private val adapter = MessageAdapter(
        onClick = { msg -> onMessageClicked(msg) },
        onLongPress = { msg -> onMessageLongPress(msg) }
    )

    private var userKey: String? = null
    private var isLoading = false
    private var hasMore = true
    private var nextCursor: Long? = null
    private val loadedIds = hashSetOf<String>()

    private var currentCategory: String = "ALL"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cbSelectAll.setOnClickListener {
            if (binding.cbSelectAll.isChecked) {
                adapter.selectAll()
            } else {
                adapter.deselectAll()
            }
        }

        binding.btnMarkAllRead.setOnClickListener { showMarkAllReadDialog() }
        binding.btnCancelSelection.setOnClickListener { adapter.exitSelectionMode() }
        binding.btnDeleteSelected.setOnClickListener { showDeleteSelectedDialog() }

        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMessages.adapter = adapter
        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0) return

                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = adapter.itemCount

                if (hasMore && !isLoading && total > 0 && lastVisible >= total - 2) {
                    loadMoreUserMessages()
                }
            }
        })

        adapter.onSelectionModeChange = { isSelectionMode, selectedCount ->
            if (isSelectionMode) {
                binding.llNormalActions.visibility = View.GONE
                binding.rlSelectionActions.visibility = View.VISIBLE
                binding.tvSelectionCount.text = "已選擇 $selectedCount 項"
                binding.cbSelectAll.isChecked = selectedCount > 0 && selectedCount == adapter.itemCount
            } else {
                binding.rlSelectionActions.visibility = View.GONE
                binding.llNormalActions.visibility = View.VISIBLE
                binding.cbSelectAll.isChecked = false
            }
        }

        setupTabLayout()
        loadFirstPageUserMessages()
    }

    private fun showDeleteSelectedDialog() {
        val selectedIdsList = adapter.getSelectedIds().toList()
        if (selectedIdsList.isEmpty()) return
        val uk = userKey ?: return

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("刪除訊息")
            .setMessage("確定要刪除這 ${selectedIdsList.size} 則訊息嗎？")
            .setPositiveButton("刪除") { _, _ ->

                val unreadMap = adapter.getUnreadCountByCategory(selectedIdsList)

                adapter.removeMultipleByIds(selectedIdsList.toSet())
                if (adapter.itemCount == 0) binding.tvEmpty.visibility = View.VISIBLE

                adapter.exitSelectionMode()

                for ((cat, count) in unreadMap) {
                    for (i in 0 until count) {
                        decrementTabBadges(cat)
                        (activity as? MainActivity)?.decreaseMessageBadge()
                    }
                }

                lifecycleScope.launch {
                    try {
                        val resp = RetrofitClient.apiService.deleteNotifications(
                            com.champion.king.data.api.dto.DeleteNotificationsRequest(
                                userKey = uk,
                                messageIds = selectedIdsList
                            )
                        )

                        if (resp.isSuccessful && resp.body()?.success == true) {
                            refreshTabBadges()
                            (activity as? MainActivity)?.refreshMessageBadge()
                        } else {
                            Log.e("MessageFragment", "[delete selected] backend returned false")
                        }
                    } catch (e: Exception) {
                        Log.e("MessageFragment", "[delete selected] exception: ${e.message}", e)
                        com.champion.king.util.ToastManager.show(requireContext(), "網路連線異常，無法同步刪除結果")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showMarkAllReadDialog() {
        val uk = userKey ?: return
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("全部標示為已讀")
            .setMessage("確定要將所有訊息標示為已讀嗎？")
            .setPositiveButton("確定") { _, _ ->
                adapter.markAllRead()
                for (i in 0..2) binding.tabLayoutMessages.getTabAt(i)?.removeBadge()
                (activity as? MainActivity)?.clearMessageBadge()

                lifecycleScope.launch {
                    try {
                        RetrofitClient.apiService.markReadNotifications(
                            com.champion.king.data.api.dto.MarkReadNotificationsRequest(
                                userKey = uk,
                                messageIds = emptyList()
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("MessageFragment", "[markAllRead] exception: ${e.message}", e)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshTabBadges() {
        val uk = userKey ?: return
        val categories = listOf("ALL", "USER", "PROMO")

        lifecycleScope.launch {
            categories.forEachIndexed { index, cat ->
                try {
                    val resp = RetrofitClient.apiService.getUnreadCount(userKey = uk, category = cat)
                    if (resp.isSuccessful) {
                        val unread = resp.body()?.unread ?: 0
                        val tab = binding.tabLayoutMessages.getTabAt(index)
                        if (unread > 0) tab?.orCreateBadge?.apply { number = unread }
                        else tab?.removeBadge()
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun setupTabLayout() {
        binding.tabLayoutMessages.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentCategory = when (tab?.position) {
                    0 -> "ALL"
                    1 -> "USER"
                    2 -> "PROMO"
                    else -> "ALL"
                }
                adapter.exitSelectionMode()
                loadFirstPageUserMessages()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadFirstPageUserMessages() {
        val sp = requireContext().getSharedPreferences(AppConfig.Prefs.LOGIN_PREFS, 0)
        userKey = sp.getString("SESSION_USER_KEY", null)

        if (!sp.getBoolean("SESSION_LOGGED_IN", false) || userKey.isNullOrBlank()) {
            adapter.setItems(emptyList())
            binding.tvEmpty.visibility = View.VISIBLE
            for (i in 0..2) binding.tabLayoutMessages.getTabAt(i)?.removeBadge()
            return
        }

        isLoading = false
        hasMore = true
        nextCursor = null
        loadedIds.clear()
        adapter.setItems(emptyList())

        loadMoreUserMessages()
        refreshTabBadges()
    }

    private fun loadMoreUserMessages() {
        val uk = userKey ?: return
        if (isLoading || !hasMore) return

        isLoading = true
        binding.pbLoading.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.apiService.listNotifications(
                    userKey = uk,
                    category = currentCategory,
                    limit = 10,
                    cursor = nextCursor
                )

                if (resp.isSuccessful) {
                    val body = resp.body()
                    nextCursor = body?.nextCursor
                    hasMore = nextCursor != null

                    val msgs = body?.messages ?: emptyList()
                    val newOnes = msgs.filter { loadedIds.add(it.messageId) }
                    adapter.appendItems(newOnes)
                }
            } catch (e: Exception) {
                Log.e("MessageFragment", "[listNotifications] exception: ${e.message}", e)
            } finally {
                isLoading = false
                binding.pbLoading.visibility = View.GONE
                if (adapter.itemCount == 0) binding.tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun onMessageClicked(msg: NotificationMessageDto) {
        if (adapter.isSelectionMode) {
            adapter.toggleSelection(msg.messageId)
            return
        }

        if (msg.readAt != null) return
        val uk = userKey ?: return

        adapter.markRead(msg.messageId, System.currentTimeMillis())
        decrementTabBadges(msg.category)
        (activity as? MainActivity)?.decreaseMessageBadge()

        lifecycleScope.launch {
            try {
                RetrofitClient.apiService.markReadNotifications(
                    com.champion.king.data.api.dto.MarkReadNotificationsRequest(
                        userKey = uk,
                        messageIds = listOf(msg.messageId)
                    )
                )
            } catch (e: Exception) {}
        }
    }

    private fun onMessageLongPress(msg: NotificationMessageDto) {
        if (!adapter.isSelectionMode) {
            adapter.enterSelectionMode(msg.messageId)
        }
    }

    private fun decrementTabBadges(category: String) {
        decrementSingleTab(0)
        when (category) {
            "USER" -> decrementSingleTab(1)
            "PROMO" -> decrementSingleTab(2)
        }
    }

    private fun decrementSingleTab(index: Int) {
        val tab = binding.tabLayoutMessages.getTabAt(index)
        val badge = tab?.badge
        if (badge != null && badge.number > 0) {
            badge.number -= 1
            if (badge.number <= 0) tab.removeBadge()
        }
    }

    // ---------------- Adapter ----------------

    private class MessageAdapter(
        private val onClick: (NotificationMessageDto) -> Unit,
        private val onLongPress: (NotificationMessageDto) -> Unit
    ) : RecyclerView.Adapter<MessageVH>() {

        private val items = mutableListOf<NotificationMessageDto>()
        private val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())

        // 🌟 已移除 promoCache，因為不需要再快取了

        var isSelectionMode = false
            private set
        private val selectedMessageIds = mutableSetOf<String>()
        var onSelectionModeChange: ((Boolean, Int) -> Unit)? = null

        fun enterSelectionMode(initialId: String) {
            isSelectionMode = true
            selectedMessageIds.add(initialId)
            onSelectionModeChange?.invoke(true, selectedMessageIds.size)
            notifyDataSetChanged()
        }

        fun exitSelectionMode() {
            isSelectionMode = false
            selectedMessageIds.clear()
            onSelectionModeChange?.invoke(false, 0)
            notifyDataSetChanged()
        }

        fun toggleSelection(messageId: String) {
            if (selectedMessageIds.contains(messageId)) {
                selectedMessageIds.remove(messageId)
            } else {
                selectedMessageIds.add(messageId)
            }
            onSelectionModeChange?.invoke(isSelectionMode, selectedMessageIds.size)

            val idx = items.indexOfFirst { it.messageId == messageId }
            if (idx >= 0) notifyItemChanged(idx)
        }

        fun selectAll() {
            selectedMessageIds.clear()
            items.forEach { selectedMessageIds.add(it.messageId) }
            onSelectionModeChange?.invoke(isSelectionMode, selectedMessageIds.size)
            notifyDataSetChanged()
        }

        fun deselectAll() {
            selectedMessageIds.clear()
            onSelectionModeChange?.invoke(isSelectionMode, 0)
            notifyDataSetChanged()
        }

        fun getSelectedIds(): Set<String> = selectedMessageIds.toSet()

        fun removeMultipleByIds(ids: Set<String>) {
            items.removeAll { ids.contains(it.messageId) }
            notifyDataSetChanged()
        }

        fun getUnreadCountByCategory(ids: List<String>): Map<String, Int> {
            val unreadMap = mutableMapOf<String, Int>()
            for (item in items) {
                if (ids.contains(item.messageId) && item.readAt == null) {
                    val count = unreadMap.getOrDefault(item.category, 0)
                    unreadMap[item.category] = count + 1
                }
            }
            return unreadMap
        }

        fun setItems(newItems: List<NotificationMessageDto>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun appendItems(more: List<NotificationMessageDto>) {
            if (more.isEmpty()) return
            val start = items.size
            items.addAll(more)
            notifyItemRangeInserted(start, more.size)
        }

        fun markRead(messageId: String, readAt: Long) {
            val idx = items.indexOfFirst { it.messageId == messageId }
            if (idx < 0) return
            items[idx] = items[idx].copy(readAt = readAt)
            notifyItemChanged(idx)
        }

        fun markAllRead() {
            val now = System.currentTimeMillis()
            var changed = false
            for (i in items.indices) {
                if (items[i].readAt == null) {
                    items[i] = items[i].copy(readAt = now)
                    changed = true
                }
            }
            if (changed) notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageVH {
            val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return MessageVH(binding)
        }

        override fun onBindViewHolder(holder: MessageVH, position: Int) {
            val theItem = items[position]
            // 🌟 拔掉 promoCache 參數
            holder.bind(theItem, sdf, isSelectionMode, selectedMessageIds.contains(theItem.messageId))

            holder.itemView.setOnClickListener { onClick(theItem) }
            holder.itemView.setOnLongClickListener {
                onLongPress(theItem)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class MessageVH(private val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root) {

        // 🌟 已移除 promoRef，不需再連接 Firebase promotions 節點

        fun bind(
            it: NotificationMessageDto,
            sdf: SimpleDateFormat,
            isSelectionMode: Boolean,
            isSelected: Boolean
        ) {
            val timeString = sdf.format(Date(it.createdAt))
            binding.tvItemTime.text = timeString

            // 🌟 核心修正：不管是 USER 還是 PROMO，現在 inbox 裡面都有 title 和 body 了，直接拿來用！
            val displayTitle = it.title ?: "系統公告"
            val displayBody = it.body ?: ""

            binding.tvItemBody.text = displayBody

            if (it.readAt == null) {
                binding.tvItemTitle.text = "$displayTitle  (未讀)"
                binding.root.setBackgroundResource(R.drawable.cell_background)
            } else {
                binding.tvItemTitle.text = displayTitle
                binding.root.setBackgroundResource(R.drawable.cell_background_read)
            }

            if (isSelectionMode) {
                binding.cbMessageSelect.visibility = View.VISIBLE
                binding.cbMessageSelect.isChecked = isSelected
            } else {
                binding.cbMessageSelect.visibility = View.GONE
                binding.cbMessageSelect.isChecked = false
            }
        }
    }
}