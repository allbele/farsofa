// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr

import app.farsofa.vr.console.DiscoveredConsole
import app.farsofa.vr.console.Discovery
import app.farsofa.vr.console.RegisteredConsole
import app.farsofa.vr.console.toHexId
import app.farsofa.vr.input.GamepadInput
import app.farsofa.vr.psn.PsnAuth
import app.farsofa.vr.ui.consoleRows
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreLogicTest {
    @Test
    fun accountIdIsUserIdInLittleEndianBytes() {
        // 0x0102030405060708
        val bytes = PsnAuth.accountIdFromUserId("72623859790382856")
        assertArrayEquals(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1), bytes)
    }

    @Test
    fun accountIdHandlesIdsAboveSignedRange() {
        val bytes = PsnAuth.accountIdFromUserId("18446744073709551615")
        assertArrayEquals(ByteArray(8) { 0xFF.toByte() }, bytes)
    }

    @Test
    fun broadcastAddressFromPrefix() {
        assertEquals("192.168.1.255", Discovery.broadcastAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 37), 24))
        assertEquals("10.0.3.255", Discovery.broadcastAddress(byteArrayOf(10, 0, 2, 9), 23))
        assertNull(Discovery.broadcastAddress(byteArrayOf(10, 0, 2, 9), 32))
        assertNull(Discovery.broadcastAddress(ByteArray(16), 64))
    }

    @Test
    fun parsesDiscoveredHosts() {
        val fields = arrayOf<String?>(
            "ready", "192.168.1.20", "Sala", "PS5", "aabbccddeeff", "1", "Some Game",
            "standby", "192.168.1.21", null, "PS4", "001122334455", "0", null,
            "ready", null, "Broken", "PS5", "ffffffffffff", "1", null,
        )
        val hosts = Discovery.parseHosts(fields)
        assertEquals(2, hosts.size)
        assertEquals("AABBCCDDEEFF", hosts[0].id)
        assertEquals("Sala", hosts[0].name)
        assertEquals(DiscoveredConsole.State.READY, hosts[0].state)
        assertEquals("Some Game", hosts[0].runningApp)
        assertEquals("PS4", hosts[1].name)
        assertEquals(DiscoveredConsole.State.STANDBY, hosts[1].state)
        assertEquals(false, hosts[1].ps5)
    }

    @Test
    fun wakeCredentialIsRegistKeyReadAsHex() {
        val key = "1a2b3c4d".toByteArray() + ByteArray(8)
        assertEquals(0x1a2b3c4dL, RegisteredConsole("ID", "n", key, ByteArray(16), null).wakeCredential)
        assertNull(RegisteredConsole("ID", "n", "zz".toByteArray(), ByteArray(16), null).wakeCredential)
    }

    @Test
    fun macBytesBecomeUpperCaseHexId() {
        assertEquals("00AB10FF0102", byteArrayOf(0, 0xAB.toByte(), 0x10, 0xFF.toByte(), 1, 2).toHexId())
    }

    @Test
    fun sticksAndTriggersScaleToProtocolRanges() {
        assertEquals(0, GamepadInput.stick(0.02f))
        assertEquals(32767, GamepadInput.stick(1f))
        assertEquals(-32767, GamepadInput.stick(-1.5f))
        assertEquals(0, GamepadInput.trigger(-0.2f))
        assertEquals(255, GamepadInput.trigger(1f))
        assertEquals(128, GamepadInput.trigger(0.5f))
    }

    @Test
    fun consoleRowsMergePairedAndDiscovered() {
        val paired = RegisteredConsole("AA", "Quarto", ByteArray(16), ByteArray(16), null)
        val away = RegisteredConsole("CC", "Casa de praia", ByteArray(16), ByteArray(16), null)
        val seen = listOf(
            DiscoveredConsole("AA", "PS5-123", "192.168.1.20", DiscoveredConsole.State.STANDBY, true, null),
            DiscoveredConsole("BB", "PS5-456", "192.168.1.21", DiscoveredConsole.State.READY, true, null),
            DiscoveredConsole("DD", "PS4-789", "192.168.1.22", DiscoveredConsole.State.READY, false, null),
        )
        val rows = consoleRows(seen, listOf(paired, away))
        assertEquals(listOf("AA", "CC", "BB"), rows.map { it.id })
        assertEquals("Quarto", rows[0].name)
        assertEquals(DiscoveredConsole.State.STANDBY, rows[0].discovered?.state)
        assertNull(rows[1].discovered)
        assertNull(rows[2].registered)
    }
}
