package com.moting.linkgo.data.backup

/**
 * 备份通道抽象：本地与云统一实现 [BackupStore]，[BackupManager] 与通道无关。
 */
interface BackupStore {
    /** 写入一份备份，返回通道结果 */
    suspend fun save(fileName: String, json: String): ChannelResult

    /** 读取指定备份内容；不存在或失败返回 null */
    suspend fun load(fileName: String): String?

    /** 列举全部备份（按时间倒序） */
    suspend fun list(): List<BackupInfo>

    /** 删除单份备份（仅用户手动触发，不自动清理） */
    suspend fun delete(fileName: String): Boolean
}
