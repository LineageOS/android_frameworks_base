/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.statementservice.network.retriever

import android.net.TrafficStats
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Whose traffic a fetch counts as, which its sockets take from the thread making them. */
@RunWith(AndroidJUnit4::class)
class UrlFetcherTest {
    // Android's loopback address is ::1, and the fetch goes to 127.0.0.1.
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val default = HttpsURLConnection.getDefaultSSLSocketFactory()
    private val uids = Collections.synchronizedList(mutableListOf<Int>())

    @Before
    fun setUp() {
        HttpsURLConnection.setDefaultSSLSocketFactory(Recording(default))
    }

    @After
    fun tearDown() {
        HttpsURLConnection.setDefaultSSLSocketFactory(default)
        server.close()
    }

    // The server never answers TLS: only the sockets made as the connection starts matter.
    private fun fetch(uid: Int?) = runBlocking {
        UrlFetcher().fetch(URL("https://127.0.0.1:${server.localPort}/"), 1000, 1000, uid = uid)
    }

    @Test
    fun fetchesForAnAppCountAsTheApps() {
        val result = fetch(APP_UID)
        assertWithMessage("$result").that(uids.toSet()).containsExactly(APP_UID)
    }

    @Test
    fun fetchesForNoAppCountAsTheServices() {
        val result = fetch(null)
        assertWithMessage("$result").that(uids.toSet()).containsExactly(NO_UID)
    }

    /** Notes whose traffic each socket counts as, as TLS starts over it. */
    private inner class Recording(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
        private fun <T> noted(socket: T): T {
            uids.add(TrafficStats.getThreadStatsUid())
            return socket
        }

        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean) =
            noted(delegate.createSocket(s, host, port, autoClose))

        override fun createSocket(host: String, port: Int) =
            noted(delegate.createSocket(host, port))

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) =
            noted(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int) =
            noted(delegate.createSocket(host, port))

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ) = noted(delegate.createSocket(address, port, localAddress, localPort))

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    }

    private companion object {
        const val APP_UID = 10123
        const val NO_UID = -1
    }
}
