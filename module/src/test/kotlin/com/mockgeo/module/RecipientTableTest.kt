package com.mockgeo.module

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RecipientTableTest {
    @Test fun `reconstructed interface shares single update quota with original wrapper`() {
        val table = RecipientTable<String>()
        val original = RecipientTable.Subscription(10321, "gps", 1)
        table.publish("remote-binder", original)
        val reconstructed = table.find(String("remote-binder".toCharArray()), "gps")!!
        assertThat(reconstructed.quota.claim(1)).isEqualTo(1)
        assertThat(original.quota.claim(1)).isEqualTo(0)
        assertThat(reconstructed.uid).isEqualTo(10321)
    }
    @Test fun `provider registrations on one binder keep independent request limits`() {
        val table = RecipientTable<String>()
        table.publish("binder", RecipientTable.Subscription(10321, "gps", 1))
        table.publish("binder", RecipientTable.Subscription(10321, "network", 3))
        assertThat(table.find("binder", "gps")!!.quota.claim(5)).isEqualTo(1)
        assertThat(table.find("binder", "network")!!.quota.claim(5)).isEqualTo(3)
        assertThat(table.find("binder", null)).isNull()
        table.remove("binder")
        assertThat(table.find("binder", "gps")).isNull()
    }
    @Test fun `failed registration restores preceding owner without resetting its quota`() {
        val table = RecipientTable<String>()
        val original = RecipientTable.Subscription(10321, "gps", 2)
        table.publish("binder", original)
        original.quota.claim(1)
        val failed = table.publish("binder", RecipientTable.Subscription(10400, "gps", 5))
        table.rollback(failed)
        assertThat(table.find("binder", "gps")).isSameInstanceAs(original)
        assertThat(original.quota.claim(5)).isEqualTo(1)
    }
    @Test fun `rollback cannot erase a newer concurrent subscription`() {
        val table = RecipientTable<String>()
        val failed = table.publish("binder", RecipientTable.Subscription(10321, "gps", 1))
        val newer = RecipientTable.Subscription(10321, "gps", 9)
        table.publish("binder", newer)
        table.rollback(failed)
        assertThat(table.find("binder", null)).isSameInstanceAs(newer)
    }
}
