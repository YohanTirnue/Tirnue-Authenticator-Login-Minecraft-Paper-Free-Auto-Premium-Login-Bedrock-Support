package com.tirnue.auth.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast small-caps typography utility for TirnueAuth.
 */
public class SmallCaps {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final char[] SMALL_CAPS_TABLE = new char[128];

    static {
        char[] smallChars = new char[] {
            'ᴀ','ʙ','ᴄ','ᴅ','ᴇ','ꜰ','ɢ','ʜ','ɪ','ᴊ','ᴋ','ʟ','ᴍ',
            'ɴ','ᴏ','ᴘ','ǫ','ʀ','ꜱ','ᴛ','ᴜ','ᴠ','ᴡ','x','ʏ','ᴢ'
        };
        for (int i = 0; i < 26; i++) {
            SMALL_CAPS_TABLE['a' + i] = smallChars[i];
            SMALL_CAPS_TABLE['A' + i] = smallChars[i];
        }
    }

    public static String toSmallCaps(String text) {
        if (text == null || text.isEmpty()) return "";
        if (CACHE.size() > 4096) {
            CACHE.clear();
        }
        return CACHE.computeIfAbsent(text, SmallCaps::convert);
    }

    private static String convert(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        int len = text.length();

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);

            // Preserve Minecraft color & formatting codes (&c, §e, &#rrggbb, etc.)
            if ((c == '&' || c == '§') && i + 1 < len) {
                char next = text.charAt(i + 1);
                if (isColorCode(next)) {
                    sb.append(c).append(Character.toLowerCase(next));
                    i++;
                    continue;
                }
                if (next == '#' && i + 7 < len) {
                    boolean isHex = true;
                    for (int h = i + 2; h <= i + 7; h++) {
                        char hc = text.charAt(h);
                        if (!isHexChar(hc)) {
                            isHex = false;
                            break;
                        }
                    }
                    if (isHex) {
                        sb.append(c).append('#');
                        for (int h = i + 2; h <= i + 7; h++) {
                            sb.append(Character.toLowerCase(text.charAt(h)));
                        }
                        i += 7;
                        continue;
                    }
                }
            }

            // Convert ASCII letters to small caps
            if (c < 128 && SMALL_CAPS_TABLE[c] != 0) {
                sb.append(SMALL_CAPS_TABLE[c]);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isColorCode(char c) {
        return (c >= '0' && c <= '9') ||
               (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') ||
               (c >= 'k' && c <= 'o') || (c >= 'K' && c <= 'O') ||
               c == 'r' || c == 'R' || c == 'x' || c == 'X';
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
