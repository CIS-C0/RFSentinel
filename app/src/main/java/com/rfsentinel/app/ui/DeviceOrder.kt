package com.rfsentinel.app.ui

import android.content.Context
import android.view.Menu
import android.view.MenuItem
import com.rfsentinel.app.detect.Advert
import com.rfsentinel.app.detect.DeviceIntel
import com.rfsentinel.app.util.Prefs
import com.rfsentinel.app.util.ProximityUtil

/**
 * How the device lists are sorted and grouped (the ⋮ menu's Sort by / Group by), shared by the main
 * screen and saved snapshots so one choice applies to both. Alerts always stay on top.
 */
object DeviceOrder {
    enum class Sort(val label: String) {
        ALERTS("Alerts, then closest"),
        SIGNAL("Signal strength"),
        NAME("Name"),
        RECENT("Recently seen"),
        FIRST_SEEN("First seen"),
        TYPE("Type & maker")
    }

    enum class Group(val label: String) {
        NONE("None"),
        CATEGORY("Category"),
        RADIO("Radio"),
        MAKER("Maker"),
        DISTANCE("Distance")
    }

    /** What sorting and grouping look at, for a device heard now or one saved. */
    class Item<T>(
        val value: T,
        val seen: DeviceFilter.Seen,
        val title: String,
        val vendor: String?,
        val rssi: Int,
        val firstSeen: Long,
        val lastSeen: Long
    )

    /** A group of the list: its header (null when not grouped) and its devices, in order. */
    class Section<T>(val header: String?, val items: List<T>)

    fun sort(context: Context) = Sort.entries.firstOrNull { it.name == Prefs.listSort(context) } ?: Sort.ALERTS
    fun group(context: Context) = Group.entries.firstOrNull { it.name == Prefs.listGroup(context) } ?: Group.NONE

    /** Category groups, in the filter chips' spirit: what matters most first. */
    private val CATEGORY_ORDER = listOf(
        DeviceFilter.TRACKERS, DeviceFilter.DRONES, DeviceFilter.CAMERAS, DeviceFilter.VEHICLES, DeviceFilter.PHONES,
        DeviceFilter.AUDIO, DeviceFilter.ROUTERS, DeviceFilter.HOTSPOTS, DeviceFilter.TVS, DeviceFilter.SMART_HOME
    )
    /** The group of flagged devices, first when grouped. */
    const val ALERTS = "Alerts"
    private const val OTHER = "Other"
    private const val UNKNOWN_MAKER = "Unknown maker"
    private val BANDS = listOf("Near", "Nearby", "Far")

    /**
     * Sorts and groups [items]. [alertsOrder] is the list's own order for Sort.ALERTS (the main screen's:
     * flagged, following, confidence, closeness). Flagged devices come first whatever the choice, in an
     * "Alerts" group of their own when grouped.
     */
    fun <T> arrange(items: List<Item<T>>, sort: Sort, group: Group, alertsOrder: Comparator<Item<T>>): List<Section<T>> {
        val order: Comparator<Item<T>> = compareByDescending<Item<T>> { it.seen.flagged }.then(
            when (sort) {
                Sort.ALERTS -> alertsOrder
                Sort.SIGNAL -> compareByDescending { it.rssi }
                Sort.NAME -> compareBy<Item<T>> { it.seen.name.isNullOrBlank() }.thenBy { it.title.lowercase() }
                Sort.RECENT -> compareByDescending { it.lastSeen }
                Sort.FIRST_SEEN -> compareByDescending { it.firstSeen }
                Sort.TYPE -> compareBy<Item<T>> { it.seen.type.lowercase() }.thenBy { maker(it).lowercase() }
            }
        )
        val sorted = items.sortedWith(order)
        if (group == Group.NONE) return listOf(Section(null, sorted.map { it.value }))
        val (alerts, rest) = sorted.partition { it.seen.flagged }
        val groups = rest.groupBy { key(it, group) }
        val keys = when (group) {
            Group.CATEGORY -> CATEGORY_ORDER.map { it.label }.filter { it in groups } + listOfNotNull(OTHER.takeIf { it in groups })
            Group.RADIO -> listOf("Bluetooth + WiFi", "WiFi", "Bluetooth", OTHER).filter { it in groups }
            Group.DISTANCE -> BANDS.filter { it in groups }
            // Most common makers first, unknown last.
            else -> groups.keys.sortedWith(compareBy<String> { it == UNKNOWN_MAKER }.thenByDescending { groups[it]!!.size }.thenBy { it.lowercase() })
        }
        return listOfNotNull(alerts.takeIf { it.isNotEmpty() }?.let { Section(ALERTS, it.map { a -> a.value }) }) +
            keys.map { k -> Section(k, groups.getValue(k).map { it.value }) }
    }

    private fun <T> key(i: Item<T>, group: Group): String = when (group) {
        Group.CATEGORY -> CATEGORY_ORDER.firstOrNull { it.matches(i.seen) }?.label ?: OTHER
        Group.RADIO -> {
            val ble = Advert.Source.BLE in i.seen.sources
            val wifi = Advert.Source.WIFI in i.seen.sources
            when { ble && wifi -> "Bluetooth + WiFi"; wifi -> "WiFi"; ble -> "Bluetooth"; else -> OTHER }
        }
        Group.MAKER -> maker(i)
        Group.DISTANCE -> ProximityUtil.band(i.rssi)
        Group.NONE -> ""
    }

    private fun maker(i: Item<*>) = i.vendor?.takeIf { it.isNotBlank() }?.let { DeviceIntel.shortVendor(it) } ?: UNKNOWN_MAKER

    /** "Trackers · 3": the header of a group. */
    fun headerLabel(s: Section<*>) = "${s.header} · ${s.items.size}"

    // The ⋮ menu: Sort by and Group by, each a list to pick one from.
    private const val SORT_GROUP = 7100
    private const val GROUP_GROUP = 7200

    fun addMenu(menu: Menu, context: Context, order: Int = Menu.NONE) {
        val sortMenu = menu.addSubMenu(Menu.NONE, SORT_GROUP, order, "Sort by")
        val sort = sort(context)
        Sort.entries.forEach { s -> sortMenu.add(SORT_GROUP, SORT_GROUP + 1 + s.ordinal, s.ordinal, s.label).isChecked = s == sort }
        sortMenu.setGroupCheckable(SORT_GROUP, true, true)
        val groupMenu = menu.addSubMenu(Menu.NONE, GROUP_GROUP, order, "Group by")
        val group = group(context)
        Group.entries.forEach { g -> groupMenu.add(GROUP_GROUP, GROUP_GROUP + 1 + g.ordinal, g.ordinal, g.label).isChecked = g == group }
        groupMenu.setGroupCheckable(GROUP_GROUP, true, true)
    }

    /** Handles a Sort by / Group by pick; true when it was one. */
    fun onMenuItem(item: MenuItem, context: Context): Boolean {
        val sort = Sort.entries.firstOrNull { item.itemId == SORT_GROUP + 1 + it.ordinal }
        val group = Group.entries.firstOrNull { item.itemId == GROUP_GROUP + 1 + it.ordinal }
        when {
            sort != null -> Prefs.setListSort(context, sort.name)
            group != null -> Prefs.setListGroup(context, group.name)
            else -> return false
        }
        item.isChecked = true
        return true
    }
}
