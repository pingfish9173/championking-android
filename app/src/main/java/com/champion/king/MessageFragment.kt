package com.champion.king

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.champion.king.core.config.AppConfig
import com.champion.king.data.api.RetrofitClient
import com.champion.king.data.api.dto.NotificationMessageDto
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageFragment : Fragment() {

    private lateinit var rv: RecyclerView
    private lateinit var pb: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var tabLayout: TabLayout

    // 🌟 Headers
    private lateinit var llNormalActions: android.widget.LinearLayout
    private lateinit var rlSelectionActions: RelativeLayout
    private lateinit var rlSelectionHeader: RelativeLayout
    private lateinit var tvSelectionCount: TextView
    private lateinit var btnCancelSelection: ImageView
    private lateinit var btnDeleteSelected: ImageView
    private lateinit var cbSelectAll: CheckBox

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_message, container, false)

        rv = v.findViewById(R.id.rv_messages)
        pb = v.findViewById(R.id.pb_loading)
        tvEmpty = v.findViewById(R.id.tv_empty)
        tabLayout = v.findViewById(R.id.tab_layout_messages)

        // Headers
        llNormalActions = v.findViewById(R.id.ll_normal_actions)
        rlSelectionActions = v.findViewById(R.id.rl_selection_actions)
        tvSelectionCount = v.findViewById(R.id.tv_selection_count)
        btnCancelSelection = v.findViewById(R.id.btn_cancel_selection)
        btnDeleteSelected = v.findViewById(R.id.btn_delete_selected)

        // 🌟 綁定全選 CheckBox
        cbSelectAll = v.findViewById(R.id.cb_select_all)

        // 🌟 點擊全選的邏輯 (使用 setOnClickListener 避免與程式自動勾選衝突)
        cbSelectAll.setOnClickListener {
            if (cbSelectAll.isChecked) {
                adapter.selectAll()
            } else {
                adapter.deselectAll()
            }
        }

        val btnMarkAllRead: ImageView = v.findViewById(R.id.btn_mark_all_read)
        btnMarkAllRead.setOnClickListener { showMarkAllReadDialog() }

        // 取消選擇按鈕
        btnCancelSelection.setOnClickListener { adapter.exitSelectionMode() }

        // 刪除所選按鈕
        btnDeleteSelected.setOnClickListener { showDeleteSelectedDialog() }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
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

        // 監聽 Adapter 選擇狀態變化，來切換頂部 Header
        adapter.onSelectionModeChange = { isSelectionMode, selectedCount ->
            if (isSelectionMode) {
                llNormalActions.visibility = View.GONE
                rlSelectionActions.visibility = View.VISIBLE
                tvSelectionCount.text = "已選擇 $selectedCount 項"

                // 🌟 當前勾選數量等於總數量時，自動將全選 CheckBox 打勾
                cbSelectAll.isChecked = selectedCount > 0 && selectedCount == adapter.itemCount
            } else {
                rlSelectionActions.visibility = View.GONE
                llNormalActions.visibility = View.VISIBLE
                cbSelectAll.isChecked = false // 退出時重置
            }
        }

        setupTabLayout()
        loadFirstPageUserMessages()

        return v
    }

    private fun showDeleteSelectedDialog() {
        val selectedIdsList = adapter.getSelectedIds().toList()
        if (selectedIdsList.isEmpty()) return
        val uk = userKey ?: return

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("刪除訊息")
            .setMessage("確定要刪除這 ${selectedIdsList.size} 則訊息嗎？")
            .setPositiveButton("刪除") { _, _ ->

                // 🌟 1. 先計算出即將被刪除的訊息中，各分類有幾個是「未讀」的
                val unreadMap = adapter.getUnreadCountByCategory(selectedIdsList)

                // 2. 樂觀 UI 刪除列表項目
                adapter.removeMultipleByIds(selectedIdsList.toSet())
                if (adapter.itemCount == 0) tvEmpty.visibility = View.VISIBLE

                // 3. 結束選擇模式
                adapter.exitSelectionMode()

                // 🌟 4. 樂觀 UI 扣減紅點 (不用等 API 回來，畫面瞬間回饋)
                for ((cat, count) in unreadMap) {
                    for (i in 0 until count) {
                        decrementTabBadges(cat) // 扣掉 Tab 的紅點
                        (activity as? MainActivity)?.decreaseMessageBadge() // 扣掉左側選單的紅點
                    }
                }

                // 5. 背景呼叫多筆刪除 API
                lifecycleScope.launch {
                    try {
                        val resp = RetrofitClient.apiService.deleteNotifications(
                            com.champion.king.data.api.dto.DeleteNotificationsRequest(
                                userKey = uk,
                                messageIds = selectedIdsList
                            )
                        )

                        // 🌟 6. 確定後端真的刪除成功後，再重刷一次未讀數量，確保兩邊資料 100% 同步
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
                for (i in 0..2) tabLayout.getTabAt(i)?.removeBadge()
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
                        val tab = tabLayout.getTabAt(index)
                        if (unread > 0) tab?.orCreateBadge?.apply { number = unread }
                        else tab?.removeBadge()
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private fun setupTabLayout() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentCategory = when (tab?.position) {
                    0 -> "ALL"
                    1 -> "USER"
                    2 -> "PROMO"
                    else -> "ALL"
                }
                adapter.exitSelectionMode() // 切換 Tab 時取消選擇模式
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
            tvEmpty.visibility = View.VISIBLE
            for (i in 0..2) tabLayout.getTabAt(i)?.removeBadge()
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
        pb.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

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
                pb.visibility = View.GONE
                if (adapter.itemCount == 0) tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun onMessageClicked(msg: NotificationMessageDto) {
        // 如果目前是選擇模式，點擊就是勾選/取消勾選
        if (adapter.isSelectionMode) {
            adapter.toggleSelection(msg.messageId)
            return
        }

        // 一般模式：已讀邏輯
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
        // 如果還沒進入選擇模式，長按就進入選擇模式並勾選當前項
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
        val tab = tabLayout.getTabAt(index)
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

        // 🌟 選擇模式狀態
        var isSelectionMode = false
            private set
        private val selectedMessageIds = mutableSetOf<String>()
        var onSelectionModeChange: ((Boolean, Int) -> Unit)? = null

        fun enterSelectionMode(initialId: String) {
            isSelectionMode = true
            selectedMessageIds.add(initialId)
            onSelectionModeChange?.invoke(true, selectedMessageIds.size)
            notifyDataSetChanged() // 顯示全部的 CheckBox
        }

        fun exitSelectionMode() {
            isSelectionMode = false
            selectedMessageIds.clear()
            onSelectionModeChange?.invoke(false, 0)
            notifyDataSetChanged() // 隱藏全部的 CheckBox
        }

        fun toggleSelection(messageId: String) {
            if (selectedMessageIds.contains(messageId)) {
                selectedMessageIds.remove(messageId)
            } else {
                selectedMessageIds.add(messageId)
            }
            onSelectionModeChange?.invoke(isSelectionMode, selectedMessageIds.size)

            // 僅更新點擊的該筆 UI，增加效能
            val idx = items.indexOfFirst { it.messageId == messageId }
            if (idx >= 0) notifyItemChanged(idx)
        }

        // 🌟 新增：全部選取
        fun selectAll() {
            selectedMessageIds.clear()
            items.forEach { selectedMessageIds.add(it.messageId) }
            onSelectionModeChange?.invoke(isSelectionMode, selectedMessageIds.size)
            notifyDataSetChanged()
        }

        // 🌟 新增：全部取消選取
        fun deselectAll() {
            selectedMessageIds.clear()
            onSelectionModeChange?.invoke(isSelectionMode, 0)
            notifyDataSetChanged()
        }

        // 🌟 加上 .toSet() 確保回傳的是「拷貝」，而非原本的記憶體參考
        fun getSelectedIds(): Set<String> = selectedMessageIds.toSet()

        fun removeMultipleByIds(ids: Set<String>) {
            items.removeAll { ids.contains(it.messageId) }
            notifyDataSetChanged()
        }

        // 🌟 新增這個方法：用來計算即將被刪除的訊息中，各分類有多少是「未讀」的
        fun getUnreadCountByCategory(ids: List<String>): Map<String, Int> {
            val unreadMap = mutableMapOf<String, Int>()
            for (item in items) {
                // 如果這筆訊息在刪除清單中，且它是「未讀」的
                if (ids.contains(item.messageId) && item.readAt == null) {
                    val count = unreadMap.getOrDefault(item.category, 0)
                    unreadMap[item.category] = count + 1
                }
            }
            return unreadMap
        }

        // --- 以下為原本的方法 ---
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
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return MessageVH(v)
        }

        override fun onBindViewHolder(holder: MessageVH, position: Int) {
            val theItem = items[position]
            holder.bind(theItem, sdf, isSelectionMode, selectedMessageIds.contains(theItem.messageId))

            holder.itemView.setOnClickListener { onClick(theItem) }
            holder.itemView.setOnLongClickListener {
                onLongPress(theItem)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class MessageVH(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvTitle: TextView = itemView.findViewById(R.id.tv_item_title)
        private val tvBody: TextView = itemView.findViewById(R.id.tv_item_body)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_item_time)
        private val cbSelect: CheckBox = itemView.findViewById(R.id.cb_message_select)

        fun bind(it: NotificationMessageDto, sdf: SimpleDateFormat, isSelectionMode: Boolean, isSelected: Boolean) {
            tvBody.text = it.body
            tvTime.text = sdf.format(Date(it.createdAt))

            // 🌟 判斷是否已讀，動態改變標題與背景
            if (it.readAt == null) {
                // 未讀：加上字樣，並使用原本的灰色背景
                tvTitle.text = "${it.title}  (未讀)"
                itemView.setBackgroundResource(R.drawable.cell_background)
            } else {
                // 已讀：純標題，並切換成剛才建立的白色背景
                tvTitle.text = it.title
                itemView.setBackgroundResource(R.drawable.cell_background_read)
            }

            // 處理 CheckBox 多選狀態
            if (isSelectionMode) {
                cbSelect.visibility = View.VISIBLE
                cbSelect.isChecked = isSelected
            } else {
                cbSelect.visibility = View.GONE
                cbSelect.isChecked = false
            }
        }
    }
}