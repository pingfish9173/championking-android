package com.champion.king.data

import com.champion.king.core.config.AppConfig
import com.champion.king.model.DeployHistory
import com.champion.king.model.UpdateInfo
import com.google.firebase.database.*

/**
 * 部署紀錄 Repository
 * 負責從 Firebase Realtime Database 讀取 deploy_history
 */
class DeployHistoryRepository(
    private val root: DatabaseReference = FirebaseDatabase.getInstance(AppConfig.DB_URL).reference
) {
    private fun deployHistoryRef() = root.child("deploy_history")

    /**
     * 監聽部署紀錄（依 deployedAt 降冪排序，最新在前）
     */
    fun observeDeployHistory(
        onItems: (List<DeployHistory>) -> Unit,
        onError: (String) -> Unit
    ): DbListenerHandle {
        val query = deployHistoryRef().orderByChild("deployedAt")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<DeployHistory>()
                for (child in snapshot.children) {
                    try {
                        val history = parseDeployHistory(child)
                        if (history != null) {
                            list.add(history)
                        }
                    } catch (e: Exception) {
                        // 略過解析失敗的紀錄
                    }
                }
                // 反轉列表，讓最新的在最前面
                onItems(list.reversed())
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error.message)
            }
        }
        query.addValueEventListener(listener)
        return DbListenerHandle(query, listener)
    }

    /**
     * 一次性讀取部署紀錄（不持續監聽）
     */
    fun getDeployHistory(
        onItems: (List<DeployHistory>) -> Unit,
        onError: (String) -> Unit
    ) {
        deployHistoryRef().orderByChild("deployedAt")
            .get()
            .addOnSuccessListener { snapshot ->
                val list = mutableListOf<DeployHistory>()
                for (child in snapshot.children) {
                    try {
                        val history = parseDeployHistory(child)
                        if (history != null) {
                            list.add(history)
                        }
                    } catch (e: Exception) {
                        // 略過解析失敗的紀錄
                    }
                }
                // 反轉列表，讓最新的在最前面
                onItems(list.reversed())
            }
            .addOnFailureListener { e ->
                onError(e.message ?: "讀取失敗")
            }
    }

    /**
     * 解析單筆部署紀錄
     */
    private fun parseDeployHistory(snapshot: DataSnapshot): DeployHistory? {
        val versionCode = snapshot.child("versionCode").getValue(Int::class.java) ?: return null
        val versionName = snapshot.child("versionName").getValue(String::class.java) ?: ""
        val deployedAt = snapshot.child("deployedAt").getValue(Long::class.java) ?: 0L
        val apkSize = snapshot.child("apkSize").getValue(String::class.java) ?: ""
        val downloadUrl = snapshot.child("downloadUrl").getValue(String::class.java) ?: ""
        val gitCommit = snapshot.child("gitCommit").getValue(String::class.java) ?: ""
        val gitBranch = snapshot.child("gitBranch").getValue(String::class.java) ?: ""

        // 解析 updateInfo
        val updateInfoSnapshot = snapshot.child("updateInfo")
        val title = updateInfoSnapshot.child("title").getValue(String::class.java) ?: ""
        val items = mutableListOf<String>()
        updateInfoSnapshot.child("items").children.forEach { itemSnapshot ->
            itemSnapshot.getValue(String::class.java)?.let { items.add(it) }
        }

        return DeployHistory(
            versionCode = versionCode,
            versionName = versionName,
            updateInfo = UpdateInfo(title = title, items = items),
            deployedAt = deployedAt,
            apkSize = apkSize,
            downloadUrl = downloadUrl,
            gitCommit = gitCommit,
            gitBranch = gitBranch
        )
    }
}