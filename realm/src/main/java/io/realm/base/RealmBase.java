/*
 * Copyright 2015 Realm Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.realm.base;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.Closeable;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.realm.BuildConfig;
import io.realm.RealmChangeListener;
import io.realm.RealmConfiguration;
import io.realm.internal.RealmProxyMediator;
import io.realm.internal.SharedGroup;
import io.realm.internal.SharedGroupManager;
import io.realm.internal.android.DebugAndroidLogger;
import io.realm.internal.android.ReleaseAndroidLogger;
import io.realm.internal.log.RealmLog;

/**
 * Base class for all Realm instances.
 *
 * @see io.realm.Realm
 */
public abstract class RealmBase implements Closeable {

    private static final int REALM_CHANGED = 14930352; // Hopefully it won't clash with other message IDs.
    private static final String INCORRECT_THREAD_CLOSE_MESSAGE = "Realm access from incorrect thread. Realm instance can only be closed on the thread it was created.";
    private static final String INCORRECT_THREAD_MESSAGE = "Realm access from incorrect thread. Realm objects can only be accessed on the thread they were created.";
    private static final String CLOSED_REALM_MESSAGE = "This Realm instance has already been closed, making it unusable.";
    private static final String DIFFERENT_KEY_MESSAGE = "Wrong key used to decrypt Realm.";

    // Map between all Realm file paths and all known configurations pointing to that file.
    private static final Map<String, List<RealmConfiguration>> globalPathConfigurationCache =
            new HashMap<String, List<RealmConfiguration>>();

    // Reference count for how many open Realm instances there currently are on this thread.
    protected static final ThreadLocal<Map<RealmConfiguration, Integer>> referenceCount =
            new ThreadLocal<Map<RealmConfiguration,Integer>>() {
                @Override
                protected Map<RealmConfiguration, Integer> initialValue() {
                    return new HashMap<RealmConfiguration, Integer>();
                }
            };

    // Map between a Handler and the canonical path to a Realm file
    public static final Map<Handler, String> handlers = new ConcurrentHashMap<Handler, String>();

    protected final List<WeakReference<RealmChangeListener>> changeListeners =
            new CopyOnWriteArrayList<WeakReference<RealmChangeListener>>();

    protected long threadId;
    protected RealmConfiguration configuration;
    protected SharedGroupManager sharedGroup;
    protected boolean autoRefresh;
    protected Handler handler;

    static {
        RealmLog.add(BuildConfig.DEBUG ? new DebugAndroidLogger() : new ReleaseAndroidLogger());
    }

    protected RealmBase(RealmConfiguration configuration, boolean autoRefresh) {
        this.threadId = Thread.currentThread().getId();
        this.configuration = configuration;
        this.sharedGroup = SharedGroupManager.getInstance(threadId, configuration);
        setAutoRefresh(autoRefresh);
    }

    protected void setAutoRefresh(boolean autoRefresh) {
        if (autoRefresh && Looper.myLooper() == null) {
            throw new IllegalStateException("Cannot set auto-refresh in a Thread without a Looper");
        }

        if (autoRefresh && !this.autoRefresh) { // Switch it on
            handler = new Handler(new RealmCallback());
            handlers.put(handler, configuration.getPath());
        } else if (!autoRefresh && this.autoRefresh && handler != null) { // Switch it off
            removeHandler(handler);
        }
        this.autoRefresh = autoRefresh;
    }

    protected boolean isAutoRefresh() {
        return autoRefresh;
    }

    /**
     * Add a change listener to the Realm
     *
     * @param listener the change listener
     * @see RealmChangeListener
     */
    public void addChangeListener(RealmChangeListener listener) {
        checkIfValid();
        for (WeakReference<RealmChangeListener> ref : changeListeners) {
            if (ref.get() == listener) {
                // It has already been added before
                return;
            }
        }

        changeListeners.add(new WeakReference<RealmChangeListener>(listener));
    }

    /**
     * Remove the specified change listener
     *
     * @param listener the change listener to be removed
     * @see io.realm.RealmChangeListener
     */
    public void removeChangeListener(RealmChangeListener listener) {
        checkIfValid();
        WeakReference<RealmChangeListener> weakRefToRemove = null;
        for (WeakReference<RealmChangeListener> weakRef : changeListeners) {
            if (listener == weakRef.get()) {
                weakRefToRemove = weakRef;
                // There won't be duplicated entries, checking is done when adding
                break;
            }
        }
        if (weakRefToRemove != null) {
            changeListeners.remove(weakRefToRemove);
        }
    }

    /**
     * Remove all user-defined change listeners
     *
     * @see io.realm.RealmChangeListener
     */
    public void removeAllChangeListeners() {
        checkIfValid();
        changeListeners.clear();
    }

