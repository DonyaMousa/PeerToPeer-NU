package com.example.peertopeer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.peertopeer.network.codec.PacketCodec
import com.example.peertopeer.network.model.NetworkPacket
import java.util.concurrent.ConcurrentHashMap

/** Durable pending work and destination receipts. One small transaction per state change. */
class PacketStore(context: Context) : SQLiteOpenHelper(context, "mesh_delivery.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending (id TEXT PRIMARY KEY, body BLOB NOT NULL)")
        db.execSQL("CREATE TABLE receipts (id TEXT PRIMARY KEY, expires INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun load(): List<NetworkPacket> = readableDatabase.rawQuery("SELECT body FROM pending", null).use { c ->
        buildList { while (c.moveToNext()) PacketCodec.decode(c.getBlob(0))?.takeUnless { it.isExpired() }?.let(::add) }
    }
    fun save(p: NetworkPacket) { writableDatabase.insertWithOnConflict("pending", null, ContentValues().apply { put("id", p.messageId); put("body", PacketCodec.encode(p)) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun remove(id: String) { writableDatabase.delete("pending", "id=?", arrayOf(id)) }
    fun received(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM receipts WHERE id=? AND expires>?", arrayOf(id, System.currentTimeMillis().toString())).use { it.moveToFirst() }
    fun remember(id: String, expires: Long) {
        val db = writableDatabase
        db.delete("receipts", "expires<?", arrayOf(System.currentTimeMillis().toString()))
        db.insertWithOnConflict("receipts", null, ContentValues().apply { put("id", id); put("expires", expires) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

class PersistentPacketMap(private val store: PacketStore) : ConcurrentHashMap<String, NetworkPacket>() {
    init { store.load().forEach { super.put(it.messageId, it) } }
    override fun put(key: String, value: NetworkPacket): NetworkPacket? { store.save(value); return super.put(key, value) }
    override fun remove(key: String): NetworkPacket? { store.remove(key); return super.remove(key) }
    override fun clear() { keys.toList().forEach(::remove) }
}
