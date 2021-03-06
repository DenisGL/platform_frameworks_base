/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server;

import android.annotation.NonNull;
import android.net.INetd;
import android.net.INetdUnsolicitedEventListener;
import android.net.LinkAddress;
import android.os.Handler;
import android.os.RemoteException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A class for reporting network events to clients.
 *
 * Implements INetdUnsolicitedEventListener and registers with netd, and relays those events to
 * all INetworkManagementEventObserver objects that have registered with it.
 */
public class NetworkObserverRegistry extends INetdUnsolicitedEventListener.Stub {

    /**
     * Constructs a new NetworkObserverRegistry.
     *
     * <p>Only one registry should be used per process since netd will silently ignore multiple
     * registrations from the same process.
     */
    NetworkObserverRegistry() {}

    /**
     * Start listening for Netd events.
     *
     * <p>This should be called before allowing any observer to be registered.
     */
    void register(@NonNull INetd netd) throws RemoteException {
        netd.registerUnsolicitedEventListener(this);
    }

    private final ConcurrentHashMap<NetworkObserver, Handler> mObservers =
            new ConcurrentHashMap<>();

    /**
     * Registers the specified observer and start sending callbacks to it.
     * This method may be called on any thread.
     */
    public void registerObserver(@NonNull NetworkObserver observer, @NonNull Handler handler) {
        mObservers.put(observer, handler);
    }

    /**
     * Unregisters the specified observer and stop sending callbacks to it.
     * This method may be called on any thread.
     */
    public void unregisterObserver(@NonNull NetworkObserver observer) {
        mObservers.remove(observer);
    }

    @FunctionalInterface
    private interface NetworkObserverEventCallback {
        void sendCallback(NetworkObserver o);
    }

    private void invokeForAllObservers(@NonNull final NetworkObserverEventCallback callback) {
        // ConcurrentHashMap#entrySet is weakly consistent: observers that were in the map before
        // creation will be processed, those added during traversal may or may not.
        for (Map.Entry<NetworkObserver, Handler> entry : mObservers.entrySet()) {
            final NetworkObserver observer = entry.getKey();
            entry.getValue().post(() -> callback.sendCallback(observer));
        }
    }

    @Override
    public void onInterfaceClassActivityChanged(boolean isActive,
            int label, long timestamp, int uid) {
        invokeForAllObservers(o -> o.onInterfaceClassActivityChanged(
                isActive, label, timestamp, uid));
    }

    /**
     * Notify our observers of a limit reached.
     */
    @Override
    public void onQuotaLimitReached(String alertName, String ifName) {
        invokeForAllObservers(o -> o.onQuotaLimitReached(alertName, ifName));
    }

    @Override
    public void onInterfaceDnsServerInfo(String ifName, long lifetime, String[] servers) {
        invokeForAllObservers(o -> o.onInterfaceDnsServerInfo(ifName, lifetime, servers));
    }

    @Override
    public void onInterfaceAddressUpdated(String addr, String ifName, int flags, int scope) {
        final LinkAddress address = new LinkAddress(addr, flags, scope);
        invokeForAllObservers(o -> o.onInterfaceAddressUpdated(address, ifName));
    }

    @Override
    public void onInterfaceAddressRemoved(String addr,
            String ifName, int flags, int scope) {
        final LinkAddress address = new LinkAddress(addr, flags, scope);
        invokeForAllObservers(o -> o.onInterfaceAddressRemoved(address, ifName));
    }

    @Override
    public void onInterfaceAdded(String ifName) {
        invokeForAllObservers(o -> o.onInterfaceAdded(ifName));
    }

    @Override
    public void onInterfaceRemoved(String ifName) {
        invokeForAllObservers(o -> o.onInterfaceRemoved(ifName));
    }

    @Override
    public void onInterfaceChanged(String ifName, boolean up) {
        invokeForAllObservers(o -> o.onInterfaceChanged(ifName, up));
    }

    @Override
    public void onInterfaceLinkStateChanged(String ifName, boolean up) {
        invokeForAllObservers(o -> o.onInterfaceLinkStateChanged(ifName, up));
    }

    @Override
    public void onRouteChanged(boolean updated, String route, String gateway, String ifName) {
        if (updated) {
            invokeForAllObservers(o -> o.onRouteUpdated(route, gateway, ifName));
        } else {
            invokeForAllObservers(o -> o.onRouteRemoved(route, gateway, ifName));
        }
    }

    @Override
    public void onStrictCleartextDetected(int uid, String hex) {}
}
