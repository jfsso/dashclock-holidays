package jp.joao.android.dashclock.holidays;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import com.google.android.apps.dashclock.api.DashClockExtension;
import com.google.android.apps.dashclock.api.ExtensionData;
import com.squareup.okhttp.OkHttpClient;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.filter.Filter;
import net.fortuna.ical4j.filter.PeriodRule;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.DateTime;
import net.fortuna.ical4j.model.Dur;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.component.VEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;

import hugo.weaving.DebugLog;
import timber.log.Timber;

public class HolidaysExtension extends DashClockExtension {

    private static final int CALENDAR_TIME_CHANGED = 10001;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd");

    public static final String PREF_CALENDAR = "pref_calendar";
    public static final String PREF_LANG = "pref_lang";
    public static final String PREF_CACHE = "cache";

    private volatile Looper mServiceLooper;
    private volatile Handler mServiceHandler;

    private String calendarId;
    private String lang;

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            onUpdateData(CALENDAR_TIME_CHANGED);
        }
    };

    @DebugLog
    @Override
    protected void onInitialize(boolean isReconnect) {
        super.onInitialize(isReconnect);
        setUpdateWhenScreenOn(true);

        // Get preference value.
        readPreferences();
    }

    @DebugLog
    @Override
    public void onCreate() {
        super.onCreate();

        HandlerThread thread = new HandlerThread("HolidaysExtension:" + getClass().getSimpleName());
        thread.start();

        mServiceLooper = thread.getLooper();
        mServiceHandler = new Handler(mServiceLooper);

        registerReceiver();
    }

    @DebugLog
    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver();

        mServiceHandler.removeCallbacksAndMessages(null); // remove all callbacks
        mServiceLooper.quit();
    }

    @DebugLog
    @Override
    protected void onUpdateData(int reason) {
        readPreferences();

        if (TextUtils.isEmpty(calendarId)) {
            Intent intent = new Intent(getApplicationContext(), SettingsActivity.class);

            ExtensionData extensionData = new ExtensionData()
                    .visible(true)
                    .status(getString(R.string.not_configured))
                    .clickIntent(intent)
                    .icon(R.drawable.ic_launcher);
            publishUpdate(extensionData);
            return;
        }

        Timber.i("calendar: %s", calendarId);

        java.util.Calendar today = java.util.Calendar.getInstance();
        today.set(java.util.Calendar.HOUR_OF_DAY, 0);
        today.clear(java.util.Calendar.MINUTE);
        today.clear(java.util.Calendar.SECOND);
        today.clear(java.util.Calendar.MILLISECOND);

        today.setTimeZone(TimeZone.getTimeZone("UTC"));

        if (useCache(today, reason)) {
            return;
        }

        NetworkInfo networkInfo = ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        if (networkInfo == null || !networkInfo.isConnected()) {
            Timber.i("No network connection; not attempting to update holiday.");
            return;
        }

        DateTime dateTime = new DateTime(today.getTime());
        Timber.i("date: " + dateTime.toString());

        Period period = new Period(dateTime, new Dur(0, 23, 59, 59));
        Filter filter = new Filter(new PeriodRule(period));

        //AssetManager assets = getApplicationContext().getAssets();
        InputStream in = null;

        String status = null;
        String expandedTitle = null;
        String expandedBody = null;

        try {
            OkHttpClient client = new OkHttpClient();
            URL url = new URL(getString(R.string.calendar_api_url, lang, URLEncoder.encode(calendarId, "UTF-8")));
            HttpURLConnection connection = client.open(url);
            in = connection.getInputStream();

            CalendarBuilder builder = new CalendarBuilder();

            Calendar calendar = builder.build(in);

            List eventsToday = (List) filter.filter(calendar.getComponents(Component.VEVENT));
            if (eventsToday.size() > 0) {
                if (eventsToday.size() == 1) {
                    VEvent component = (VEvent) eventsToday.get(0);
                    status = component.getSummary().getValue();
                } else {
                    StringBuilder sb = new StringBuilder();
                    Timber.i(eventsToday.size() + " holidays today");
                    status = getString(R.string.multiple_holidays, eventsToday.size());
                    expandedTitle = status;
                    for (Object o : eventsToday) {
                        VEvent component = (VEvent) o;
                        Timber.i(component.getSummary().getValue());
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }

                        sb.append(component.getSummary().getValue());
                    }
                    expandedBody = sb.toString();
                }

            }
            publishHoliday(status, expandedTitle, expandedBody);

            // cache
            JSONArray cacheArray = new JSONArray();
            cacheArray.put(calendarId);
            cacheArray.put(lang);
            cacheArray.put(DATE_FORMAT.format(today.getTime()));
            JSONArray extensionArray = new JSONArray();
            extensionArray.put(status != null ? status : JSONObject.NULL);
            extensionArray.put(expandedTitle != null ? expandedTitle : JSONObject.NULL);
            extensionArray.put(expandedBody != null ? expandedBody : JSONObject.NULL);
            cacheArray.put(extensionArray);
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
            sp.edit().putString(PREF_CACHE, cacheArray.toString()).apply();
            Timber.i("saved to cache");
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ParserException e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    @DebugLog
    public boolean useCache(java.util.Calendar today, int reason) {
        // read cache
        if (reason == UPDATE_REASON_INITIAL || reason == UPDATE_REASON_MANUAL) {
            return false;
        }

        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
            String cache = sp.getString(PREF_CACHE, null);
            if (cache != null) {
                JSONArray cacheArray = new JSONArray(cache);
                // check if calendar id is different
                if (!calendarId.equals(cacheArray.getString(0))) {
                    Timber.i("calendar id is different");
                    clearCache();
                    unpublishHoliday();
                    return false;
                }

                // check if lang id is different
                if (!lang.equals(cacheArray.getString(1))) {
                    Timber.i("lang is different");
                    clearCache();
                    unpublishHoliday();
                    return false;
                }

                // check if date is different than today's date
                if (!DATE_FORMAT.format(today.getTime()).equals(cacheArray.getString(2))) {
                    Timber.i("date is different" + DATE_FORMAT.format(today.getTime()) + "!= " + cacheArray.getString(1));
                    clearCache();
                    unpublishHoliday();
                    return false;
                }

                // display cached holiday
                JSONArray extensionData = new JSONArray(cacheArray.getString(3));
                String status = extensionData.isNull(0) ? null : extensionData.getString(0);
                String expandedTitle = extensionData.isNull(1) ? null : extensionData.getString(1);
                String expandedBody = extensionData.isNull(2) ? null : extensionData.getString(2);

                publishHoliday(status, expandedTitle, expandedBody);

                return true;
            }
        } catch (JSONException e) {
            Timber.e(e, e.getMessage());
        }
        return false;
    }

    @DebugLog
    public void clearCache() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        sp.edit().putString(PREF_CACHE, null).apply();

    }

    @DebugLog
    public void publishHoliday(String status, String expandedTitle, String expandedBody) {
        ExtensionData extensionData = new ExtensionData()
                .visible(!TextUtils.isEmpty(status) || !TextUtils.isEmpty(expandedTitle) || !TextUtils.isEmpty(expandedBody))
                .icon(R.drawable.ic_launcher)
                .status(status)
                .expandedTitle(expandedTitle)
                .expandedBody(expandedBody);
        publishUpdate(extensionData);
    }

    public void unpublishHoliday() {
        ExtensionData extensionData = new ExtensionData()
                .visible(false);
        publishUpdate(extensionData);
    }

    private void registerReceiver() {
        final IntentFilter filter = new IntentFilter();

        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);

        getApplicationContext().registerReceiver(mIntentReceiver, filter, null, mServiceHandler);
    }

    private void unregisterReceiver() {
        getApplicationContext().unregisterReceiver(mIntentReceiver);
    }

    private void readPreferences() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        calendarId = sp.getString(PREF_CALENDAR, null);
        lang = sp.getString(PREF_LANG, "en");
    }
}
