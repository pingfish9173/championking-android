package com.champion.king

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView // 🌟 補上 ImageView 的 import
import android.widget.ProgressBar
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

    private val adapter = MessageAdapter(
        onClick = { msg -> onMessageClicked(msg) },
        onLongPress = { msg -> onMessageLongPress(msg) }
    )
    private var userKey: String? = null
    private var isLoading = false
    private var hasMore = true
    private var nextCursor: Long? = null
    private val loadedIds = hashSetOf<String>()

    // 定義當前選擇的 Category，預設為 "ALL"
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

        // 🌟 綁定右上角的一鍵已讀(掃把)按鈕
        val btnMarkAllRead: ImageView = v.findViewById(R.id.btn_mark_all_read)
        btnMarkAllRead.setOnClickListener { showMarkAllReadDialog() }

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

        setupTabLayout()
        loadFirstPageUserMessages()

        return v
    }

    // 🌟 一鍵已讀的對話框與邏輯
    private fun showMarkAllReadDialog() {
        val uk = userKey ?: return

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("全部標示為已讀")
            .setMessage("確定要將所有訊息標示為已讀嗎？")
            .setPositiveButton("確定") { _, _ ->

                // 1. 樂觀 UI 更新：將列表內所有訊息瞬間變更為已讀
                adapter.markAllRead()

                // 2. 移除 TabLayout 上的所有分類紅點
                for (i in 0..2) tabLayout.getTabAt(i)?.removeBadge()

                // 3. 移除左側主選單的紅點
                (activity as? MainActivity)?.clearMessageBadge()

                // 4. 背景呼叫 API 告知後端（傳入空陣列代表全部已讀）
                lifecycleScope.launch {
                    try {
                        val resp = RetrofitClient.apiService.markReadNotifications(
                            com.champion.king.data.api.dto.MarkReadNotificationsRequest(
                                userKey = uk,
                                messageIds = emptyList()
                            )
                        )

                        if (!resp.isSuccessful) {
                            Log.e("MessageFragment", "[markAllRead] backend failed")
                        }
                    } catch (e: Exception) {
                        Log.e("MessageFragment", "[markAllRead] exception: ${e.message}", e)
                        com.champion.king.util.ToastManager.show(requireContext(), "網路連線異常，狀態可能未同步")
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

                        if (unread > 0) {
                            tab?.orCreateBadge?.apply { number = unread }
                        } else {
                            tab?.removeBadge()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MessageFragment", "[refreshTabBadges] category=$cat exception: ${e.message}")
                }
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
                loadFirstPageUserMessages()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun loadFirstPageUserMessages() {
        val sp = requireContext().getSharedPreferences(AppConfig.Prefs.LOGIN_PREFS, 0)
        val loggedIn = sp.getBoolean("SESSION_LOGGED_IN", false)
        userKey = sp.getString("SESSION_USER_KEY", null)

        if (!loggedIn || userKey.isNullOrBlank()) {
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

                if (!resp.isSuccessful) {
                    Log.e("MessageFragment", "[listNotifications] http=${resp.code()} msg=${resp.message()}")
                    return@launch
                }

                val body = resp.body()
                val msgs = body?.messages ?: emptyList()

                nextCursor = body?.nextCursor
                hasMore = nextCursor != null

                val newOnes = msgs.filter { loadedIds.add(it.messageId) }

                adapter.appendItems(newOnes)

                if (adapter.itemCount == 0) {
                    tvEmpty.visibility = View.VISIBLE
                }

            } catch (e: Exception) {
                Log.e("MessageFragment", "[listNotifications] exception: ${e.message}", e)
                if (adapter.itemCount == 0) tvEmpty.visibility = View.VISIBLE
            } finally {
                isLoading = false
                pb.visibility = View.GONE
            }
        }
    }

    private fun onMessageClicked(msg: NotificationMessageDto) {
        if (msg.readAt != null) return

        val uk = userKey ?: return

        adapter.markRead(msg.messageId, System.currentTimeMillis())
        decrementTabBadges(msg.category)
        (activity as? MainActivity)?.decreaseMessageBadge()

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.apiService.markReadNotifications(
                    com.champion.king.data.api.dto.MarkReadNotificationsRequest(
                        userKey = uk,
                        messageIds = listOf(msg.messageId)
                    )
                )

                if (!resp.isSuccessful || resp.body()?.success != true) {
                    Log.e("MessageFragment", "[markRead] backend returned false")
                }
            } catch (e: Exception) {
                Log.e("MessageFragment", "[markRead] exception: ${e.message}", e)
                com.champion.king.util.ToastManager.show(requireContext(), "網路連線異常，訊息狀態可能未同步")
            }
        }
    }

    private fun onMessageLongPress(msg: NotificationMessageDto) {
        val uk = userKey ?: return

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("刪除訊息")
            .setMessage("確定要刪除這則訊息嗎？\n\n${msg.title}")
            .setNegativeButton("取消", null)
            .setPositiveButton("刪除") { _, _ ->

                val wasUnread = msg.readAt == null

                adapter.removeById(msg.messageId)
                if (adapter.itemCount == 0) tvEmpty.visibility = View.VISIBLE

                if (wasUnread) {
                    decrementTabBadges(msg.category)
                    (activity as? MainActivity)?.decreaseMessageBadge()
                }

                lifecycleScope.launch {
                    try {
                        val resp = RetrofitClient.apiService.deleteNotifications(
                            com.champion.king.data.api.dto.DeleteNotificationsRequest(
                                userKey = uk,
                                messageIds = listOf(msg.messageId)
                            )
                        )

                        if (!resp.isSuccessful || resp.body()?.success != true) {
                            Log.e("MessageFragment", "[delete] backend returned false")
                        }
                    } catch (e: Exception) {
                        Log.e("MessageFragment", "[delete] exception: ${e.message}", e)
                        com.champion.king.util.ToastManager.show(requireContext(), "網路連線異常，無法同步刪除結果")
                    }
                }
            }
            .show()
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
            if (badge.number <= 0) {
                tab.removeBadge()
            }
        }
    }

    // ---------------- Adapter ----------------

    private class MessageAdapter(
        private val onClick: (NotificationMessageDto) -> Unit,
        private val onLongPress: (NotificationMessageDto) -> Unit
    ) : RecyclerView.Adapter<MessageVH>() {

        private val items = mutableListOf<NotificationMessageDto>()
        private val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())

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
            val old = items[idx]
            items[idx] = old.copy(readAt = readAt)
            notifyItemChanged(idx)
        }

        // 🌟 新增：讓畫面上所有項目變成已讀
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

        fun removeById(messageId: String) {
            val idx = items.indexOfFirst { it.messageId == messageId }
            if (idx < 0) return
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return MessageVH(v)
        }

        override fun onBindViewHolder(holder: MessageVH, position: Int) {
            val theItem = items[position]
            holder.bind(theItem, sdf)
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

        fun bind(it: NotificationMessageDto, sdf: SimpleDateFormat) {
            tvBody.text = it.body
            tvTime.text = sdf.format(Date(it.createdAt))

            if (it.readAt == null) {
                tvTitle.text = "${it.title}  (未讀)"
            } else {
                tvTitle.text = it.title
            }
        }
    }
}