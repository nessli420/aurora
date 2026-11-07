package com.aurora.music.data.routes

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteIdentityFormatTest {
    // keys are persisted in route bindings, so the hex format must never change
    @Test fun keysKeepTheirPersistedFormat() {
        assertEquals("android:2:5c73a5c73dc13ecfc980360fd350c74b6f118d464b142e6f5fe69d79ea34ae49",
            RouteIdentity.key(2, "", true, true))
        assertEquals("android:8:9f22b2f81273aac48b23c6af6ea788fbc14f3075d1da5ca108b3107b9cec0a3a",
            RouteIdentity.key(8, " AA:BB:CC ", false, true))
    }
}
