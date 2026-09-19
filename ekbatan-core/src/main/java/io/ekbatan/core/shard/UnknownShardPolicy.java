package io.ekbatan.core.shard;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

/**
 * What to do with a shard this deployment has not configured.
 *
 * <p>The fallback exists because shard layouts differ between environments and grow over time:
 * a staging database holding one shard has to be able to read ids minted where there are four.
 * It is also exactly what a mistyped shard identifier looks like, and that one writes to the
 * wrong database. Nothing at the call site can tell the two apart.
 *
 * <p>So the fallback stays the default and is no longer silent - {@link DatabaseRegistry}
 * logs every time it fires - and a deployment that holds the whole layout, where an
 * unregistered shard can only be a bug, can ask for {@link #FAIL} instead.
 *
 * <p>Bound from {@code ekbatan.sharding.unknownShardPolicy} in {@code application.yml}, case and
 * separator insensitively, so {@code fail}, {@code FAIL}, {@code useDefaultShard} and
 * {@code use_default_shard} all work.
 */
public enum UnknownShardPolicy {

    /** Route it to the default shard, and log a warning saying so. The historical behaviour. */
    USE_DEFAULT_SHARD,

    /** Throw. For a deployment that holds every shard in the layout. */
    FAIL;

    /**
     * Parses a configured value, ignoring case.
     *
     * @param value the configured value; {@code null} or blank yields {@link #USE_DEFAULT_SHARD}.
     * @return the matching policy.
     * @throws IllegalArgumentException if the value names neither policy.
     */
    @JsonCreator
    public static UnknownShardPolicy from(String value) {
        if (value == null || value.isBlank()) {
            return USE_DEFAULT_SHARD;
        }
        // Normalised, so useDefaultShard / use_default_shard / USE-DEFAULT-SHARD all bind. YAML
        // keys in this file are camelCase and enum constants are SCREAMING_SNAKE; making an
        // operator guess which one a *value* wants is a pointless way to fail a deployment.
        var normalised = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return switch (normalised) {
            case "usedefaultshard" -> USE_DEFAULT_SHARD;
            case "fail" -> FAIL;
            default ->
                throw new IllegalArgumentException(
                        "unknownShardPolicy must be 'useDefaultShard' or 'fail', not '" + value + "'");
        };
    }
}
