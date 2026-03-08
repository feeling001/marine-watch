package com.marinewatch.app.ble

import java.util.UUID

/**
 * BLE constants for the Marine Gateway ESP32.
 *
 * UUIDs follow the custom base: 4D475743-xxxx-4E41-5649-474154494F4E
 * Security: Secure Connections with MITM, bonding enabled.
 * The ESP32 IO capability is ESP_IO_CAP_OUT (displays PIN) so the
 * Android client must use KeyboardOnly / KeyboardDisplay capability.
 */
object BleConstants {

    /** Advertised BLE device name */
    const val DEVICE_NAME = "MarineGateway"

    /** Passkey shown on the ESP32 display (configurable server-side) */
    const val PASSKEY = 123456

    // ----------------------------------------------------------------
    // Navigation Service
    // ----------------------------------------------------------------

    /** Navigation GATT service UUID */
    val NAV_SERVICE_UUID: UUID =
        UUID.fromString("4d475743-0001-4e41-5649-474154494f4e")

    /**
     * NavData characteristic UUID.
     * Properties: READ + NOTIFY.
     * Payload: UTF-8 JSON, updated at 1 Hz.
     */
    val NAV_DATA_CHAR_UUID: UUID =
        UUID.fromString("4d475743-0101-4e41-5649-474154494f4e")

    // ----------------------------------------------------------------
    // Wind Service
    // ----------------------------------------------------------------

    /** Wind GATT service UUID */
    val WIND_SERVICE_UUID: UUID =
        UUID.fromString("4d475743-0002-4e41-5649-474154494f4e")

    /**
     * WindData characteristic UUID.
     * Properties: READ + NOTIFY.
     * Payload: UTF-8 JSON, updated at 1 Hz.
     *
     * JSON shape:
     * {
     *   "aws": Float|null,   // Apparent Wind Speed, knots
     *   "awa": Float|null,   // Apparent Wind Angle, degrees (-180 to +180, + = starboard)
     *   "tws": Float|null,   // True Wind Speed, knots
     *   "twa": Float|null,   // True Wind Angle, degrees (-180 to +180)
     *   "twd": Float|null    // True Wind Direction, degrees true (0-360)
     * }
     */
    val WIND_DATA_CHAR_UUID: UUID =
        UUID.fromString("4d475743-0201-4e41-5649-474154494f4e")

    // ----------------------------------------------------------------
    // Standard CCCD descriptor used to enable notifications
    // ----------------------------------------------------------------

    /** Client Characteristic Configuration Descriptor (0x2902) */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ----------------------------------------------------------------
    // Timing
    // ----------------------------------------------------------------

    /**
     * After this delay with no new packet, data values turn yellow
     * to warn the user that the information may be outdated.
     */
    const val DATA_WARN_THRESHOLD_MS = 5_000L

    /** Maximum acceptable data age before showing "stale" overlay (ms) */
    const val DATA_STALE_THRESHOLD_MS = 15_000L

    /** Delay before retrying a failed BLE connection (ms) */
    const val RECONNECT_DELAY_MS = 3_000L
}