    private void removeHandler(Handler handler) {
        handler.removeCallbacksAndMessages(null);
        handlers.remove(handler);
    }

    private void sendNotifications() {
        Iterator<WeakReference<RealmChangeListener>> iterator = changeListeners.iterator();
        List<WeakReference<RealmChangeListener>> toRemoveList = null;
        while (iterator.hasNext()) {
            WeakReference<RealmChangeListener> weakRef = iterator.next();
            RealmChangeListener listener = weakRef.get();
            if (listener == null) {
                if (toRemoveList == null) {
                    toRemoveList = new ArrayList<WeakReference<RealmChangeListener>>(changeListeners.size());
                }
                toRemoveList.add(weakRef);
            } else {
                listener.onChange();
            }
        }
        if (toRemoveList != null) {
            changeListeners.removeAll(toRemoveList);
        }
    }

    /**
     * Closes a single instance of a Realm.
     * @return {@code true} if it was the last instance close, {@code false} otherwise.
     */
    protected boolean closeInstance(RealmConfiguration configuration) {
        if (this.threadId != Thread.currentThread().getId()) {
            throw new IllegalStateException(INCORRECT_THREAD_CLOSE_MESSAGE);
        }

        boolean wasLastInstance = false;
        Map<RealmConfiguration, Integer> localRefCount = referenceCount.get();
        String canonicalPath = configuration.getPath();
        Integer references = localRefCount.get(configuration);
        if (references == null) {
            references = 0;
        }

        if (references == 1) {
            wasLastInstance = true;
            sharedGroup.close();
            sharedGroup = null;
            globalPathConfigurationCache.get(canonicalPath).remove(configuration);
        }

        int refCount = references - 1;
        if (refCount < 0) {
            RealmLog.w("Calling close() on a Realm that is already closed: " + canonicalPath);
        }
        localRefCount.put(configuration, Math.max(0, refCount));

        if (handler != null && refCount <= 0) {
            removeHandler(handler);
        }

        return wasLastInstance;
    }

    /**
     * Write a compacted copy of the Realm to the given destination File.
     * <p>
     * The destination file cannot already exist.
     * <p>
     * Note that if this is called from within a write transaction it writes the
     * current data, and not the data as it was when the last write transaction was committed.
     *
     * @param destination File to save the Realm to
     * @throws java.io.IOException if any write operation fails
     */
    public void writeCopyTo(File destination) throws java.io.IOException {
        writeEncryptedCopyTo(destination, null);
    }

    /**
     * Write a compacted and encrypted copy of the Realm to the given destination File.
     * <p>
     * The destination file cannot already exist.
     * <p>
     * Note that if this is called from within a write transaction it writes the
     * current data, and not the data as it was when the last write transaction was committed.
     * <p>
     * @param destination File to save the Realm to
     * @throws java.io.IOException if any write operation fails
     */
    public void writeEncryptedCopyTo(File destination, byte[] key) throws java.io.IOException {
        if (destination == null) {
            throw new IllegalArgumentException("The destination argument cannot be null");
        }
        checkIfValid();
        sharedGroup.copyToFile(destination, key);
    }

    /**
     * Refresh the Realm instance and all the RealmResults and RealmObjects instances coming from it
     */
    public void refresh() {
        checkIfValid();
        sharedGroup.advanceRead();
    }

    /**
     * Starts a write transaction, this must be closed with {@link io.realm.Realm#commitTransaction()}
     * or aborted by {@link io.realm.Realm#cancelTransaction()}. Write transactions are used to
     * atomically create, update and delete objects within a realm.
     * <br>
     * Before beginning the write transaction, {@link io.realm.Realm#beginTransaction()} updates the
     * realm in the case of pending updates from other threads.
     * <br>
     * Notice: it is not possible to nest write transactions. If you start a write
     * transaction within a write transaction an exception is thrown.
     * <br>
     * @throws java.lang.IllegalStateException If already in a write transaction or incorrect thread.
     *
     */
    public void beginTransaction() {
        checkIfValid();
        sharedGroup.promoteToWrite();
    }

    /**
     * All changes since {@link io.realm.Realm#beginTransaction()} are persisted to disk and the
     * Realm reverts back to being read-only. An event is sent to notify all other realm instances
     * that a change has occurred. When the event is received, the other Realms will get their
     * objects and {@link io.realm.RealmResults} updated to reflect
     * the changes from this commit.
     *
     * @throws java.lang.IllegalStateException If the write transaction is in an invalid state or incorrect thread.
     */
    public void commitTransaction() {
        checkIfValid();
        sharedGroup.commitAndContinueAsRead();

        for (Map.Entry<Handler, String> handlerIntegerEntry : handlers.entrySet()) {
            Handler handler = handlerIntegerEntry.getKey();
            String realmPath = handlerIntegerEntry.getValue();

            // Notify at once on thread doing the commit
            if (handler.equals(this.handler)) {
                sendNotifications();
                continue;
            }

            // For all other threads, use the Handler
            if (
                    realmPath.equals(configuration.getPath())    // It's the right realm
                    && !handler.hasMessages(REALM_CHANGED)       // The right message
                    && handler.getLooper().getThread().isAlive() // The receiving thread is alive
                    ) {
                handler.sendEmptyMessage(REALM_CHANGED);
            }
        }
    }

