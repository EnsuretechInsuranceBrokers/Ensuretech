package com.broker.sheetgen;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** The standalone page is plain HTML and script; these checks keep a few decisions from being undone by accident. */
class StaticPageTest {
    private static String page() throws Exception {
        try (var in = new ClassPathResource("static/index.html").getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
    }

    @Test
    void theMobileCodeIsNeverFilledIn() throws Exception {
        String html = page();
        assertTrue(html.contains("mobile_no_code: \"\""), "the mobile / country code must be written blank");
        assertFalse(html.matches("(?s).*mobile_no_code\\s*:\\s*\"9\\d\".*"), "no country code (91) may be written into the sheet");
        assertFalse(html.contains("mobile_no_code: \"91\""));
    }
}
