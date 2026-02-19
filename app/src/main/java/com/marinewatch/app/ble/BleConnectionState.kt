package com.marinewatch.app.ble

/**
 * Represents the lifecycle states of the BLE connection to the Marine Gateway.
 */
enum class BleConnectionState {
    /** Not yet started or explicitly disconnected. */
    DISCONNECTED,

    /** Actively scanning for the MarineGateway advertisement. */
    SCANNING,

    /** TCP/BLE link established, discovering GATT services. */
    CONNECTING,

    /** GATT services discovered, notifications subscribed — data flowing. */
    CONNECTED,

    /** Connection lost; will retry after [BleConstants.RECONNECT_DELAY_MS]. */
    RECONNECTING,

    /** A pairing/passkey dialog is pending user action. */
    PAIRING
}
