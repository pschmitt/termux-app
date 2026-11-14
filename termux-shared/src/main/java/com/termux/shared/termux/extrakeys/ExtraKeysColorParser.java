package com.termux.shared.termux.extrakeys;

import androidx.annotation.Nullable;

/**
 * Parses the color values of the {@code extra-keys-button-*-color} properties of
 * {@code termux.properties}.
 *
 * Supported formats are {@code #RGB}, {@code #RRGGBB} and {@code #AARRGGBB} (alpha first, like
 * {@link android.graphics.Color#parseColor(String)}), case-insensitive, surrounded by optional
 * whitespace. Opaque colors get an alpha of {@code 0xFF}. This class does not depend on the
 * Android framework so that it can be unit tested on the JVM.
 */
public final class ExtraKeysColorParser {

    /** The maximum accepted value of {@code extra-keys-button-corner-radius} in dp. Larger radii
     * are rejected, a radius above half the button height only makes a pill shape anyway. */
    public static final float MAX_CORNER_RADIUS_DP = 32f;

    private ExtraKeysColorParser() {}

    /**
     * Parse the value of the {@code extra-keys-button-corner-radius} property: a plain decimal
     * number of dp, for example {@code 8} or {@code 6.5}, from {@code 0} to
     * {@link #MAX_CORNER_RADIUS_DP}, surrounded by optional whitespace. Signs, exponents,
     * {@code NaN}, infinity and units are not accepted.
     *
     * @param value The value to parse, may be {@code null}.
     * @return Returns the radius in dp, or {@code null} if the value is {@code null}, empty,
     * not a valid number or out of range.
     */
    @Nullable
    public static Float parseCornerRadiusDp(@Nullable String value) {
        if (value == null) return null;

        String number = value.trim();
        if (!number.matches("[0-9]{1,4}(\\.[0-9]{1,4})?")) return null;

        float radius = Float.parseFloat(number);
        if (radius > MAX_CORNER_RADIUS_DP) return null;
        return radius;
    }

    /**
     * Parse a color value.
     *
     * @param value The value to parse, may be {@code null}.
     * @return Returns the ARGB color, or {@code null} if the value is {@code null}, empty or not
     * a valid color.
     */
    @Nullable
    public static Integer parseColor(@Nullable String value) {
        if (value == null) return null;

        String hex = value.trim();
        if (hex.length() < 2 || hex.charAt(0) != '#') return null;
        hex = hex.substring(1);

        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), 16) < 0 || hex.charAt(i) > 'f') return null;
        }

        switch (hex.length()) {
            case 3: { // #RGB
                int r = Character.digit(hex.charAt(0), 16);
                int g = Character.digit(hex.charAt(1), 16);
                int b = Character.digit(hex.charAt(2), 16);
                return 0xFF000000 | (r * 0x11) << 16 | (g * 0x11) << 8 | (b * 0x11);
            }
            case 6: // #RRGGBB
                return 0xFF000000 | (int) Long.parseLong(hex, 16);
            case 8: // #AARRGGBB
                return (int) Long.parseLong(hex, 16);
            default:
                return null;
        }
    }

    /**
     * Parse a color value, falling back to a default.
     *
     * @param value The value to parse, may be {@code null}.
     * @param def The color to return if the value is {@code null}, empty or not a valid color.
     * @return Returns the parsed ARGB color, otherwise {@code def}.
     */
    public static int parseColor(@Nullable String value, int def) {
        Integer color = parseColor(value);
        return color != null ? color : def;
    }

}
