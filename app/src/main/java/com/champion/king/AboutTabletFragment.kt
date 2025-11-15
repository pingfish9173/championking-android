package com.champion.king

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

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

    private lateinit var contentText: TextView

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

        contentText = view.findViewById(R.id.content_text)

        // --- 三種內文 ----
        val contentUpdateLog = """
2025/12/05　V1.2.1
冠軍王電子刮板正式上架。
        """.trimIndent()

        val contentAbout = """
一、購買與使用
1. 冠軍王電子刮板屬虛擬商品,僅供消費娛樂,不得折換現金或其他財物。
2. 點數發送至帳號後,恕不退換或轉讓。
3. 在商城確認購買刮板後,恕不退換或轉讓。

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
2. 當待機時間15分鐘,則直接跳轉廣告頁面。
3. 換板密碼係提供部分射板玩家使用,如有需要,店主可提供密碼給玩家,請自行判斷。
        """.trimIndent()


        val contentDisclaimer = """
冠軍王電子刮板│免責聲明

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

        // 預設顯示更新紀錄並設定選中狀態
        contentText.text = contentUpdateLog
        selectTab(TabType.UPDATE_LOG)

        // 點擊事件
        tabUpdateLog.setOnClickListener {
            contentText.text = contentUpdateLog
            selectTab(TabType.UPDATE_LOG)
        }

        tabAbout.setOnClickListener {
            contentText.text = contentAbout
            selectTab(TabType.ABOUT)
        }

        tabDisclaimer.setOnClickListener {
            contentText.text = contentDisclaimer
            selectTab(TabType.DISCLAIMER)
        }
    }

    /**
     * 選擇頁簽並更新視覺狀態
     */
    private fun selectTab(selectedTab: TabType) {
        // 先重置所有 tab 為未選中狀態
        resetTabState(tabUpdateLogText, tabUpdateLogIndicator)
        resetTabState(tabAboutText, tabAboutIndicator)
        resetTabState(tabDisclaimerText, tabDisclaimerIndicator)

        // 設定選中的 tab 為選中狀態
        when (selectedTab) {
            TabType.UPDATE_LOG -> setTabSelected(tabUpdateLogText, tabUpdateLogIndicator)
            TabType.ABOUT -> setTabSelected(tabAboutText, tabAboutIndicator)
            TabType.DISCLAIMER -> setTabSelected(tabDisclaimerText, tabDisclaimerIndicator)
        }
    }

    /**
     * 重置頁簽狀態為未選中
     */
    private fun resetTabState(textView: TextView, indicator: View) {
        textView.setTextColor(0xFF444444.toInt())  // 灰色
        textView.setTypeface(null, Typeface.NORMAL)  // 正常字重
        indicator.visibility = View.INVISIBLE  // 隱藏指示線
    }

    /**
     * 設定頁簽為選中狀態
     */
    private fun setTabSelected(textView: TextView, indicator: View) {
        textView.setTextColor(0xFFFF6B35.toInt())  // 主題橘色
        textView.setTypeface(null, Typeface.BOLD)  // 粗體
        indicator.visibility = View.VISIBLE  // 顯示指示線
    }

    /**
     * 頁簽類型枚舉
     */
    private enum class TabType {
        UPDATE_LOG,
        ABOUT,
        DISCLAIMER
    }
}