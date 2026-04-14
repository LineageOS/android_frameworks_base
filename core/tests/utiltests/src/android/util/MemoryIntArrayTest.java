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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.system.Os;
import android.system.OsConstants;

import androidx.test.runner.AndroidJUnit4;

import libcore.io.IoUtils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@DisabledOnRavenwood(blockedBy = MemoryIntArray.class)
public class MemoryIntArrayTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    static {
        if (!RavenwoodRule.isOnRavenwood()) {
            System.loadLibrary("cutils");
            System.loadLibrary("memoryintarraytest");
        }
    }

    @Test
    public void testSize() throws Exception {
        MemoryIntArray array = null;
        try {
            array = new MemoryIntArray(3);
            assertEquals("size must be three", 3, array.size());
        } finally {
            IoUtils.closeQuietly(array);
        }
    }

    @Test
    public void testGetSet() throws Exception {
        MemoryIntArray array = null;
        try {
            array = new MemoryIntArray(3);

            array.set(0, 1);
            array.set(1, 2);
            array.set(2, 3);

            assertEquals("First element should be 1", 1, array.get(0));
            assertEquals("First element should be 2", 2, array.get(1));
            assertEquals("First element should be 3", 3, array.get(2));
        } finally {
            IoUtils.closeQuietly(array);
        }
    }

    @Test
    public void testWritable() throws Exception {
        MemoryIntArray array = null;
        try {
            array = new MemoryIntArray(3);
            assertTrue("Must be mutable", array.isWritable());
        } finally {
            IoUtils.closeQuietly(array);
        }
    }

    @Test
    public void testClose() throws Exception {
        MemoryIntArray array = null;
        try {
            array = new MemoryIntArray(3);
            array.close();
            assertTrue("Must be closed", array.isClosed());
        } finally {
            if (array != null && !array.isClosed()) {
                IoUtils.closeQuietly(array);
            }
        }
    }

    @Test
    public void testMarshalledGetSet() throws Exception {
        MemoryIntArray firstArray = null;
        MemoryIntArray secondArray = null;
        try {
            firstArray = new MemoryIntArray(3);

            firstArray.set(0, 1);
            firstArray.set(1, 2);
            firstArray.set(2, 3);

            Parcel parcel = Parcel.obtain();
            parcel.writeParcelable(firstArray, 0);
            parcel.setDataPosition(0);
            secondArray = parcel.readParcelable(null);
            parcel.recycle();

            assertNotNull("Should marshall file descriptor", secondArray);
            assertEquals("Marshalled size must be three", 3, secondArray.size());
            assertEquals("First element should be 1", 1, secondArray.get(0));
            assertEquals("First element should be 2", 2, secondArray.get(1));
            assertEquals("First element should be 3", 3, secondArray.get(2));
        } finally {
            IoUtils.closeQuietly(firstArray);
            IoUtils.closeQuietly(secondArray);
        }
    }

    @Test
    public void testInteractOnceClosed() throws Exception {
        MemoryIntArray array = null;
        try {
            array = new MemoryIntArray(3);
            array.close();

            array.close();

            try {
                array.size();
                fail("Cannot interact with a closed instance");
            } catch (IllegalStateException e) {
                /* expected */
            }

            try {
                array.get(0);
                fail("Cannot interact with a closed instance");
            } catch (IllegalStateException e) {
                /* expected */
            }

            try {
                array.set(0, 1);
                fail("Cannot interact with a closed instance");
            } catch (IllegalStateException e) {
                /* expected */
            }

            try {
                array.isWritable();
                fail("Cannot interact with a closed instance");
            } catch (IllegalStateException e) {
                /* expected */
            }
        } finally {
            if (array != null && !array.isClosed()) {
                IoUtils.closeQuietly(array);
            }
        }
    }

    @Test
    public void testInteractPutOfBounds() throws Exception {
        MemoryIntArray array = null;
        try {
            array = new MemoryIntArray(3);

            try {
                array.get(-1);
                fail("Cannot interact out of array bounds");
            } catch (IndexOutOfBoundsException e) {
                /* expected */
            }

            try {
                array.get(3);
                fail("Cannot interact out of array bounds");
            } catch (IndexOutOfBoundsException e) {
                /* expected */
            }

            try {
                array.set(-1, 0);
                fail("Cannot interact out of array bounds");
            } catch (IndexOutOfBoundsException e) {
                /* expected */
            }

            try {
                array.set(3, 0);
                fail("Cannot interact out of array bounds");
            } catch (IndexOutOfBoundsException e) {
                /* expected */
            }
        } finally {
            IoUtils.closeQuietly(array);
        }
    }

    @Test
    public void testOverMaxSize() throws Exception {
        MemoryIntArray array = null;
        try {
            array = new MemoryIntArray(MemoryIntArray.getMaxSize() + 1);
            fail("Cannot use over max size");
        } catch (IllegalArgumentException e) {
            /* expected */
        } finally {
            IoUtils.closeQuietly(array);
        }
    }

    @Test
    public void testNotMutableByUnprivilegedClients() throws Exception {
        RemoteIntArray remoteIntArray = new RemoteIntArray(1);
        try {
            assertNotNull("Couldn't get remote instance", remoteIntArray);
            MemoryIntArray localIntArray = remoteIntArray.peekInstance();
            assertNotNull("Couldn't get local instance", localIntArray);

            remoteIntArray.set(0, 1);
            assertSame("Remote should be able to modify", 1, remoteIntArray.get(0));

            try {
                localIntArray.set(0, 0);
                fail("Local shouldn't be able to modify");
            } catch (UnsupportedOperationException e) {
                /* expected */
            }
            assertSame("Local shouldn't be able to modify", 1, localIntArray.get(0));
            assertSame("Local shouldn't be able to modify", 1, remoteIntArray.get(0));
        } finally {
            remoteIntArray.destroy();
        }
    }

    @Test
    public void testAshmemSizeMatchesMemoryIntArraySize() throws Exception {
        boolean success = false;

        // Get a handle to a remote process to send the fd
        RemoteIntArray remoteIntArray = new RemoteIntArray(1);
        try {
            // Let us try 100 times
            for (int i = 0; i < 100; i++) {
                // Create a MemoryIntArray to muck with
                MemoryIntArray array = new MemoryIntArray(1);

                // Create the fd to stuff in the MemoryIntArray
                final int fd = nativeCreateAshmem("foo", 1);

                // Replace the fd with our ahsmem region
                Field fdFiled = MemoryIntArray.class.getDeclaredField("mFd");
                fdFiled.setAccessible(true);
                fdFiled.set(array, fd);

                CountDownLatch countDownLatch = new CountDownLatch(2);

                new Thread() {
                    @Override
                    public void run() {
                        for (int i = 2; i < Integer.MAX_VALUE; i++) {
                            if (countDownLatch.getCount() == 1) {
                                countDownLatch.countDown();
                                return;
                            }
                            nativeSetAshmemSize(fd, i);
                        }
                    }
                }.start();

                try {
                    remoteIntArray.accessLastElementInRemoteProcess(array);
                } catch (IllegalArgumentException e) {
                    success = true;
                }

                countDownLatch.countDown();
                countDownLatch.await(1000, TimeUnit.MILLISECONDS);

                if (success) {
                    break;
                }
            }
        } finally {
            remoteIntArray.destroy();
        }

        if (!success) {
            fail("MemoryIntArray should catch ahshmem size changing under it");
        }
    }

    // Ensure mmap/munmap behave properly even if the underlying FD's size has changed.
    @Test
    public void testMmapAndMunmapSizeConsistency() throws Exception {

        MemoryIntArray array = null;
        int largeFd = -1;
        int originalFd = -1;
        long newMappedAddr = -1;
        // Since we only allocate enough space for 1 int below, this is also the mapping size for
        // the array, since an int is going to be less than a page in size, but the mapping size
        // is page aligned.
        int pageSize = (int) Os.sysconf(OsConstants._SC_PAGESIZE);
        try {
            array = new MemoryIntArray(1);

            Field fdField = MemoryIntArray.class.getDeclaredField("mFd");
            fdField.setAccessible(true);

            Field addrField = MemoryIntArray.class.getDeclaredField("mMemoryAddr");
            addrField.setAccessible(true);

            originalFd = (int) fdField.get(array);

            long mappedAddr = (long) addrField.get(array);

            // Generate ashmem descriptor with a size large enough to ensure that the region spans
            // more than 1 page.
            largeFd = nativeCreateAshmem("large_fd", (2 * pageSize) / Integer.BYTES);

            // Establish collateral adjacent mapped segment
            newMappedAddr = nativeMremap(mappedAddr, pageSize, 2 * pageSize);
            assertTrue("mremap should not fail", newMappedAddr != -1);
            long adjacentProbeAddr = newMappedAddr + pageSize;

            // Inject the larger FD and new mapping address before closing. This should cause only
            // the first page of array to be unmapped, since that was its original size.
            fdField.set(array, largeFd);
            addrField.set(array, newMappedAddr);
            array.close();

            // Verify the original mapped configuration limits are honored. This would fail
            // if we tried to reuse the size from the injected FD.
            assertTrue("First page of the mapping should be unmapped",
                       !nativeIsRangeMapped(newMappedAddr, pageSize));
            assertTrue("Probe should not be wiped by unmap",
                       nativeIsRangeMapped(adjacentProbeAddr, pageSize));
            nativeMunmap(adjacentProbeAddr, pageSize);
            newMappedAddr = -1;
        } finally {
            if (array != null && !array.isClosed()) {
                IoUtils.closeQuietly(array);
            }
            if (originalFd >= 0) {
                try {
                    ParcelFileDescriptor.adoptFd(originalFd).close();
                } catch (Exception e) {
                    // Ignored
                }
            }
            if (newMappedAddr != -1) {
                nativeMunmap(newMappedAddr, 2 * pageSize);
            }
        }
    }

    private native int nativeCreateAshmem(String name, int size);
    private native void nativeSetAshmemSize(int fd, int size);
    private native long nativeMremap(long oldAddress, int oldSize, int newSize);
    private native boolean nativeIsRangeMapped(long address, int size);
    private native int nativeMunmap(long address, int size);
}
