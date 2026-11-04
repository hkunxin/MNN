package com.alibaba.mnnllm.android.assistant.notify;

import java.util.regex.Pattern;

/**
 * R7 privacy rule: verification-code notifications never enter the digest
 * store (they are the most sensitive text a notification carries). Detection
 * is intentionally conservative — keyword AND digits — so ordinary messages
 * containing numbers are not silently hidden from the summary.
 */
public final class VerificationCodes {

    /** 验证码/OTP wording, Chinese and English. */
    private static final Pattern KEYWORD = Pattern.compile(
            "验证码|校验码|动态码|动态密码|确认码|识别码|校验代码"
                    + "|verification\\s*code|security\\s*code|one[-\\s]?time\\s*(?:code|password)"
                    + "|login\\s*code|auth(?:entication)?\\s*code|\\bOTP\\b",
            Pattern.CASE_INSENSITIVE);

    /** The code itself: a 3-8 digit run, possibly spaced (1 2 3 4). */
    private static final Pattern DIGITS = Pattern.compile("\\d[\\d\\s-]{2,15}\\d");

    private VerificationCodes() {
    }

    /** @return true when the notification looks like a verification code. */
    public static boolean isVerificationCode(String title, String text) {
        String haystack = (title == null ? "" : title) + "\n" + (text == null ? "" : text);
        if (haystack.trim().isEmpty()) {
            return false;
        }
        return KEYWORD.matcher(haystack).find() && DIGITS.matcher(haystack).find();
    }
}
