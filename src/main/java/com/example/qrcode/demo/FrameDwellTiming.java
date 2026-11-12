package com.example.qrcode.demo;

/** Testable minimum-visible-duration rule for the QR image player. */
public final class FrameDwellTiming {
    private FrameDwellTiming() { }

    public static boolean hasElapsed(long paintedAtNanos, long nowNanos, int minimumMillis) {
        if (paintedAtNanos <= 0 || nowNanos < paintedAtNanos || minimumMillis < 0) {
            return false;
        }
        return nowNanos - paintedAtNanos >= minimumMillis * 1_000_000L;
    }

    public static int countdownSecondsRemaining(long startedAtNanos, long nowNanos, int countdownSeconds) {
        if (startedAtNanos <= 0 || nowNanos < startedAtNanos || countdownSeconds <= 0) {
            return Math.max(0, countdownSeconds);
        }
        long elapsedNanos = nowNanos - startedAtNanos;
        long durationNanos = countdownSeconds * 1_000_000_000L;
        if (elapsedNanos >= durationNanos) {
            return 0;
        }
        long remainingNanos = durationNanos - elapsedNanos;
        return (int) ((remainingNanos + 999_999_999L) / 1_000_000_000L);
    }
}
