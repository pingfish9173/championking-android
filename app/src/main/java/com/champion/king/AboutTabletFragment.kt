package com.champion.king

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.champion.king.data.DbListenerHandle
import com.champion.king.data.DeployHistoryRepository
import com.champion.king.model.DeployHistory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AboutTabletFragment : Fragment() {

    private lateinit var tabUpdateLog: LinearLayout
    private lateinit var tabAbout: LinearLayout
    private lateinit var tabDisclaimer: LinearLayout

    private lateinit var tabUpdateLogText: TextView
    private lateinit var tabAboutText: TextView
    private lateinit var tabDisclaimerText: TextView

    private lateinit var tabUpdateLogIndicator: View
    private lateinit var tabAboutIndicator: View
    private lateinit var tabDisclaimerIndicator: View

    private lateinit var contentScrollUpdateLog: View
    private lateinit var contentScrollAbout: View
    private lateinit var contentScrollDisclaimer: View

    private lateinit var contentTextUpdateLog: TextView
    private lateinit var contentTextAbout: TextView
    private lateinit var contentTextDisclaimer: TextView
    private lateinit var titleText: TextView

    // Repository 和監聽器
    private val deployHistoryRepository = DeployHistoryRepository()
    private var deployHistoryListener: DbListenerHandle? = null

    private lateinit var tabFeatures: LinearLayout
    private lateinit var tabFeaturesText: TextView
    private lateinit var tabFeaturesIndicator: View
    private lateinit var contentScrollFeatures: View
    private lateinit var contentTextFeatures: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_about_tablet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化所有 views
        tabUpdateLog = view.findViewById(R.id.tab_update_log)
        tabAbout = view.findViewById(R.id.tab_about)
        tabDisclaimer = view.findViewById(R.id.tab_disclaimer)

        tabUpdateLogText = view.findViewById(R.id.tab_update_log_text)
        tabAboutText = view.findViewById(R.id.tab_about_text)
        tabDisclaimerText = view.findViewById(R.id.tab_disclaimer_text)

        tabUpdateLogIndicator = view.findViewById(R.id.tab_update_log_indicator)
        tabAboutIndicator = view.findViewById(R.id.tab_about_indicator)
        tabDisclaimerIndicator = view.findViewById(R.id.tab_disclaimer_indicator)

        contentScrollUpdateLog = view.findViewById(R.id.content_scroll_update_log)
        contentScrollAbout = view.findViewById(R.id.content_scroll_about)
        contentScrollDisclaimer = view.findViewById(R.id.content_scroll_disclaimer)

        contentTextUpdateLog = view.findViewById(R.id.content_text_update_log)
        contentTextAbout = view.findViewById(R.id.content_text_about)
        contentTextDisclaimer = view.findViewById(R.id.content_text_disclaimer)
        titleText = view.findViewById(R.id.title)

        tabFeatures = view.findViewById(R.id.tab_features)
        tabFeaturesText = view.findViewById(R.id.tab_features_text)
        tabFeaturesIndicator = view.findViewById(R.id.tab_features_indicator)
        contentScrollFeatures = view.findViewById(R.id.content_scroll_features)
        contentTextFeatures = view.findViewById(R.id.content_text_features)

        // 1. 定義內文 (這裡不需要手動加全形空格了，交給 Span 處理)
        val contentFeatures = """
一、商城：
裡面分別有10刮、20刮、25刮、30刮、40刮、50刮、60刮、80刮、100刮、120刮、160刮、200刮、240刮，總共13種刮數的刮板，任君挑選，可選擇自己合適的刮板做購買。

二、背包：
可查看目前自己背包裡剩餘的刮板存貨。

三、設定：
1. 上板參數設定：
上方有6個板位可供台主使用，可一次全部設定，也可只設定一個板位 (視每個台主使用情況有所不同)，右下方刮刮卡參數設定，刮數僅顯示背包裡有的庫存，特獎及大獎可依照每位台主自由選擇(點擊特獎或大獎可以直接點選左邊區域的數字，亦可點擊畫筆用輸入數字的方式)，夾出X刮及贈送X刮可選1~5自由設定，設定完成後按下儲存，此時設定好的參數會顯示在所選擇的板位區域，按下設為使用中(★)，右上角顯示★，表示上板成功，點擊左下方「玩家頁面」畫面即可跳轉。

2. 下板設定：
按下「改為未使用」，右上角★消失，即下板。

3. 返回：
按下返回，刮板將自動返回至背包(前提是刮板無刮開的狀態下才可進行)。

4. 刪除：
點選板位區任一板，按下刪除則自動銷毀(除刮開的刮數已超過此該板刮數之1/2(含)，且特獎尚未刮出)。

5. 自動刮開：
可以選擇自動刮開已儲存好的刮板刮數。

四、換板密碼：
可自由設定密碼，亦可隨時更改，在玩家頁面時，按下「下一板」，輸入密碼即係台主設定的換板密碼，輸入完成後即跳轉下一板。

五、會員資訊：
變更密碼：
如欲變更密碼，輸入現在密碼再輸入新密碼即可變更。

六、解除裝置綁定：
為避免有心人士盜用，一個帳號不可同時綁兩個設備，當按下解除裝置綁定，則會自動登出，如帳號在登入狀態時，系統則自動綁定設備。

七、檢查更新：
勾選「自動檢查更新」，系統將自動偵測有更新檔時自動更新。
""".trimIndent()

        // 2. 調用美化函式
        contentTextFeatures.text = formatFeatureContent(contentFeatures)

        tabFeatures.setOnClickListener {
            selectTab(TabType.FEATURES)
        }

        // --- 遊戲協議內文 ----
        val contentAbout = """
一、購買與使用
1. 冠軍王電子刮板屬虛擬商品,僅供消費娛樂,不得折換現金或其他財物。
2. 點數發送至帳號後,概不退換或轉讓。
3. 在商城確認購買刮板後,概不退換或轉讓。

二、獎項與中獎規則
1. 獎項為特獎及大獎。
2. 刮中對應的特獎及大獎數字表示中獎。
3. 特獎、大獎號碼由店主自由選定。
4. 特獎僅能設定一個數字,大獎依照不同的刮板數有設定上限。
5. 任一刮板數,如刮開部分超過該板數的1/2(含),且特獎尚未刮出時,不可將其刪除、換版。

三、帳號安全
1. 請妥善保管帳號與密碼,因外洩造成損失,恕不補發。
2. 點數、刮板不得跨帳號或合併。
3. 請使用官方刮板裝置,避免非正版裝置導致帳號異常,造成權益受損,本公司恕不負責。

四、合規限制
1. 未滿18歲之使用者,需經家長同意方可購買。
2. 僅供台灣境內使用,海外用戶須自行確認合法性。
3. 如發現盜刷、外掛、作弊等行為,主辦方有權終止帳號。

五、其他說明
1. 本商品屬虛擬商品,不適用七日鑑賞期。
2. 系統維護期間,可能暫停購買或使用,敬請見諒。
3. 主辦方保留活動解釋及調整之權利。

六、軟體說明
1. 冠軍王App內建永不休眠模式,故無需再另外設定。
2. 換板密碼係提供部分射板玩家使用,如有需要,店主可提供密碼給玩家,請自行判斷。
3. 軟體內部採自動更新機制
（請在用戶資訊頁面勾選自動更新）
        """.trimIndent()

        // --- 免責聲明內文 ----
        val contentDisclaimer = """
為保障使用者權益,並維護「冠軍王電子刮板」平台(以下簡稱「本平台」)之正常運作,請使用者在使用本平台提供之服務前,詳閱以下免責聲明。當使用者開始使用本平台,即視為已閱讀、了解並同意遵守本免責聲明之全部內容。

一、服務使用風險
使用者明白並同意,於本平台進行刮塗、遊戲或相關操作時,可能因網路環境、裝置狀況、系統更新、不可抗力等因素造成延遲、錯誤、中斷或資料遺失,本平台不負任何賠償責任。
本平台遊戲內容之結果為系統隨機生成,並無任何人工操控、保證中獎、特別待遇或其他不當行為。

二、帳號安全與裝置綁定
本平台採用裝置綁定與驗證機制以保障使用者安全。
使用者應妥善保管帳號、密碼及綁定裝置,因個人疏忽導致之損害,本平台不負賠償責任。

三、點數、道具及虛擬物品
所有點數與虛擬物品均無現金價值,亦不可兌換為現金或其他資產。
如因誤操作、第三方惡意行為或系統問題造成虛擬物品遺失,本平台將依紀錄協助查詢,但不保證補發。

四、內容正確性與資訊更新
本平台展示之圖片、商品資訊、活動內容僅供參考,本平台得隨時修改或移除相關資訊。
因資訊錯誤或變更導致之損失,本平台不負責任。

五、設備、使用環境與外在因素
使用者應確保運行本平台之裝置處於正常且合適之環境,例如:
- 避免陽光直射裝置
- 避免高溫、潮濕、灰塵、強震動、強磁場等極端環境
- 避免電量不足、裝置老化或散熱不良等狀況

若因不當使用環境而影響本平台運行,本平台不負責任。
本平台之功能可能因不同裝置規格、效能或使用者自行安裝之第三方軟體造成差異,本平台不保證於各型號裝置皆能完全正常運作。

六、第三方服務
對於本平台連結之外部網站、金流或其他第三方服務,其內容與安全性皆由第三方負責,本平台不負責任。

七、系統維護與服務中止
本平台可能因維護、更新、故障或不可抗力而暫停服務。
因上述原因造成的資料遺漏或使用不便,本平台不負任何賠償責任。

八、法律責任限制
除法律強制規定外,本平台對使用者因使用服務而產生之任何直接或間接損害,概不負責。

九、本聲明之修改
本平台得隨時修訂本免責聲明並公告於平台,使用者於公告後繼續使用即視為同意修訂內容。
        """.trimIndent()

        // 為 TextView 設置內容
        contentTextAbout.text = contentAbout
        contentTextDisclaimer.text = contentDisclaimer

        // 顯示載入中
        contentTextUpdateLog.text = "載入中..."

        // 從資料庫載入更新紀錄
        loadDeployHistory()

        // 預設顯示更新紀錄頁籤
        selectTab(TabType.UPDATE_LOG)

        // 點擊事件
        tabUpdateLog.setOnClickListener {
            selectTab(TabType.UPDATE_LOG)
        }

        tabAbout.setOnClickListener {
            selectTab(TabType.ABOUT)
        }

        tabDisclaimer.setOnClickListener {
            selectTab(TabType.DISCLAIMER)
        }
    }

    private fun formatFeatureContent(fullText: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val lines = fullText.split("\n")

        // 保留您調整好的縮進距離
        val titleIndent = 18  // 數字標題（1. 2. 等）的起點
        val contentIndent = 46 // 說明文字整段內縮的起點

        lines.forEach { line ->
            val start = sb.length
            sb.append(line).append("\n")
            val end = sb.length

            val trimmedLine = line.trim()

            // --- 新增：處理大標題（一到七）加粗 ---
            val mainTitles = listOf("一、", "二、", "三、", "四、", "五、", "六、", "七、")
            if (mainTitles.any { trimmedLine.startsWith(it) }) {
                sb.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start,
                    end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            // 1. 處理 1. 到 5. 的標題行
            else if (trimmedLine.startsWith("1.") || trimmedLine.startsWith("2.") ||
                trimmedLine.startsWith("3.") || trimmedLine.startsWith("4.") ||
                trimmedLine.startsWith("5.")) {

                sb.setSpan(
                    android.text.style.LeadingMarginSpan.Standard(titleIndent),
                    start,
                    end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            // 2. 處理標題下的說明文字行（如果該行不為空，且不屬於大標題）
            else if (line.isNotEmpty() && !mainTitles.any { line.startsWith(it) }) {

                // 使用 LeadingMarginSpan.Standard(indent) 會讓該行所有文字（含換行）都縮排
                sb.setSpan(
                    android.text.style.LeadingMarginSpan.Standard(contentIndent),
                    start,
                    end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return sb
    }

    /**
     * 從資料庫載入部署紀錄
     */
    private fun loadDeployHistory() {
        // 使用一次性讀取（不需要即時監聽）
        deployHistoryRepository.getDeployHistory(
            onItems = { historyList ->
                if (isAdded) {
                    displayUpdateLog(historyList)
                }
            },
            onError = { errorMsg ->
                if (isAdded) {
                    contentTextUpdateLog.text = "載入失敗：$errorMsg"
                }
            }
        )
    }

    private fun displayUpdateLog(historyList: List<DeployHistory>) {
        if (historyList.isEmpty()) {
            contentTextUpdateLog.text = "暫無更新紀錄"
            return
        }

        val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN)
        val sb = SpannableStringBuilder()

        fun appendStyledTitle(titleLine: String) {
            val start = sb.length
            sb.append(titleLine)
            val end = sb.length

            // 粗體
            sb.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            // 字體加大（可自行微調 1.10f ~ 1.30f）
            sb.setSpan(
                RelativeSizeSpan(1.18f),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        historyList.forEachIndexed { index, history ->
            // 日期和版本號（保持原樣）
            val dateStr = dateFormat.format(Date(history.deployedAt))
            sb.append("$dateStr　V${history.versionName}\n")

            // 標題（只改這行：粗體 + 加大）
            val title = history.updateInfo.title
            if (title.isNotEmpty()) {
                appendStyledTitle(title)
                sb.append("\n")
            }

            // 細項（保持原樣）
            history.updateInfo.items.forEach { item ->
                sb.append("• $item\n")
            }

            // 分隔（最後一筆不加）
            if (index < historyList.size - 1) {
                sb.append("\n")
            }
        }

        contentTextUpdateLog.text = sb
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 移除監聽器（如果有使用 observe 模式）
        deployHistoryListener?.remove()
        deployHistoryListener = null
    }

    /**
     * 選擇頁籤並更新視覺狀態
     */
    private fun selectTab(selectedTab: TabType) {
        // 重置所有狀態（記得加入新頁籤）
        resetTabState(tabUpdateLogText, tabUpdateLogIndicator)
        resetTabState(tabFeaturesText, tabFeaturesIndicator)
        resetTabState(tabAboutText, tabAboutIndicator)
        resetTabState(tabDisclaimerText, tabDisclaimerIndicator)

        contentScrollUpdateLog.visibility = View.GONE
        contentScrollFeatures.visibility = View.GONE
        contentScrollAbout.visibility = View.GONE
        contentScrollDisclaimer.visibility = View.GONE

        when (selectedTab) {
            TabType.UPDATE_LOG -> {
                setTabSelected(tabUpdateLogText, tabUpdateLogIndicator)
                contentScrollUpdateLog.visibility = View.VISIBLE
                titleText.text = "更新紀錄"
            }

            TabType.FEATURES -> { // 新增處理
                setTabSelected(tabFeaturesText, tabFeaturesIndicator)
                contentScrollFeatures.visibility = View.VISIBLE
                titleText.text = "功能介紹"
            }

            TabType.ABOUT -> {
                setTabSelected(tabAboutText, tabAboutIndicator)
                contentScrollAbout.visibility = View.VISIBLE
                titleText.text = "遊戲協議"
            }

            TabType.DISCLAIMER -> {
                setTabSelected(tabDisclaimerText, tabDisclaimerIndicator)
                contentScrollDisclaimer.visibility = View.VISIBLE
                titleText.text = "免責聲明"
            }
        }
    }

    /**
     * 重置頁籤狀態為未選中
     */
    private fun resetTabState(textView: TextView, indicator: View) {
        textView.setTextColor(0xFF444444.toInt())  // 灰色
        textView.setTypeface(null, Typeface.NORMAL)  // 正常字重
        indicator.visibility = View.INVISIBLE  // 隱藏指示線
    }

    /**
     * 設定頁籤為選中狀態
     */
    private fun setTabSelected(textView: TextView, indicator: View) {
        textView.setTextColor(0xFFFF6B35.toInt())  // 主題橘色
        textView.setTypeface(null, Typeface.BOLD)  // 粗體
        indicator.visibility = View.VISIBLE  // 顯示指示線
    }

    /**
     * 頁籤類型枚舉
     */
    private enum class TabType {
        UPDATE_LOG,
        FEATURES,
        ABOUT,
        DISCLAIMER
    }
}