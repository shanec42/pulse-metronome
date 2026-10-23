package com.austinlinux.dfmetronome.ble.protocol

import java.util.UUID

object ProtocolRegistry {
    /** Detection priority: earlier wins when several match. */
    val all: List<OximeterProtocol> = listOf(Fs20fProtocol, Ks60fwProtocol, BerryProtocol, PlxProtocol, EmayProtocol, ViatomProtocol)
    fun forAdvertisement(name: String?, serviceUuids: Set<UUID>): OximeterProtocol? = all.firstOrNull { it.matchesAdvertisement(name, serviceUuids) }
    fun forGatt(characteristicUuids: Set<UUID>): OximeterProtocol? = all.firstOrNull { it.matchesGatt(characteristicUuids) }
    fun byId(id: ProtocolId): OximeterProtocol = all.first { it.id == id }
    /** Every service UUID a supported meter advertises. */
    fun advertisedServiceUuids(): Set<UUID> = all.flatMap { it.advertisedServiceUuids }.toSet()
}