    /**
     * Revert all writes (created, updated, or deleted objects) made in the current write
     * transaction and end the transaction.
     * <br>
     * The Realm reverts back to read-only.
     * <br>
     * Calling this when not in a write transaction will throw an exception.
     *
     * @throws java.lang.IllegalStateException    If the write transaction is an invalid state,
     *                                             not in a write transaction or incorrect thread.
     */
    public void cancelTransaction() {
        checkIfValid();
        sharedGroup.rollbackAndContinueAsRead();
    }

    /**
     * Checks if a Realm's underlying resources are still available or not getting accessed from
     * the wrong thread.
     */
    protected void checkIfValid() {
        // Check if the Realm instance has been closed
        if (sharedGroup != null && !sharedGroup.isOpen()) {
            throw new IllegalStateException(RealmBase.CLOSED_REALM_MESSAGE);
        }

        // Check if we are in the right thread
        if (threadId != Thread.currentThread().getId()) {
            throw new IllegalStateException(RealmBase.INCORRECT_THREAD_MESSAGE);
        }
    }

    /**
     * Returns the canonical path to where this Realm is persisted on disk.
     *
     * @return The canonical path to the Realm file.
     * @see File#getCanonicalPath()
     */
    public String getPath() {
        return configuration.getPath();
    }

    /**
     * Returns the {@link RealmConfiguration} for this Realm.
     * @return {@link RealmConfiguration} for this Realm.
     */
    public RealmConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * Make sure that the new configuration doesn't clash with any existing configurations for the
     * Realm.
     *
     * @throws IllegalArgumentException If the new configuration isn't valid.
     */
    protected static void validateAgainstExistingConfigurations(RealmConfiguration newConfiguration) {

        // Ensure cache state
        String realmPath = newConfiguration.getPath();
        List<RealmConfiguration> pathConfigurationCache = globalPathConfigurationCache.get(realmPath);
        if (pathConfigurationCache == null) {
            pathConfigurationCache = new CopyOnWriteArrayList<RealmConfiguration>();
            globalPathConfigurationCache.put(realmPath, pathConfigurationCache);
        }

        if (pathConfigurationCache.size() > 0) {

            // For the current restrictions, it is enough to just check one of the existing configurations.
            RealmConfiguration cachedConfiguration = pathConfigurationCache.get(0);

            // Check that encryption keys aren't different
            if (!Arrays.equals(cachedConfiguration.getEncryptionKey(), newConfiguration.getEncryptionKey())) {
                throw new IllegalArgumentException(DIFFERENT_KEY_MESSAGE);
            }

            // Check schema versions are the same
            if (cachedConfiguration.getSchemaVersion() != newConfiguration.getSchemaVersion()) {
                throw new IllegalArgumentException(String.format("Configurations cannot have different schema versions " +
                                "if used to open the same file. %d vs. %d", cachedConfiguration.getSchemaVersion(),
                        newConfiguration.getSchemaVersion()));
            }

            // Check that schema is the same
            RealmProxyMediator cachedSchema = cachedConfiguration.getSchemaMediator();
            RealmProxyMediator schema = newConfiguration.getSchemaMediator();
            if (!cachedSchema.equals(schema)) {
                throw new IllegalArgumentException("Two configurations with different schemas are trying to open " +
                        "the same Realm file. Their schema must be the same: " + newConfiguration.getPath());
            }

            // Check if the durability is the same
            SharedGroup.Durability cachedDurability = cachedConfiguration.getDurability();
            SharedGroup.Durability newDurability = newConfiguration.getDurability();
            if (!cachedDurability.equals(newDurability)) {
                throw new IllegalArgumentException("A Realm cannot be both in-memory and persisted. Two conflicting " +
                        "configurations pointing to " + newConfiguration.getPath() + " are being used.");
            }
        }

        // The new configuration doesn't violate existing configurations. Cache it.
        pathConfigurationCache.add(newConfiguration);
    }

    private class RealmCallback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message message) {
            if (message.what == REALM_CHANGED) {
                sharedGroup.advanceRead();
                sendNotifications();
            }
            return true;
        }
    }
}
