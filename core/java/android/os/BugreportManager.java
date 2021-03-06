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

package android.os;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.IBinder.DeathRecipient;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Class that provides a privileged API to capture and consume bugreports.
 *
 * @hide
 */
@SystemApi
@SystemService(Context.BUGREPORT_SERVICE)
public class BugreportManager {
    private final Context mContext;
    private final IDumpstate mBinder;

    /** @hide */
    public BugreportManager(@NonNull Context context, IDumpstate binder) {
        mContext = context;
        mBinder = binder;
    }

    /**
     * An interface describing the callback for bugreport progress and status.
     */
    public abstract static class BugreportCallback {
        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = { "BUGREPORT_ERROR_" }, value = {
                BUGREPORT_ERROR_INVALID_INPUT,
                BUGREPORT_ERROR_RUNTIME,
                BUGREPORT_ERROR_USER_DENIED_CONSENT,
                BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT
        })

        /** Possible error codes taking a bugreport can encounter */
        public @interface BugreportErrorCode {}

        /** The input options were invalid */
        public static final int BUGREPORT_ERROR_INVALID_INPUT =
                IDumpstateListener.BUGREPORT_ERROR_INVALID_INPUT;

        /** A runtime error occured */
        public static final int BUGREPORT_ERROR_RUNTIME =
                IDumpstateListener.BUGREPORT_ERROR_RUNTIME_ERROR;

        /** User denied consent to share the bugreport */
        public static final int BUGREPORT_ERROR_USER_DENIED_CONSENT =
                IDumpstateListener.BUGREPORT_ERROR_USER_DENIED_CONSENT;

        /** The request to get user consent timed out. */
        public static final int BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT =
                IDumpstateListener.BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT;

        /**
         * Called when there is a progress update.
         * @param progress the progress in [0.0, 100.0]
         */
        public void onProgress(float progress) {}

        /**
         * Called when taking bugreport resulted in an error.
         *
         * <p>If {@code BUGREPORT_ERROR_USER_DENIED_CONSENT} is passed, then the user did not
         * consent to sharing the bugreport with the calling app.
         *
         * <p>If {@code BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT} is passed, then the consent timed
         * out, but the bugreport could be available in the internal directory of dumpstate for
         * manual retrieval.
         */
        public void onError(@BugreportErrorCode int errorCode) {}

        /**
         * Called when taking bugreport finishes successfully.
         */
        public void onFinished() {}
    }

    /**
     * Starts a bugreport.
     *
     * <p>This starts a bugreport in the background. However the call itself can take several
     * seconds to return in the worst case. {@code callback} will receive progress and status
     * updates.
     *
     * <p>The bugreport artifacts will be copied over to the given file descriptors only if the
     * user consents to sharing with the calling app.
     *
     * @param bugreportFd file to write the bugreport. This should be opened in write-only,
     *     append mode.
     * @param screenshotFd file to write the screenshot, if necessary. This should be opened
     *     in write-only, append mode.
     * @param params options that specify what kind of a bugreport should be taken
     * @param callback callback for progress and status updates
     */
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void startBugreport(@NonNull ParcelFileDescriptor bugreportFd,
            @Nullable ParcelFileDescriptor screenshotFd,
            @NonNull BugreportParams params,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BugreportCallback callback) {
        // TODO(b/111441001): Enforce android.Manifest.permission.DUMP if necessary.
        DumpstateListener dsListener = new DumpstateListener(executor, callback);
        try {
            // Note: mBinder can get callingUid from the binder transaction.
            mBinder.startBugreport(-1 /* callingUid */,
                    mContext.getOpPackageName(),
                    (bugreportFd != null ? bugreportFd.getFileDescriptor() : new FileDescriptor()),
                    (screenshotFd != null
                            ? screenshotFd.getFileDescriptor() : new FileDescriptor()),
                    params.getMode(), dsListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /*
     * Cancels a currently running bugreport.
     */
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void cancelBugreport() {
        try {
            mBinder.cancelBugreport();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final class DumpstateListener extends IDumpstateListener.Stub
            implements DeathRecipient {
        private final Executor mExecutor;
        private final BugreportCallback mCallback;

        DumpstateListener(Executor executor, @Nullable BugreportCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void binderDied() {
            // TODO(b/111441001): implement
        }

        @Override
        public void onProgress(int progress) throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    mCallback.onProgress(progress);
                });
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onError(int errorCode) throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    mCallback.onError(errorCode);
                });
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onFinished() throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    mCallback.onFinished();
                });
            } finally {
                Binder.restoreCallingIdentity(identity);
                // The bugreport has finished. Let's shutdown the service to minimize its footprint.
                cancelBugreport();
            }
        }

        // Old methods; should go away
        @Override
        public void onProgressUpdated(int progress) throws RemoteException {
            // TODO(b/111441001): remove from interface
        }

        @Override
        public void onMaxProgressUpdated(int maxProgress) throws RemoteException {
            // TODO(b/111441001): remove from interface
        }

        @Override
        public void onSectionComplete(String title, int status, int size, int durationMs)
                throws RemoteException {
            // TODO(b/111441001): remove from interface
        }
    }
}
