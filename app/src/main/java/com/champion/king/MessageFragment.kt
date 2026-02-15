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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageFragment : Fragment() {

    private lateinit var rv: RecyclerView
    private lateinit var pb: ProgressBar
    private lateinit var tvEmpty: TextView

    private val adapter = MessageAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_message, container, false)

        rv = v.findViewById(R.id.rv_messages)
        pb = v.findViewById(R.id.pb_loading)
        tvEmpty = v.findViewById(R.id.tv_empty)

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        loadFirstPageUserMessages()

        return v
    }

    private fun loadFirstPageUserMessages() {
        val sp = requireContext().getSharedPreferences(AppConfig.Prefs.LOGIN_PREFS, 0)
        val loggedIn = sp.getBoolean("SESSION_LOGGED_IN", false)
        val userKey = sp.getString("SESSION_USER_KEY", null)

        if (!loggedIn || userKey.isNullOrBlank()) {
            adapter.setItems(emptyList())
            tvEmpty.visibility = View.VISIBLE
            return
        }

        pb.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.apiService.listNotifications(
                    userKey = userKey,
                    category = "USER",
                    limit = 10,
                    cursor = null
                )

                pb.visibility = View.GONE

                if (!resp.isSuccessful) {
                    Log.e("MessageFragment", "[listNotifications] http=${resp.code()} msg=${resp.message()}")
                    tvEmpty.visibility = View.VISIBLE
                    return@launch
                }

                val body = resp.body()
                val msgs = body?.messages ?: emptyList()

                adapter.setItems(msgs)
                tvEmpty.visibility = if (msgs.isEmpty()) View.VISIBLE else View.GONE

            } catch (e: Exception) {
                pb.visibility = View.GONE
                Log.e("MessageFragment", "[listNotifications] exception: ${e.message}", e)
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    // ---------------- Adapter ----------------

    private class MessageAdapter : RecyclerView.Adapter<MessageVH>() {

        private val items = mutableListOf<NotificationMessageDto>()
        private val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())

        fun setItems(newItems: List<NotificationMessageDto>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
            return MessageVH(v)
        }

        override fun onBindViewHolder(holder: MessageVH, position: Int) {
            val it = items[position]
            holder.bind(it, sdf)
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