package com.fonrouge.fullStack.config

import dev.kilua.rpc.RpcServiceManager

/**
 * Centralized registry for all application view configurations.
 *
 * Consolidates the view configuration maps (`ConfigView`, `ConfigViewItem`, `ConfigViewList`)
 * and RPC service managers into a single access point. This improves cohesion and simplifies
 * view lookup by URL.
 */
object ViewRegistry {

    /**
     * Map of general view configurations, indexed by base URL.
     */
    val configViewMap = mutableMapOf<String, ConfigView<*, *>>()

    /**
     * Map of item view configurations, indexed by base URL.
     */
    val configViewItemMap = mutableMapOf<String, ConfigViewItem<*, *, *, *, *>>()

    /**
     * Map of list view configurations, indexed by base URL.
     */
    val configViewListMap = mutableMapOf<String, ConfigViewList<*, *, *, *, *, *>>()

    private var _itemServiceManager: RpcServiceManager<*>? = null
    private var _listServiceManager: RpcServiceManager<*>? = null

    /**
     * RPC service manager for item operations.
     *
     * @throws IllegalStateException if accessed before being initialized.
     */
    var itemServiceManager: RpcServiceManager<*>
        get() = _itemServiceManager
            ?: error("ViewRegistry.itemServiceManager has not been initialized. Set its value before creating ConfigViewItem instances.")
        set(value) {
            _itemServiceManager = value
        }

    /**
     * The list service manager as stored — `null` while unset — for module-internal callers (tests)
     * that must save and restore it exactly; [listServiceManager] cannot express "unset".
     */
    internal var listServiceManagerOrNull: RpcServiceManager<*>?
        get() = _listServiceManager
        set(value) {
            _listServiceManager = value
        }

    /**
     * RPC service manager for list operations.
     *
     * @throws IllegalStateException if accessed before being initialized.
     */
    var listServiceManager: RpcServiceManager<*>
        get() = _listServiceManager
            ?: error("ViewRegistry.listServiceManager has not been initialized. Set its value before creating ConfigViewList instances.")
        set(value) {
            _listServiceManager = value
        }

    /**
     * Finds a view configuration by its base URL, searching across all three maps.
     *
     * @param baseUrl The base URL of the view to find.
     * @return The matching view configuration, or null if not found in any map.
     */
    fun findByUrl(baseUrl: String): ConfigView<*, *>? =
        configViewMap[baseUrl] ?: configViewItemMap[baseUrl] ?: configViewListMap[baseUrl]

    /**
     * Clears all registries and service managers. Useful for testing.
     */
    @Suppress("unused")
    fun reset() {
        configViewMap.clear()
        configViewItemMap.clear()
        configViewListMap.clear()
        _itemServiceManager = null
        _listServiceManager = null
    }
}
