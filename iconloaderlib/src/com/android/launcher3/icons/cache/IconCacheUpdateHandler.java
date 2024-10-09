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
package com.android.launcher3.icons.cache;

import android.content.ComponentName;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseBooleanArray;

import androidx.annotation.VisibleForTesting;

import com.android.launcher3.icons.cache.BaseIconCache.IconDB;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

/**
 * Utility class to handle updating the Icon cache
 */
public class IconCacheUpdateHandler {

    private static final String TAG = "IconCacheUpdateHandler";

    /**
     * In this mode, all invalid icons are marked as to-be-deleted in {@link #mItemsToDelete}.
     * This mode is used for the first run.
     */
    private static final boolean MODE_SET_INVALID_ITEMS = true;

    /**
     * In this mode, any valid icon is removed from {@link #mItemsToDelete}. This is used for all
     * subsequent runs, which essentially acts as set-union of all valid items.
     */
    private static final boolean MODE_CLEAR_VALID_ITEMS = false;

    private final BaseIconCache mIconCache;

    private final ArrayMap<UserHandle, Set<String>> mPackagesToIgnore = new ArrayMap<>();

    private final SparseBooleanArray mItemsToDelete = new SparseBooleanArray();
    private boolean mFilterMode = MODE_SET_INVALID_ITEMS;

    @VisibleForTesting
    public IconCacheUpdateHandler(BaseIconCache cache) {
        mIconCache = cache;
    }

    /**
     * Sets a package to ignore for processing
     */
    public void addPackagesToIgnore(UserHandle userHandle, String packageName) {
        Set<String> packages = mPackagesToIgnore.get(userHandle);
        if (packages == null) {
            packages = new HashSet<>();
            mPackagesToIgnore.put(userHandle, packages);
        }
        packages.add(packageName);
    }

    /**
     * Updates the persistent DB, such that only entries corresponding to {@param apps} remain in
     * the DB and are updated.
     *
     * @return The set of packages for which icons have updated.
     */
    public <T> void updateIcons(List<T> apps, CachingLogic<T> cachingLogic,
            OnUpdateCallback onUpdateCallback) {
        // Filter the list per user
        HashMap<UserHandle, HashMap<ComponentName, T>> userComponentMap = new HashMap<>();
        int count = apps.size();
        for (int i = 0; i < count; i++) {
            T app = apps.get(i);
            UserHandle userHandle = cachingLogic.getUser(app);
            HashMap<ComponentName, T> componentMap = userComponentMap.get(userHandle);
            if (componentMap == null) {
                componentMap = new HashMap<>();
                userComponentMap.put(userHandle, componentMap);
            }
            componentMap.put(cachingLogic.getComponent(app), app);
        }

        for (Entry<UserHandle, HashMap<ComponentName, T>> entry : userComponentMap.entrySet()) {
            updateIconsPerUser(entry.getKey(), entry.getValue(), cachingLogic, onUpdateCallback);
        }

        // From now on, clear every valid item from the global valid map.
        mFilterMode = MODE_CLEAR_VALID_ITEMS;
    }

    /**
     * Updates the persistent DB, such that only entries corresponding to {@param apps} remain in
     * the DB and are updated.
     *
     * @return The set of packages for which icons have updated.
     */
    @SuppressWarnings("unchecked")
    private <T> void updateIconsPerUser(UserHandle user, HashMap<ComponentName, T> componentMap,
            CachingLogic<T> cachingLogic, OnUpdateCallback onUpdateCallback) {
        Set<String> ignorePackages = mPackagesToIgnore.get(user);
        if (ignorePackages == null) {
            ignorePackages = Collections.emptySet();
        }
        long userSerial = mIconCache.getSerialNumberForUser(user);

        ArrayDeque<T> appsToUpdate = new ArrayDeque<>();

        try (Cursor c = mIconCache.mIconDb.query(
                new String[] {
                        IconDB.COLUMN_ROWID, IconDB.COLUMN_COMPONENT, IconDB.COLUMN_FRESHNESS_ID},
                IconDB.COLUMN_USER + " = ? ",
                new String[]{Long.toString(userSerial)})) {

            while (c.moveToNext()) {
                var app = updateOrDeleteIcon(c, componentMap, ignorePackages, user, cachingLogic);
                if (app != null) {
                    appsToUpdate.add(app);
                }
            }
        } catch (SQLiteException e) {
            Log.d(TAG, "Error reading icon cache", e);
            // Continue updating whatever we have read so far
        }

        // Insert remaining apps.
        if (!componentMap.isEmpty() || !appsToUpdate.isEmpty()) {
            ArrayDeque<T> appsToAdd = new ArrayDeque<>();
            appsToAdd.addAll(componentMap.values());
            new SerializedIconUpdateTask(userSerial, user, appsToAdd, appsToUpdate, cachingLogic,
                    onUpdateCallback).scheduleNext();
        }
    }

