package nl.smartphonemod.client;

import nl.smartphonemod.client.hud.Notifications;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Stopwatch and timer of the Clock app; they keep running while the phone is put away. */
public final class ClockState {
    // Stopwatch
    public static boolean swRunning;
    public static long swStart, swAccumulated;
    public static final List<Long> laps = new ArrayList<>();

    // Timer
    public static long timerSetMs = 5 * 60_000;
    public static long timerEnd;      // when running
    public static long timerLeftMs = timerSetMs; // when paused
    public static boolean timerRunning;

    private ClockState() {}

    public static long stopwatchMs() {
        return swAccumulated + (swRunning ? System.currentTimeMillis() - swStart : 0);
    }

    public static void stopwatchToggle() {
        if (swRunning) swAccumulated += System.currentTimeMillis() - swStart;
        else swStart = System.currentTimeMillis();
        swRunning = !swRunning;
    }

    public static void stopwatchLapOrReset() {
        if (swRunning) laps.add(0, stopwatchMs());
        else {
            swAccumulated = 0;
            laps.clear();
        }
    }

    public static long timerMs() {
        return timerRunning ? Math.max(0, timerEnd - System.currentTimeMillis()) : timerLeftMs;
    }

    public static void timerToggle() {
        if (timerRunning) {
            timerLeftMs = timerMs();
            timerRunning = false;
        } else if (timerLeftMs > 0) {
            timerEnd = System.currentTimeMillis() + timerLeftMs;
            timerRunning = true;
        }
    }

    public static void timerAdjust(long deltaMs) {
        if (timerRunning) return;
        timerSetMs = Math.max(10_000, Math.min(99 * 60_000L, timerSetMs + deltaMs));
        timerLeftMs = timerSetMs;
    }

    public static void timerReset() {
        timerRunning = false;
        timerLeftMs = timerSetMs;
    }

    /** Called every client tick: fires the alarm when the timer runs out. */
    public static void tick() {
        if (timerRunning && System.currentTimeMillis() >= timerEnd) {
            timerRunning = false;
            timerLeftMs = timerSetMs;
            Notifications.post("clock", Component.translatable("smartphone.clock.timer"),
                    Component.translatable("smartphone.clock.timer_done"), true);
        }
    }

    public static String format(long ms, boolean hundredths) {
        long totalSec = ms / 1000;
        String base = String.format("%02d:%02d", totalSec / 60, totalSec % 60);
        return hundredths ? base + String.format(",%02d", (ms / 10) % 100) : base;
    }
}
