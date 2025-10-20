package com.champion.king

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.champion.king.core.ui.BaseBindingFragment
import com.champion.king.data.BackpackRepository
import com.champion.king.data.DbListenerHandle
import com.champion.king.databinding.FragmentBackpackBinding
import com.champion.king.util.toast

/** UI 要用的資料模型（維持原樣顯示） */
data class BackpackItem(
    val type: String,   // 例如 "10刮"
    val quantity: Int
)

class BackpackFragment : BaseBindingFragment<FragmentBackpackBinding>() {

    private var userSessionProvider: UserSessionProvider? = null

    // Repository
    private val repo by lazy { BackpackRepository() }

    // 監聽句柄（onDestroyView 時 remove）
    private var pointsHandle: DbListenerHandle? = null
    private var backpackHandle: DbListenerHandle? = null

    // 狀態
    private var currentUserPoints: Int = 0

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is UserSessionProvider) userSessionProvider = context
        else throw RuntimeException("$context must implement UserSessionProvider")
    }
    override fun onDetach() { super.onDetach(); userSessionProvider = null }

    override fun createBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentBackpackBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val userKey = userSessionProvider?.getCurrentUserFirebaseKey()
        if (userKey.isNullOrEmpty()) {
            binding.userPointsBackpackTextview.text = "我的點數: N/A"
            context?.toast("無法載入背包：用戶未登入")
            return
        }

        // 監聽點數
        pointsHandle = repo.observeUserPoints(
            userKey = userKey,
            onPoints = { p ->
                if (!isAdded || this@BackpackFragment.view == null) return@observeUserPoints
                currentUserPoints = p
                binding.userPointsBackpackTextview.text = "我的點數: $currentUserPoints"
                Log.d("BackpackFragment", "User points loaded: $currentUserPoints")
            },
            onError = { msg ->
                if (!isAdded || this@BackpackFragment.view == null) return@observeUserPoints
                Log.e("BackpackFragment", "Failed to load user points: $msg")
                context?.toast("載入點數失敗：$msg")
            }
        )

        // 監聽背包
        backpackHandle = repo.observeBackpack(
            userKey = userKey,
            onBackpack = { map ->
                if (!isAdded || this@BackpackFragment.view == null) return@observeBackpack
                // 依固定順序輸出（與你原先顯示一致）
                val order = listOf("10","20","25","30","40","50","60","80","100","120","160","200","240")
                val items = order
                    .filter { map.containsKey(it) }
                    .map { BackpackItem("${it}刮", map[it] ?: 0) }
                displayBackpackItems(items)
            },
            onError = { msg ->
                if (!isAdded || this@BackpackFragment.view == null) return@observeBackpack
                Log.e("BackpackFragment", "Failed to load backpack items: $msg")
                context?.toast("載入背包物品失敗：$msg")
            }
        )
    }

    override fun onDestroyView() {
        // ✅ 確實移除監聽，避免離開畫面後回呼觸發 UI 操作
        pointsHandle?.remove();   pointsHandle = null
        backpackHandle?.remove(); backpackHandle = null
        super.onDestroyView()
    }

    // ===== UI =====

    private fun displayBackpackItems(items: List<BackpackItem>) {
        val ctx = context ?: return

        val container = binding.backpackItemsContainer
        container.removeAllViews()

        val itemsPerRow = 5
        var rowLayout: LinearLayout? = null

        val total = items.size
        val fill = if (total % itemsPerRow != 0) itemsPerRow - (total % itemsPerRow) else 0

        (0 until total + fill).forEachIndexed { index, _ ->
            if (index % itemsPerRow == 0) {
                rowLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, resources.getDimensionPixelSize(R.dimen.shop_row_margin_bottom))
                    }
                }
                container.addView(rowLayout)
            }

            if (index < total) {
                val item = items[index]
                val itemLayout = LayoutInflater.from(ctx)
                    .inflate(R.layout.backpack_item_template, rowLayout, false)

                itemLayout.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )

                val productNameTextView: TextView = itemLayout.findViewById(R.id.product_name_textview)
                val quantityTextView: TextView = itemLayout.findViewById(R.id.quantity_textview)
                val productImageView: ImageView = itemLayout.findViewById(R.id.product_image_view)

                productNameTextView.text = item.type
                quantityTextView.text = "數量：${item.quantity}"
                productImageView.setImageResource(R.drawable.ic_shop_item)

                rowLayout?.addView(itemLayout)
            } else {
                rowLayout?.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }
    }

    // dp → px（保留給未來需要）
    private fun Int.toPx(): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, this.toFloat(), resources.displayMetrics
    ).toInt()
}
