/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.icons.cache

import android.content.ComponentName
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import android.os.UserHandle
import android.util.ArrayMap
import android.util.Log
import android.util.SparseBooleanArray
import androidx.annotation.VisibleForTesting
import com.android.launcher3.icons.cache.BaseIconCache.IconDB
import java.util.ArrayDeque

/** Utility class to handle updating the Icon cache */
class IconCacheUpdateHandler(private val iconCache: BaseIconCache) {

    private val packagesToIgnore = ArrayMap<UserHandle, MutableSet<String>>()
    private val itemsToDelete = SparseBooleanArray()

    private var filterMode = MODE_SET_INVALID_ITEMS

    /** Sets a package to ignore for processing */
    fun addPackagesToIgnore(userHandle: UserHandle, packageName: String) {
        packagesToIgnore.getOrPut(userHandle) { HashSet() }.add(packageName)
    }

    /**
     * Updates the persistent DB, such that only entries corresponding to {@param apps} remain in
     * the DB and are updated.
     *
     * @return The set of packages for which icons have updated.
     */
    fun <T : Any> updateIcons(
        apps: List<T>,
        cachingLogic: CachingLogic<T>,
        onUpdateCallback: OnUpdateCallback,
    ) {
        // Filter the list per user
        val userComponentMap = HashMap<UserHandle, HashMap<ComponentName, T>>()
        apps.forEach { app ->
            val userHandle = cachingLogic.getUser(app)
            var componentMap = userComponentMap.getOrPut(userHandle) { HashMap() }
            componentMap[cachingLogic.getComponent(app)] = app
        }

        for ((key, value) in userComponentMap) {
            updateIconsPerUser(key, value, cachingLogic, onUpdateCallback)
        }

        // From now on, clear every valid item from the global valid map.
        filterMode = MODE_CLEAR_VALID_ITEMS
    }

    /**
     * Updates the persistent DB, such that only entries corresponding to {@param apps} remain in
     * the DB and are updated.
     *
     * @return The set of packages for which icons have updated.
     */
    private fun <T : Any> updateIconsPerUser(
        user: UserHandle,
        componentMap: HashMap<ComponentName, T>,
        cachingLogic: CachingLogic<T>,
        onUpdateCallback: OnUpdateCallback,
    ) {
        var ignorePackages: Set<String> = packagesToIgnore[user] ?: emptySet()
        val userSerial = iconCache.getSerialNumberForUser(user)
        val appsToUpdate = ArrayDeque<T>()

        try {
            iconCache.mIconDb
                .query(
                    arrayOf(
                        IconDB.COLUMN_ROWID,
                        IconDB.COLUMN_COMPONENT,
                        IconDB.COLUMN_FRESHNESS_ID,
                    ),
                    IconDB.COLUMN_USER + " = ? ",
                    arrayOf(userSerial.toString()),
                )
                .use { c ->
                    while (c.moveToNext()) {
                        updateOrDeleteIcon(c, componentMap, ignorePackages, user, cachingLogic)
                            ?.let { appsToUpdate.add(it) }
                    }
                }
        } catch (e: SQLiteException) {
            Log.d(TAG, "Error reading icon cache", e)
            // Continue updating whatever we have read so far
        }

        // Insert remaining apps.
        if (componentMap.isNotEmpty() || !appsToUpdate.isEmpty()) {
            val appsToAdd = ArrayDeque<T>()
            appsToAdd.addAll(componentMap.values)
            SerializedIconUpdateTask(
                    userSerial,
                    user,
                    appsToAdd,
                    appsToUpdate,
                    cachingLogic,
                    onUpdateCallback,
                )
                .scheduleNext()
        }
    }

