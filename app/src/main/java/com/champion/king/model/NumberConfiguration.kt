package com.champion.king.model

import android.os.Parcel
import android.os.Parcelable

// 定義刮刮卡中每個數字格的配置
data class NumberConfiguration(
    var id: Int = 0, // 編號 (例如：1, 2, 3...，用於識別格子位置)
    var number: Int = 0, // 刮開後顯示的數字
    var scratched: Boolean = false, // 是否已刮開 (true=已刮開, false=未刮開)
    var scratchedAt: Long? = null,  // ✅ 刮開時間（Unix ms；未刮開為 null）
) : Parcelable {

    // 從 Parcel 讀取數據的次級建構子
    constructor(parcel: Parcel) : this(
        parcel.readInt(), // 讀取 id
        parcel.readInt(), // 讀取 number
        parcel.readByte() != 0.toByte(), // 讀取 scratched (byte 轉 boolean)
        parcel.readValue(Long::class.java.classLoader) as? Long // ✅ 讀取 scratchedAt
    )

    // 將物件數據寫入 Parcel
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id) // 寫入 id
        parcel.writeInt(number) // 寫入 number
        parcel.writeByte(if (scratched) 1 else 0) // 將 boolean 轉為 byte 寫入
        parcel.writeValue(scratchedAt) // ✅ 寫入 scratchedAt
    }

    // 返回物件的內容描述，通常返回 0
    override fun describeContents(): Int = 0

    // 伴生物件，用於從 Parcel 創建物件陣列
    companion object CREATOR : Parcelable.Creator<NumberConfiguration> {
        override fun createFromParcel(parcel: Parcel): NumberConfiguration = NumberConfiguration(parcel)
        override fun newArray(size: Int): Array<NumberConfiguration?> = arrayOfNulls(size)
    }
}
