package com.nextlevellife.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryHintTest {
    @Test
    fun titleDependsOnManufacturer() {
        assertEquals("Samsung может усыпить блокировку", BatteryHint.forManufacturer("samsung").title)
        assertEquals("HiOS может усыпить блокировку", BatteryHint.forManufacturer("TECNO").title)
        assertEquals("HiOS может усыпить блокировку", BatteryHint.forManufacturer("Infinix").title)
        assertEquals("MIUI / HyperOS может усыпить блокировку", BatteryHint.forManufacturer("Xiaomi").title)
        assertEquals("Телефон может усыпить блокировку", BatteryHint.forManufacturer("Google").title)
        assertEquals("Телефон может усыпить блокировку", BatteryHint.forManufacturer(null).title)
    }

    @Test
    fun transsionAndXiaomiMentionAutostart() {
        assertTrue(BatteryHint.forManufacturer("tecno").body.contains("автозапуск"))
        assertTrue(BatteryHint.forManufacturer("redmi").body.contains("автозапуск"))
    }
}