    /**
     * This method retrieves the component and either adds it to the list of apps to update or
     * adds it to a list of apps to delete from cache later. Returns the individual app if it
     * should be updated, or null if nothing should be updated.
     */
    @VisibleForTesting
    public <T> T updateOrDeleteIcon(Cursor c, Map<ComponentName, ? extends T> componentMap,
            Set<String> ignorePackages, UserHandle user, CachingLogic<T> cachingLogic) {
        final int indexComponent = c.getColumnIndex(IconDB.COLUMN_COMPONENT);
        final int indexFreshnessId = c.getColumnIndex(IconDB.COLUMN_FRESHNESS_ID);
        final int rowIndex = c.getColumnIndex(IconDB.COLUMN_ROWID);

        int rowId = c.getInt(rowIndex);
        String cn = c.getString(indexComponent);
        ComponentName component = ComponentName.unflattenFromString(cn);
        if (component == null) {
            // b/357725795
            Log.e(TAG, "Invalid component name while updating icon cache: " + cn);
            mItemsToDelete.put(rowId, true);
            return null;
        }

        T app = componentMap.remove(component);
        if (app == null) {
            if (!ignorePackages.contains(component.getPackageName())) {
                if (mFilterMode == MODE_SET_INVALID_ITEMS) {
                    mIconCache.remove(component, user);
                    mItemsToDelete.put(rowId, true);
                }
            }
            return null;
        }

        String freshnessId = c.getString(indexFreshnessId);
        if (Objects.equals(freshnessId, cachingLogic.getFreshnessIdentifier(
                app, mIconCache.getIconProvider()))) {
            if (mFilterMode == MODE_CLEAR_VALID_ITEMS) {
                mItemsToDelete.put(rowId, false);
            }
            return null;
        }

        return app;
    }

    /**
     * Commits all updates as part of the update handler to disk. Not more calls should be made
     * to this class after this.
     */
    public void finish() {
        // Commit all deletes
        int deleteCount = 0;
        StringBuilder queryBuilder = new StringBuilder()
                .append(IconDB.COLUMN_ROWID)
                .append(" IN (");

        int count = mItemsToDelete.size();
        for (int i = 0; i < count; i++) {
            if (mItemsToDelete.valueAt(i)) {
                if (deleteCount > 0) {
                    queryBuilder.append(", ");
                }
                queryBuilder.append(mItemsToDelete.keyAt(i));
                deleteCount++;
            }
        }
        queryBuilder.append(')');

        if (deleteCount > 0) {
            mIconCache.mIconDb.delete(queryBuilder.toString(), null);
        }
    }

    /**
     * A runnable that updates invalid icons and adds missing icons in the DB for the provided
     * LauncherActivityInfo list. Items are updated/added one at a time, so that the
     * worker thread doesn't get blocked.
     */
    private class SerializedIconUpdateTask<T> implements Runnable {
        private final long mUserSerial;
        private final UserHandle mUserHandle;
        private final ArrayDeque<T> mAppsToAdd;
        private final ArrayDeque<T> mAppsToUpdate;
        private final CachingLogic<T> mCachingLogic;
        private final HashSet<String> mUpdatedPackages = new HashSet<>();
        private final OnUpdateCallback mOnUpdateCallback;

        SerializedIconUpdateTask(long userSerial, UserHandle userHandle,
                ArrayDeque<T> appsToAdd, ArrayDeque<T> appsToUpdate, CachingLogic<T> cachingLogic,
                OnUpdateCallback onUpdateCallback) {
            mUserHandle = userHandle;
            mUserSerial = userSerial;
            mAppsToAdd = appsToAdd;
            mAppsToUpdate = appsToUpdate;
            mCachingLogic = cachingLogic;
            mOnUpdateCallback = onUpdateCallback;
        }

        @Override
        public void run() {
            if (!mAppsToUpdate.isEmpty()) {
                T app = mAppsToUpdate.removeLast();
                String pkg = mCachingLogic.getComponent(app).getPackageName();

                mIconCache.addIconToDBAndMemCache(app, mCachingLogic, mUserSerial);
                mUpdatedPackages.add(pkg);

                if (mAppsToUpdate.isEmpty() && !mUpdatedPackages.isEmpty()) {
                    // No more app to update. Notify callback.
                    mOnUpdateCallback.onPackageIconsUpdated(mUpdatedPackages, mUserHandle);
                }

                // Let it run one more time.
                scheduleNext();
            } else if (!mAppsToAdd.isEmpty()) {
                T app = mAppsToAdd.removeLast();
                mIconCache.addIconToDBAndMemCache(app, mCachingLogic, mUserSerial);

                // Let it run one more time.
                scheduleNext();
            }
        }

        public void scheduleNext() {
            mIconCache.workerHandler.postAtTime(this,
                    mIconCache.iconUpdateToken, SystemClock.uptimeMillis() + 1);
        }
    }

    public interface OnUpdateCallback {

        void onPackageIconsUpdated(HashSet<String> updatedPackages, UserHandle user);
    }
}
