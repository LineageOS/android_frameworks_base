/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.util;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import dalvik.annotation.optimization.FastNative;
import dalvik.system.CloseGuard;

import libcore.io.IoUtils;

import java.io.Closeable;
import java.io.IOException;
import java.util.UUID;

/**
 * This class is an array of integers that is backed by shared memory.
 * It is useful for efficiently sharing state between processes. The
 * write and read operations are guaranteed to not result in read/
 * write memory tear, i.e. they are atomic. However, multiple read/
 * write operations are <strong>not</strong> synchronized between
 * each other.
 * <p>
 * The data structure is designed to have one owner process that can
 * read/write. There may be multiple client processes that can only read.
 * The owner process is the process that created the array. The shared
 * memory is pinned (not reclaimed by the system) until the owning process
 * dies or the data structure is closed. This class is <strong>not</strong>
 * thread safe. You should not interact with an instance of this class
 * once it is closed. If you pass back to the owner process an instance
 * it will be read only even in the owning process.
 * </p>
 *
 * @hide
 */
// TODO(b/498720726): Refactor this class to be implemented entirely in terms of SharedMemory,
// instead of manually managing the FD and ashmem regions using JNI.
public final class MemoryIntArray implements Parcelable, Closeable {
    private static final String TAG = "MemoryIntArray";

    private static final int MAX_SIZE = 1024;

    private final CloseGuard mCloseGuard = CloseGuard.get();

    private final boolean mIsOwner;
    private final long mMemoryAddr;
    private final int mSize;
    private int mFd = -1;

    /**
     * Creates a new instance.
     *
     * @param size The size of the array in terms of integer slots. Cannot be
     *     more than {@link #getMaxSize()}.
     * @throws IOException If an error occurs while accessing the shared memory.
     */
    public MemoryIntArray(int size) throws IOException {
        if (size > MAX_SIZE) {
            throw new IllegalArgumentException("Max size is " + MAX_SIZE);
        }
        mIsOwner = true;
        final String name = UUID.randomUUID().toString();
        mFd = nativeCreate(name, size);
        // Note that we use the effective size after allocation, rather than the provided size,
        // preserving compat with the original behavior. In practice these should be equivalent.
        mSize = nativeSize(mFd);
        mMemoryAddr = nativeOpen(mFd, mIsOwner, mSize);
        mCloseGuard.open("MemoryIntArray.close");
    }

    private MemoryIntArray(Parcel parcel) throws IOException {
        mIsOwner = false;
        ParcelFileDescriptor pfd = parcel.readParcelable(null, android.os.ParcelFileDescriptor.class);
        if (pfd == null) {
            throw new IOException("No backing file descriptor");
        }
        mFd = pfd.detachFd();
        mSize = nativeSize(mFd);
        mMemoryAddr = nativeOpen(mFd, mIsOwner, mSize);
        mCloseGuard.open("MemoryIntArray.close");
    }

    /**
     * @return Gets whether this array is mutable.
     */
    public boolean isWritable() {
        enforceNotClosed();
        return mIsOwner;
    }

    /**
     * Gets the value at a given index.
     *
     * @param index The index.
     * @return The value at this index.
     * @throws IOException If an error occurs while accessing the shared memory.
     */
    // TODO(b/441345470): Remove the checked IOException after updating all callers, as we
    // no longer throw from native.
    public int get(int index) throws IOException {
        enforceNotClosed();
        enforceValidIndex(index);
        return nativeGet(mMemoryAddr, index);
    }

    /**
     * Sets the value at a given index. This method can be called only if
     * {@link #isWritable()} returns true which means your process is the
     * owner.
     *
     * @param index The index.
     * @param value The value to set.
     * @throws IOException If an error occurs while accessing the shared memory.
     */
    // TODO(b/441345470): Remove the checked IOException after updating all callers, as we
    // no longer throw from native.
    public void set(int index, int value) throws IOException {
        enforceNotClosed();
        enforceWritable();
        enforceValidIndex(index);
        nativeSet(mMemoryAddr, index, value);
    }

    /**
     * @return Gets the array size.
     */
    public int size() {
        enforceNotClosed();
        return mSize;
    }

    /**
     * Closes the array releasing resources.
     *
     * @throws IOException If an error occurs while accessing the shared memory.
     */
    @Override
    public void close() throws IOException {
        if (!isClosed()) {
            nativeClose(mFd, mMemoryAddr, mSize);
            mFd = -1;
            mCloseGuard.close();
        }
    }

    /**
     * @return Whether this array is closed and shouldn't be used.
     */
    public boolean isClosed() {
        return mFd == -1;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            IoUtils.closeQuietly(this);
        } finally {
            super.finalize();
        }
    }

    @Override
    public int describeContents() {
        return CONTENTS_FILE_DESCRIPTOR;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromFd(mFd)) {
            parcel.writeParcelable(pfd, flags);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        MemoryIntArray other = (MemoryIntArray) obj;
        return mFd == other.mFd;
    }

    @Override
    public int hashCode() {
        return mFd;
    }

    private void enforceNotClosed() {
        if (isClosed()) {
            throw new IllegalStateException("cannot interact with a closed instance");
        }
    }

    private void enforceValidIndex(int index) {
        if (index < 0 || index > mSize - 1) {
            throw new IndexOutOfBoundsException(
                    index + " not between 0 and " + (mSize - 1));
        }
    }

    private void enforceWritable() {
        if (!isWritable()) {
            throw new UnsupportedOperationException("array is not writable");
        }
    }

    private native int nativeCreate(String name, int size);
    // The same explicit size should always be passed to nativeOpen() and nativeClose() to ensure
    // consistency between mmap() and munmap().
    private native long nativeOpen(int fd, boolean owner, int size);
    private native void nativeClose(int fd, long memoryAddr, int size);
    @FastNative
    private native int nativeGet(long memoryAddr, int index);
    @FastNative
    private native void nativeSet(long memoryAddr, int index, int value);
    private native int nativeSize(int fd);

    /**
     * @return The max array size.
     */
    public static int getMaxSize() {
        return MAX_SIZE;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<MemoryIntArray> CREATOR =
            new Parcelable.Creator<MemoryIntArray>() {
        @Override
        public MemoryIntArray createFromParcel(Parcel parcel) {
            try {
                return new MemoryIntArray(parcel);
            } catch (IOException ioe) {
                throw new IllegalArgumentException("Error unparceling MemoryIntArray");
            }
        }

        @Override
        public MemoryIntArray[] newArray(int size) {
            return new MemoryIntArray[size];
        }
    };
}
