#!/usr/bin/env python3
"""Run the actual light-sensor lifecycle method against ambient-lux validity boundaries.

This host check isolates the production method from Android-only dependencies.
It does not emulate sensor events or certify the complete display controller.
"""

import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


JAVA = r"""
import java.util.ArrayList;

public class AmbientLuxWarmupRegression {
    static final class PowerManager {
        static final float BRIGHTNESS_INVALID_FLOAT = Float.NaN;
    }
    static final class Clock {
        long uptimeMillis() { return 100; }
    }
    static final class Handler {
        void removeMessages(int message) { }
    }
    static final class SensorManager {
        int registrations;
        int unregistrations;
        void registerListener(Object listener, Object sensor, int rate, Handler handler) {
            registrations++;
        }
        void unregisterListener(Object listener) { unregistrations++; }
    }
    static final class Controller {
        final boolean mResetAmbientLuxAfterWarmUpConfig;
        boolean mLightSensorEnabled;
        boolean mAmbientLuxValid;
        long mLightSensorEnableTime;
        int mCurrentLightSensorRate;
        int mInitialLightSensorRate = 250;
        int mRecentLightSamples;
        float mAmbientLux;
        float mPreThresholdLux = Float.NaN;
        float mScreenAutoBrightness = Float.NaN;
        float mPreThresholdBrightness = Float.NaN;
        final int MSG_UPDATE_AMBIENT_LUX = 1;
        final Clock mClock = new Clock();
        final Handler mHandler = new Handler();
        final SensorManager mSensorManager = new SensorManager();
        final Object mLightSensorListener = new Object();
        final Object mLightSensor = new Object();
        final ArrayList<Integer> mAmbientLightRingBuffer = new ArrayList<>();

        Controller(boolean reset) { mResetAmbientLuxAfterWarmUpConfig = reset; }
        void registerForegroundAppUpdater() { }
        void unregisterForegroundAppUpdater() { }

        @METHOD@
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static Controller enabled(boolean reset) {
        Controller controller = new Controller(reset);
        require(controller.setLightSensorEnabled(true), "initial enable must register");
        return controller;
    }

    static void cycle(Controller controller, boolean expectedValidity) {
        controller.setLightSensorEnabled(false);
        require(controller.mAmbientLuxValid == expectedValidity,
                "disable must preserve validity only when an estimate exists and reset is off");
        require(Float.isNaN(controller.mScreenAutoBrightness),
                "disable must clear the previous brightness recommendation");
        require(controller.mRecentLightSamples == 0
                && controller.mAmbientLightRingBuffer.isEmpty(),
                "disable must clear the sample window");
        require(controller.setLightSensorEnabled(true), "wake must register again");
        require(controller.mAmbientLuxValid == expectedValidity,
                "wake must not fabricate a valid ambient estimate");
        require(controller.mSensorManager.registrations == 2
                && controller.mSensorManager.unregistrations == 1,
                "the validity fix must preserve sensor registration lifecycle");
    }

    public static void main(String[] args) {
        Controller noSamples = enabled(false);
        cycle(noSamples, false);
        System.out.println("PASS: no-sample estimate stays invalid across disable and wake");

        Controller measuredDark = enabled(false);
        // Fixture represents an estimate already established from a genuine zero-lux sample.
        measuredDark.mAmbientLux = 0;
        measuredDark.mAmbientLuxValid = true;
        measuredDark.mRecentLightSamples = 1;
        measuredDark.mAmbientLightRingBuffer.add(0);
        measuredDark.mScreenAutoBrightness = 0.25f;
        cycle(measuredDark, true);
        require(measuredDark.mAmbientLux == 0, "a valid zero-lux estimate must be retained");
        System.out.println("PASS: valid zero-lux estimate remains valid with reset disabled");

        Controller reset = enabled(true);
        reset.mAmbientLux = 44.4f;
        reset.mAmbientLuxValid = true;
        reset.mRecentLightSamples = 1;
        reset.mAmbientLightRingBuffer.add(44);
        reset.mScreenAutoBrightness = 0.25f;
        cycle(reset, false);
        require(Float.isNaN(reset.mPreThresholdLux), "reset must invalidate threshold lux");
        System.out.println("PASS: reset-enabled policy clears a valid estimate");
    }
}
"""


def extract_method(source):
    text = source.read_text()
    signature = "private boolean setLightSensorEnabled(boolean enable)"
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 1
    cursor = opening + 1
    while depth:
        if text[cursor] == "{":
            depth += 1
        elif text[cursor] == "}":
            depth -= 1
        cursor += 1
    return text[start:cursor]


def main():
    default_source = Path(__file__).resolve().parents[3] / (
        "core/java/com/android/server/display/AutomaticBrightnessController.java")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=default_source)
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    javac = str(args.java_home / "bin/javac") if args.java_home else shutil.which("javac")
    java = str(args.java_home / "bin/java") if args.java_home else shutil.which("java")
    if not javac or not java:
        parser.error("provide --java-home or put the declared JDK on PATH")
    method = extract_method(args.source)
    with tempfile.TemporaryDirectory(prefix="ambient-lux-warmup-") as directory:
        harness = Path(directory) / "AmbientLuxWarmupRegression.java"
        harness.write_text(JAVA.replace("@METHOD@", method))
        subprocess.run([javac, "-Xlint:all", "-Werror", "-d", directory, str(harness)],
                       check=True)
        subprocess.run([java, "-ea", "-cp", directory, "AmbientLuxWarmupRegression"],
                       check=True)


if __name__ == "__main__":
    main()
