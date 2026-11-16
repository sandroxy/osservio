package com.sandrox.osservio.ui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InspectorGlassShaderTest {
    @Test public void opticalTokensMatchPinnedUpstreamShader() throws Exception {
        // Independently computed from the AGSL in the pinned upstream GlassLensRenderer.kt,
        // after stripping comments and whitespace. Parameter mapping is maintained separately.
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(
            InspectorGlassShader.SOURCE.replaceAll("\\s+", "").getBytes(StandardCharsets.UTF_8));
        StringBuilder value = new StringBuilder();
        for (byte item : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        assertEquals("2a3d09ba73ccf8b67336fc527fc071203f486c56ef6b3476dc15c1608c592a61", value.toString());
    }
}
