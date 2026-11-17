package com.likeazusa2.aeautopatterns.api;

/**
 * Stable discovery constants for third-party integrations.
 *
 * <p>Third-party mods should depend on the mod id in their NeoForge metadata and
 * implement {@code integration.RecipeAdapterProvider}; this class avoids a
 * dependency on the terminal implementation or client classes.</p>
 */
public final class AEAutoPatternsApi {
    public static final String MOD_ID = "aeautopatterns";
    public static final int API_VERSION = 1;

    private AEAutoPatternsApi() {}
}
