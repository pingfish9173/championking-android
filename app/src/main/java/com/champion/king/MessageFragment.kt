package com.champion.king

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private lateinit var tabLayout: TabLayout // 新增 TabLayout

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
        tabLayout = v.findViewById(R.id.tab_layout_messages) // 初始化 TabLayout

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

        // 設定 TabLayout 切換事件
        setupTabLayout()

        // 畫面載入時先抓第一頁
        loadFirstPageUserMessages()

        return v
    }

    // 取得各分類的未讀數並更新 Tab Badge
    private fun refreshTabBadges() {
        val uk = userKey ?: return
        // 必須與 TabLayout 中的順序一致：0=ALL, 1=USER, 2=PROMO
        val categories = listOf("ALL", "USER", "PROMO")

        lifecycleScope.launch {
            categories.forEachIndexed { index, cat ->
                try {
                    val resp = RetrofitClient.apiService.getUnreadCount(userKey = uk, category = cat)
                    if (resp.isSuccessful) {
                        val unread = resp.body()?.unread ?: 0
                        val tab = tabLayout.getTabAt(index)

                        if (unread > 0) {
                            // 顯示並設定紅點數字
                            tab?.orCreateBadge?.apply {
                                number = unread
                                // 可選：如果你想自訂紅點顏色，可以加上這兩行
                                // backgroundColor = ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)
                                // badgeTextColor = ContextCompat.getColor(requireContext(), android.R.color.white)
                            }
                        } else {
                            // 未讀數為 0 時移除紅點
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
                // 根據選中的 Tab 更新 currentCategory
                currentCategory = when (tab?.position) {
                    0 -> "ALL"
                    1 -> "USER"
                    2 -> "PROMO"
                    else -> "ALL"
                }
                // 切換 Tab 後重新載入第一頁
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
            // 未登入時清空所有 Badge
            for (i in 0..2) tabLayout.getTabAt(i)?.removeBadge()
            return
        }

        // reset paging state
        isLoading = false
        hasMore = true
        nextCursor = null
        loadedIds.clear()
        adapter.setItems(emptyList())

        loadMoreUserMessages()

        // 🌟 新增：載入資料時同步更新 Tab 上的紅點數字
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
                // 將這裡寫死的 "USER" 替換成動態的 currentCategory
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
        // 如果已經是已讀，就不做事
        if (msg.readAt != null) return

        val uk = userKey ?: return

        // 🌟 1. 樂觀 UI 更新：立刻改變列表文字為已讀狀態
        adapter.markRead(msg.messageId, System.currentTimeMillis())

        // 🌟 2. 立刻扣減上方 Tab 的紅點數字
        decrementTabBadges(msg.category)

        // 🌟 3. 立刻扣減左側主選單的紅點數字
        (activity as? MainActivity)?.decreaseMessageBadge()

        // 🌟 4. 背景發送 API (不用等它回來，使用者已經可以繼續滑動了)
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
                // 斷線或發生例外，依照你的需求跳出提示
                com.champion.king.util.ToastManager.show(requireContext(), "網路連線異常，訊息狀態可能未同步")

                // (選擇性) 如果你想做到很嚴謹，可以在這裡把剛才扣掉的紅點與已讀狀態 rollback 回來
                // 但通常為了不干擾使用者，單純跳 Toast 提醒就足夠了
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

                // 紀錄刪除前這則訊息是否「未讀」
                val wasUnread = msg.readAt == null

                // 🌟 1. 樂觀 UI 更新：立刻從列表 RecyclerView 中移除
                adapter.removeById(msg.messageId)
                if (adapter.itemCount == 0) tvEmpty.visibility = View.VISIBLE

                // 🌟 2. 如果刪除的是「未讀」訊息，才需要扣減紅點
                if (wasUnread) {
                    decrementTabBadges(msg.category)
                    (activity as? MainActivity)?.decreaseMessageBadge()
                }

                // 🌟 3. 背景發送 API
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
                        // 若斷線，使用者重整畫面(切換Tab)資料還是會回來，所以不用刻意寫回滾邏輯
                    }
                }
            }
            .show()
    }

    // 🌟 補上這個缺少的函式
    private fun decrementTabBadges(category: String) {
        decrementSingleTab(0) // Tab 0 永遠是 "ALL"，必扣
        when (category) {
            "USER" -> decrementSingleTab(1)
            "PROMO" -> decrementSingleTab(2)
        }
    }

    // 這是你原本已經有的函式
    private fun decrementSingleTab(index: Int) {
        val tab = tabLayout.getTabAt(index)
        val badge = tab?.badge
        if (badge != null && badge.number > 0) {
            badge.number -= 1
            if (badge.number <= 0) {
                tab.removeBadge() // 如果扣到 0 就把紅點隱藏
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
                onLongPress(theItem) // 你現在用 theItem 命名就用它
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
            tvTitle.text = it.title
            tvBody.text = it.body
            tvTime.text = sdf.format(Date(it.createdAt))

            // 未讀視覺（簡單：未讀就加個「(未讀)」）
            if (it.readAt == null) {
                tvTitle.text = "${it.title}  (未讀)"
            }
        }
    }
}