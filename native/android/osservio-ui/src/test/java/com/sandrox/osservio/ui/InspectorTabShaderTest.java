package com.sandrox.osservio.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Independent hashes of the pinned upstream programs, not of a generated Java file. */
public final class InspectorTabShaderTest {
    @Test public void opticalProgramsRetainUpstreamExpressions() throws Exception {
        assertEquals("7a59a28535b3c0a3a860f496a4152e1a418098aa137a8180c70cc4abe6c9951a", hash(InspectorTabShaders.REFRACTION));
        assertEquals("22eff10caf6fa23e7121d9a75b6f78d83b741d11484a9fef5fad64a3a539cba4", hash(InspectorTabShaders.DISPERSION));
        assertEquals("1e49fbcd70af5e9a202e38e8d103f53483ee158d1843f42379d216f29d4c3e3f", hash(InspectorTabShaders.HIGHLIGHT));
        assertEquals("732f5916989eb3be1c4db4dbff418c3b8594fa799a99a844ebe6263661a55604", hash(InspectorTabShaders.INTERACTIVE));
    }
    private static String hash(String source) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.replaceAll("\\s+", "").getBytes(StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        for (byte value : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
}
