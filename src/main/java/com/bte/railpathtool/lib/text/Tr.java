package com.bte.railpathtool.lib.text;

import net.minecraft.client.resources.language.I18n;

/**
 * Acces centralise aux cles i18n du mod (assets/bte_railpathtool/lang).
 * Fallback sur la cle elle-meme si une langue incomplete est chargee.
 */
public final class Tr {

    public static final String PREFIX = "bte_railpathtool.";

    private Tr() {
    }

    public static String get(String key) {
        return I18n.get(PREFIX + key);
    }

    public static String get(String key, Object... args) {
        return I18n.get(PREFIX + key, args);
    }

    public static boolean exists(String key) {
        return I18n.exists(PREFIX + key);
    }
}
