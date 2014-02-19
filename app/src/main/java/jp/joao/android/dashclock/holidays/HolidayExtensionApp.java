package jp.joao.android.dashclock.holidays;

import android.app.Application;

import com.crashlytics.android.Crashlytics;

import timber.log.Timber;

public class HolidayExtensionApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        Crashlytics.start(this);

        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        } else {
            Timber.plant(new CrashlyticsTree());
        }
    }

    /**
     * A logging implementation which reports 'info', 'warning', and 'error' logs to Crashlytics.
     */
    public static class CrashlyticsTree extends Timber.HollowTree {
        @Override
        public void i(String message, Object... args) {
            logMessage(message, args);
        }

        @Override
        public void i(Throwable t, String message, Object... args) {
            logMessage(message, args);
            // NOTE: We are explicitly not sending the exception to Crashlytics here.
        }

        @Override
        public void w(String message, Object... args) {
            logMessage("WARN: " + message, args);
        }

        @Override
        public void w(Throwable t, String message, Object... args) {
            logMessage("WARN: " + message, args);
            // NOTE: We are explicitly not sending the exception to Crashlytics here.
        }

        @Override
        public void e(String message, Object... args) {
            logMessage("ERROR: " + message, args);
        }

        @Override
        public void e(Throwable t, String message, Object... args) {
            logMessage("ERROR: " + message, args);
            Crashlytics.logException(t);
        }

        private void logMessage(String message, Object... args) {
            Crashlytics.log(String.format(message, args));
        }
    }
}
