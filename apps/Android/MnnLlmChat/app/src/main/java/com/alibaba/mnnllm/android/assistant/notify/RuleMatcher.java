package com.alibaba.mnnllm.android.assistant.notify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure, dependency-free rule engine, ported verbatim from mnblocker
 * (io.mo.mnblocker.RuleMatcher) for the spec-002 notification assistant.
 *
 * Two rule sets:
 *  - block rules: a channel / notification whose text matches counts as
 *    "marketing" and is silenced. The built-in default {@link #DEFAULT_BLOCK_RULE}
 *    is always present in a channel set ({@link #compile}) — but never in a
 *    content set ({@link #compileContent}).
 *  - allow rules (whitelist / safety valve): a channel that matches is NEVER
 *    blocked by a regex, protecting verification codes / IM even when a broad
 *    block rule would otherwise catch them.
 *
 * Precedence: allow rule beats block rule beats default-allow. A per-channel
 * user override (handled by the caller) still wins over everything.
 *
 * No android.* / org.json dependency on purpose.
 */
final class RuleMatcher {

    /** The literal built-in default: any text containing 营销. Always active. */
    static final String DEFAULT_BLOCK_RULE = ".*营销.*";

    private final List<Pattern> blockPatterns;
    private final List<Pattern> allowPatterns;
    private final List<String> blockRaw;
    private final List<String> allowRaw;

    private RuleMatcher(List<Pattern> blockPatterns, List<String> blockRaw,
                        List<Pattern> allowPatterns, List<String> allowRaw) {
        this.blockPatterns = blockPatterns;
        this.blockRaw = blockRaw;
        this.allowPatterns = allowPatterns;
        this.allowRaw = allowRaw;
    }

    /** Compile block + allow rule strings; the default block rule is injected first. */
    static RuleMatcher compile(Collection<String> blockRules, Collection<String> allowRules) {
        return compile(blockRules, allowRules, true);
    }

    /**
     * Compile a CONTENT-level rule set: same as {@link #compile} minus the
     * built-in {@link #DEFAULT_BLOCK_RULE} (that rule describes a channel, and
     * content rules match a notification's title / text — injecting it here
     * would drop any message that merely mentions 营销).
     */
    static RuleMatcher compileContent(Collection<String> blockRules, Collection<String> allowRules) {
        return compile(blockRules, allowRules, false);
    }

    private static RuleMatcher compile(Collection<String> blockRules, Collection<String> allowRules,
                                       boolean injectDefault) {
        List<Pattern> bp = new ArrayList<>();
        List<String> braw = new ArrayList<>();
        if (injectDefault) {
            addCompiled(DEFAULT_BLOCK_RULE, bp, braw);
        }
        if (blockRules != null) {
            for (String r : blockRules) {
                addCompiled(r, bp, braw);
            }
        }
        if (injectDefault && bp.isEmpty()) {
            addCompiled(DEFAULT_BLOCK_RULE, bp, braw);
        }

        List<Pattern> ap = new ArrayList<>();
        List<String> araw = new ArrayList<>();
        if (allowRules != null) {
            for (String r : allowRules) {
                addCompiled(r, ap, araw);
            }
        }
        return new RuleMatcher(bp, braw, ap, araw);
    }

    private static void addCompiled(String rule, List<Pattern> patterns, List<String> raw) {
        if (rule == null) {
            return;
        }
        String t = rule.trim();
        if (t.isEmpty() || t.startsWith("#") || raw.contains(t)) {
            return;
        }
        try {
            patterns.add(Pattern.compile(t));
            raw.add(t);
        } catch (PatternSyntaxException ignored) {
            // A malformed rule is skipped rather than breaking the whole set.
        }
    }

    /** @return the first block rule that matched any candidate, else {@code null}. */
    String firstBlockMatch(String... candidates) {
        return firstMatch(blockPatterns, candidates);
    }

    /** @return the first allow rule that matched any candidate, else {@code null}. */
    String firstAllowMatch(String... candidates) {
        return firstMatch(allowPatterns, candidates);
    }

    /** Regex-only verdict, ignoring any per-channel override. */
    boolean shouldBlock(String... candidates) {
        if (firstAllowMatch(candidates) != null) {
            return false;
        }
        return firstBlockMatch(candidates) != null;
    }

    /** Full verdict including a per-channel override. */
    boolean decideBlock(Boolean override, String... candidates) {
        if (override != null) {
            return override;
        }
        return shouldBlock(candidates);
    }

    /** Compiled block rules, default first. Never empty. */
    List<String> blockRules() {
        return blockRaw;
    }

    /** Compiled allow rules (whitelist). May be empty. */
    List<String> allowRules() {
        return allowRaw;
    }

    private static String firstMatch(List<Pattern> patterns, String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (Pattern p : patterns) {
            for (String cand : candidates) {
                if (cand == null || cand.isEmpty()) {
                    continue;
                }
                try {
                    // find() subsumes matches(): a full match is also a match
                    // somewhere, so testing both would run the engine twice.
                    if (p.matcher(cand).find()) {
                        return p.pattern();
                    }
                } catch (Throwable ignored) {
                    // A pathological input must never break channel handling.
                }
            }
        }
        return null;
    }
}
