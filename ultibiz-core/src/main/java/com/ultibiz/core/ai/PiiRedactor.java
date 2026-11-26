package com.ultibiz.core.ai;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The personal data that may not leave the organisation. The rules follow the
 * country of the organisation: Indonesia masks the national tax number, the
 * e-mail addresses and the phone numbers; the strict default masks the e-mail
 * addresses everywhere.
 */
public final class PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(\\+62|0)\\d{2,3}[\\s-]?\\d{3,4}[\\s-]?\\d{3,5}");
    private static final Pattern NPWP = Pattern.compile("\\d{2}\\.\\d{3}\\.\\d{3}\\.\\d-\\d{3}\\.\\d{3}");
    private static final Pattern NPWP_PLAIN = Pattern.compile("\\b\\d{15,16}\\b");

    private PiiRedactor() {
    }

    /** Redacts according to the country of the organisation. */
    public static String redact(String text, String countryCode) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = mask(EMAIL.matcher(text), "[email]");
        if ("ID".equalsIgnoreCase(countryCode)) {
            result = mask(NPWP.matcher(result), "[npwp]");
            result = mask(NPWP_PLAIN.matcher(result), "[npwp]");
            result = mask(PHONE.matcher(result), "[phone]");
        }
        return result;
    }

    private static String mask(Matcher matcher, String replacement) {
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The country rules as they are shown in the admin console. */
    public static List<String> rules(String countryCode) {
        if ("ID".equalsIgnoreCase(countryCode)) {
            return List.of("e-mail -> [email]", "NPWP -> [npwp]", "phone -> [phone]");
        }
        return List.of("e-mail -> [email]");
    }
}
