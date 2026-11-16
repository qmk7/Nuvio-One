package com.nuvio.tv.core.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTypesTest {

    @Test
    fun `a television is TV whatever its screen size`() {
        assertEquals(DeviceType.TV, DeviceTypes.resolve(isTelevision = true, smallestScreenWidthDp = 540))
        assertEquals(DeviceType.TV, DeviceTypes.resolve(isTelevision = true, smallestScreenWidthDp = 1080))
    }

    @Test
    fun `touch screens split into phone and tablet at 600dp`() {
        assertEquals(DeviceType.PHONE, DeviceTypes.resolve(isTelevision = false, smallestScreenWidthDp = 360))
        assertEquals(DeviceType.PHONE, DeviceTypes.resolve(isTelevision = false, smallestScreenWidthDp = 599))
        assertEquals(DeviceType.TABLET, DeviceTypes.resolve(isTelevision = false, smallestScreenWidthDp = 600))
        assertEquals(DeviceType.TABLET, DeviceTypes.resolve(isTelevision = false, smallestScreenWidthDp = 800))
    }

    @Test
    fun `only TV uses the remote, phones and tablets use touch`() {
        assertTrue(DeviceType.TV.isTv)
        assertFalse(DeviceType.TV.isTouch)
        assertTrue(DeviceType.PHONE.isTouch)
        assertTrue(DeviceType.TABLET.isTouch)
    }
}
