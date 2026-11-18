package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.model.DirectorySource
import com.example.model.ServerHealth
import com.example.model.VpnProtocol
import com.example.model.VpnServer
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vpn_servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val countryName: String,
    val countryCode: String,
    val host: String,
    val ipAddress: String,
    val port: Int,
    val protocol: String,
    val transport: String,
    val directorySource: String,
    val latencyMs: Int?,
    val health: String,
    val bandwidthBps: Long,
    val activeSessions: Int,
    val score: Long,
    val uptimeSeconds: Long,
    val operatorName: String,
    val logPolicy: String,
    val trustNotice: String,
    val supportsIpv6: Boolean,
    val isFavorite: Boolean,
    val isImported: Boolean,
    val inAppTunnelSupported: Boolean,
    val lastCheckedAt: Long,
    val lastError: String?,
    val peerPublicKey: String?,
    val encryptedConfigPayload: String?
) {
    fun toDomain(): VpnServer = VpnServer(
        id = id,
        name = name,
        countryName = countryName,
        countryCode = countryCode,
        host = host,
        ipAddress = ipAddress,
        port = port,
        protocol = runCatching { VpnProtocol.valueOf(protocol) }.getOrDefault(VpnProtocol.WIREGUARD),
        transport = transport,
        directorySource = runCatching { DirectorySource.valueOf(directorySource) }
            .getOrDefault(DirectorySource.USER_IMPORTED),
        latencyMs = latencyMs,
        health = runCatching { ServerHealth.valueOf(health) }.getOrDefault(ServerHealth.UNTESTED),
        bandwidthBps = bandwidthBps,
        activeSessions = activeSessions,
        score = score,
        uptimeSeconds = uptimeSeconds,
        operatorName = operatorName,
        logPolicy = logPolicy,
        trustNotice = trustNotice,
        supportsIpv6 = supportsIpv6,
        isFavorite = isFavorite,
        isImported = isImported,
        inAppTunnelSupported = inAppTunnelSupported,
        lastCheckedAt = lastCheckedAt,
        lastError = lastError,
        peerPublicKey = peerPublicKey,
        encryptedConfigPayload = encryptedConfigPayload
    )

    companion object {
        fun fromDomain(server: VpnServer): ServerEntity = ServerEntity(
            id = server.id,
            name = server.name,
            countryName = server.countryName,
            countryCode = server.countryCode,
            host = server.host,
            ipAddress = server.ipAddress,
            port = server.port,
            protocol = server.protocol.name,
            transport = server.transport,
            directorySource = server.directorySource.name,
            latencyMs = server.latencyMs,
            health = server.health.name,
            bandwidthBps = server.bandwidthBps,
            activeSessions = server.activeSessions,
            score = server.score,
            uptimeSeconds = server.uptimeSeconds,
            operatorName = server.operatorName,
            logPolicy = server.logPolicy,
            trustNotice = server.trustNotice,
            supportsIpv6 = server.supportsIpv6,
            isFavorite = server.isFavorite,
            isImported = server.isImported,
            inAppTunnelSupported = server.inAppTunnelSupported,
            lastCheckedAt = server.lastCheckedAt,
            lastError = server.lastError,
            peerPublicKey = server.peerPublicKey,
            encryptedConfigPayload = server.encryptedConfigPayload
        )
    }
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM vpn_servers ORDER BY isFavorite DESC, inAppTunnelSupported DESC, COALESCE(latencyMs, 999999) ASC, score DESC")
    fun observeServers(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM vpn_servers")
    suspend fun getAllOnce(): List<ServerEntity>

    @Query("SELECT * FROM vpn_servers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(servers: List<ServerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: ServerEntity)

    @Query("UPDATE vpn_servers SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE vpn_servers SET latencyMs = :latencyMs, health = :health, lastCheckedAt = :checkedAt, lastError = :error WHERE id = :id")
    suspend fun updateHealth(id: String, latencyMs: Int?, health: String, checkedAt: Long, error: String?)

    @Query("DELETE FROM vpn_servers WHERE isImported = 0 AND isFavorite = 0")
    suspend fun clearNonFavoriteDiscovered()

    @Query("DELETE FROM vpn_servers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(entities = [ServerEntity::class], version = 1, exportSchema = false)
abstract class VpnDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    companion object {
        @Volatile
        private var instance: VpnDatabase? = null

        fun getInstance(context: Context): VpnDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VpnDatabase::class.java,
                    "free_public_vpn.db"
                ).build().also { instance = it }
            }
        }
    }
}
