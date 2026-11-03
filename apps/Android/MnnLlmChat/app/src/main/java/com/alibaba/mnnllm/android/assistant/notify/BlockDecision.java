package com.alibaba.mnnllm.android.assistant.notify;

/**
 * Pure block/allow decision helper, ported from mnblocker
 * (io.mo.mnblocker.BlockDecision): the full priority chain
 * (override > app whitelist > allow rule > block rule > default-allow),
 * emitting the {@code decisionReason} the listener logs so the logcat chain
 * is auditable (spec-002 task 1 completion criterion).
 */
final class BlockDecision {

    private BlockDecision() {}

    /**
     * @param matcher         channel or content matcher to test candidates against
     * @param override        Boolean.TRUE = force block, Boolean.FALSE = force
     *                        allow, null = defer to regex rules
     * @param appWhitelisted  true if the owning app is fully exempt
     * @param candidates      text fields to match (id/name/description, or title/text)
     */
    static Result decide(RuleMatcher matcher, Boolean override, boolean appWhitelisted,
                         String... candidates) {
        if (override != null) {
            return new Result(override, "override(" + (override ? "block" : "allow") + ")");
        }
        if (appWhitelisted) {
            return new Result(false, "app-whitelist");
        }
        String allowRule = matcher.firstAllowMatch(candidates);
        if (allowRule != null) {
            return new Result(false, "allow:" + allowRule);
        }
        String blockRule = matcher.firstBlockMatch(candidates);
        if (blockRule != null) {
            return new Result(true, "regex:" + blockRule);
        }
        return new Result(false, "no-match");
    }

    static final class Result {
        final boolean block;
        final String reason;

        Result(boolean block, String reason) {
            this.block = block;
            this.reason = reason;
        }
    }
}
