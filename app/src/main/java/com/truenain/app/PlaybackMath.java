package com.truenain.app;

final class PlaybackMath {
    private PlaybackMath() {}

    static int nearestIndex(long[] ptsUs, long targetUs) {
        if (ptsUs == null || ptsUs.length == 0) return 0;
        int lo = 0, hi = ptsUs.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = ptsUs[mid];
            if (v < targetUs) lo = mid + 1;
            else if (v > targetUs) hi = mid - 1;
            else return mid;
        }
        if (lo <= 0) return 0;
        if (lo >= ptsUs.length) return ptsUs.length - 1;
        return Math.abs(ptsUs[lo] - targetUs) < Math.abs(targetUs - ptsUs[lo - 1]) ? lo : lo - 1;
    }

    static int forwardFiveSeconds(long[] ptsUs, int index) {
        if (ptsUs == null || ptsUs.length == 0) return 0;
        index = Math.max(0, Math.min(ptsUs.length - 1, index));
        return nearestIndex(ptsUs, ptsUs[index] + 5_000_000L);
    }

    static int maxManualFps(double fps) {
        if (fps <= 0) return 60;
        return Math.max(1, Math.min(120, (int)Math.ceil(fps)));
    }

    static double effectiveSpeed(double base, boolean slowPreview) {
        return slowPreview ? 0.25 : base;
    }
}
