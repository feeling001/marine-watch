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
     */
    val WIND_DATA_CHAR_UUID: UUID =
        UUID.fromString("4d475743-0201-4e41-5649-474154494f4e")

    // ----------------------------------------------------------------
    // Sail Performance Service
    // ----------------------------------------------------------------

    /** Sail Performance GATT service UUID */
    val PERF_SERVICE_UUID: UUID =
        UUID.fromString("4d475743-0004-4e41-5649-474154494f4e")

    /**
     * PerformanceData characteristic UUID.
     * Properties: READ + NOTIFY.
     * Payload: UTF-8 JSON, updated at 1 Hz.
     */
    val PERF_DATA_CHAR_UUID: UUID =
        UUID.fromString("4d475743-0401-4e41-5649-474154494f4e")

    // ----------------------------------------------------------------
    // Autopilot Service
    // ----------------------------------------------------------------

    /** Autopilot GATT service UUID */
    val AUTOPILOT_SERVICE_UUID: UUID =
        UUID.fromString("4d475743-0003-4e41-5649-474154494f4e")

    /**
     * AutopilotData characteristic UUID.
     * Properties: READ + NOTIFY.
     * Payload: UTF-8 JSON, updated at 1 Hz.
     */
    val AUTOPILOT_DATA_CHAR_UUID: UUID =
        UUID.fromString("4d475743-0301-4e41-5649-474154494f4e")

    /**
     * AutopilotCmd characteristic UUID.
     * Properties: WRITE (no response).
     * Payload: UTF-8 JSON command.
     *
     * JSON shape: { "command": "<cmd>" }
     * Commands: "enable", "disable", "adjust+10", "adjust-10", "adjust+1", "adjust-1"
     */
    val AUTOPILOT_CMD_CHAR_UUID: UUID =
        UUID.fromString("4d475743-0302-4e41-5649-474154494f4e")

    // ----------------------------------------------------------------
    // Admin Service
    // ----------------------------------------------------------------

    /** Admin GATT service UUID */
    val ADMIN_SERVICE_UUID: UUID =
        UUID.fromString("4d475743-0005-4e41-5649-474154494f4e")

    /**
     * AdminData characteristic UUID.
     * Properties: READ + NOTIFY.
     * Payload: UTF-8 JSON, updated at 1 Hz.
     * Fields: uptime_s, datetime_utc, wifi_mode, wifi_ssid, ip, free_heap.
     */
    val ADMIN_DATA_CHAR_UUID: UUID =
        UUID.fromString("4d475743-0501-4e41-5649-474154494f4e")

    /**
     * AdminCmd characteristic UUID.
     * Properties: WRITE (no response).
     * Payload: UTF-8 JSON command.
     *
     * Supported commands:
     *   { "command": "restart" }
     *   { "command": "wifi_sta", "ssid": "...", "password": "..." }
     *   { "command": "wifi_ap",  "ssid": "...", "password": "..." }
     */
    val ADMIN_CMD_CHAR_UUID: UUID =
        UUID.fromString("4d475743-0502-4e41-5649-474154494f4e")

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

    // ----------------------------------------------------------------
    // RSSI thresholds (dBm) for signal quality indicator
    // ----------------------------------------------------------------

    /** RSSI poll interval when connected (ms) */
    const val RSSI_POLL_INTERVAL_MS = 5_000L

    /** RSSI ≥ this → 5 bars (excellent) */
    const val RSSI_EXCELLENT = -60

    /** RSSI ≥ this → 4 bars (good) */
    const val RSSI_GOOD = -70

    /** RSSI ≥ this → 3 bars (fair) */
    const val RSSI_FAIR = -80

    /** RSSI ≥ this → 2 bars (weak) */
    const val RSSI_POOR = -90

    /** RSSI below [RSSI_2_BARS] → 1 bar (very weak) */
    const val RSSI_VERY_POOR  = -100
}