    /**
     * This method retrieves the component and either adds it to the list of apps to update or adds
     * it to a list of apps to delete from cache later. Returns the individual app if it should be
     * updated, or null if nothing should be updated.
     */
    @VisibleForTesting
    fun <T : Any> updateOrDeleteIcon(
        c: Cursor,
        componentMap: MutableMap<ComponentName, out T>,
        ignorePackages: Set<String>,
        user: UserHandle,
        cachingLogic: CachingLogic<T>,
    ): T? {
        val indexComponent = c.getColumnIndex(IconDB.COLUMN_COMPONENT)
        val indexFreshnessId = c.getColumnIndex(IconDB.COLUMN_FRESHNESS_ID)
        val rowIndex = c.getColumnIndex(IconDB.COLUMN_ROWID)

        val rowId = c.getInt(rowIndex)
        val cn = c.getString(indexComponent)
        val component = ComponentName.unflattenFromString(cn)
        if (component == null) {
            // b/357725795
            Log.e(TAG, "Invalid component name while updating icon cache: $cn")
            itemsToDelete.put(rowId, true)
            return null
        }

        val app = componentMap.remove(component)
        if (app == null) {
            if (!ignorePackages.contains(component.packageName)) {
                if (filterMode == MODE_SET_INVALID_ITEMS) {
                    iconCache.remove(component, user)
                    itemsToDelete.put(rowId, true)
                }
            }
            return null
        }

        val freshnessId = c.getString(indexFreshnessId)
        if (freshnessId == cachingLogic.getFreshnessIdentifier(app, iconCache.iconProvider)) {
            if (filterMode == MODE_CLEAR_VALID_ITEMS) {
                itemsToDelete.put(rowId, false)
            }
            return null
        }

        return app
    }

    /**
     * Commits all updates as part of the update handler to disk. Not more calls should be made to
     * this class after this.
     */
    fun finish() {
        // Commit all deletes
        var deleteCount = 0
        val queryBuilder = StringBuilder().append(IconDB.COLUMN_ROWID).append(" IN (")

        val count = itemsToDelete.size()
        for (i in 0 until count) {
            if (itemsToDelete.valueAt(i)) {
                if (deleteCount > 0) {
                    queryBuilder.append(", ")
                }
                queryBuilder.append(itemsToDelete.keyAt(i))
                deleteCount++
            }
        }
        queryBuilder.append(')')

        if (deleteCount > 0) {
            iconCache.mIconDb.delete(queryBuilder.toString(), null)
        }
    }

    /**
     * A runnable that updates invalid icons and adds missing icons in the DB for the provided
     * LauncherActivityInfo list. Items are updated/added one at a time, so that the worker thread
     * doesn't get blocked.
     */
    private inner class SerializedIconUpdateTask<T : Any>(
        private val userSerial: Long,
        private val userHandle: UserHandle,
        private val appsToAdd: ArrayDeque<T>,
        private val appsToUpdate: ArrayDeque<T>,
        private val cachingLogic: CachingLogic<T>,
        private val onUpdateCallback: OnUpdateCallback,
    ) : Runnable {
        private val updatedPackages = HashSet<String>()

        override fun run() {
            if (appsToUpdate.isNotEmpty()) {
                val app = appsToUpdate.removeLast()
                val pkg = cachingLogic.getComponent(app).packageName

                iconCache.addIconToDBAndMemCache(app, cachingLogic, userSerial)
                updatedPackages.add(pkg)

                if (appsToUpdate.isEmpty() && updatedPackages.isNotEmpty()) {
                    // No more app to update. Notify callback.
                    onUpdateCallback.onPackageIconsUpdated(updatedPackages, userHandle)
                }

                // Let it run one more time.
                scheduleNext()
            } else if (appsToAdd.isNotEmpty()) {
                iconCache.addIconToDBAndMemCache(appsToAdd.removeLast(), cachingLogic, userSerial)

                // Let it run one more time.
                scheduleNext()
            }
        }

        fun scheduleNext() {
            iconCache.workerHandler.postAtTime(
                this,
                iconCache.iconUpdateToken,
                SystemClock.uptimeMillis() + 1,
            )
        }
    }

    interface OnUpdateCallback {
        fun onPackageIconsUpdated(updatedPackages: HashSet<String>, user: UserHandle)
    }

    companion object {
        private const val TAG = "IconCacheUpdateHandler"

        /**
         * In this mode, all invalid icons are marked as to-be-deleted in [.mItemsToDelete]. This
         * mode is used for the first run.
         */
        private const val MODE_SET_INVALID_ITEMS = true

        /**
         * In this mode, any valid icon is removed from [.mItemsToDelete]. This is used for all
         * subsequent runs, which essentially acts as set-union of all valid items.
         */
        private const val MODE_CLEAR_VALID_ITEMS = false
    }
}
