/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
//Switched to layout inflation instead of canvas drawn using this guide
// https://sterlingudell.wordpress.com/2015/05/10/layout-based-watch-faces-for-android-wear/
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{
        static final int MSG_UPDATE_TIME = 0;
        private final String TAG=Engine.class.getSimpleName();
        /**
         * Handler to update the time periodically in interactive mode.
         */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs = INTERACTIVE_UPDATE_RATE_MS
                                    - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        boolean mRegisteredTimeZoneReceiver = false;


        boolean mAmbient;

        Calendar mCalendar;

        private int specW, specH;
        private View mLayout;

        private TextView time;
        private TextView date;
        private TextView high;
        private TextView low;
        private ImageView imageView;
        private final Point displaySize = new Point();
        float mXOffset;
        float mYOffset;
        private int highTemp=0;
        private int lowTemp=0;
        private int iconInt;
        private GoogleApiClient mGoogleApiClient;
        private final String PATH = "/watch_face/data";
        private final String KEY_WEATHER_ID = "weather_id";
        private final String KEY_TEMP_HIGH = "temp_high";
        private final String KEY_TEMP_LOW = "temp_low";

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);


            mCalendar = Calendar.getInstance();

            LayoutInflater inflater =
                    (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mLayout = inflater.inflate(R.layout.rect_watchface, null);

            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            display.getSize(displaySize);

            specW = View.MeasureSpec.makeMeasureSpec(displaySize.x,
                    View.MeasureSpec.EXACTLY);
            specH = View.MeasureSpec.makeMeasureSpec(displaySize.y,
                    View.MeasureSpec.EXACTLY);

            time = (TextView) mLayout.findViewById(R.id.time);
            date = (TextView) mLayout.findViewById(R.id.date);
            high = (TextView) mLayout.findViewById(R.id.high);
            low = (TextView) mLayout.findViewById(R.id.low);
            imageView = (ImageView) mLayout.findViewById(R.id.image);
            iconInt = R.drawable.art_clear;
            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
            mGoogleApiClient.connect();
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Log.e(TAG, "connected");
        }
        @Override
        public void onConnectionSuspended(int cause) {
            Log.e(TAG, "Suspended: " + String.valueOf(cause));

        }

        @Override
        public void onConnectionFailed(ConnectionResult result) {
            Log.e(TAG, "ConnectionFailed: " + result);

        }
        //https://developer.android.com/training/wearables/data-layer/data-items.html
        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().equals(PATH)) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        updateWeather(dataMap);
                    }
                } else if (event.getType() == DataEvent.TYPE_DELETED) {
                    // DataItem deleted
                }
            }
        }

        public void updateWeather(DataMap dataMap){
            if (dataMap != null){
                if(dataMap.containsKey(KEY_TEMP_HIGH)
                        && dataMap.containsKey(KEY_TEMP_HIGH)
                        && dataMap.containsKey(KEY_TEMP_HIGH)){
                    highTemp = (int) dataMap.getDouble(KEY_TEMP_HIGH);
                    lowTemp = (int)dataMap.getDouble(KEY_TEMP_LOW);
                    iconInt = getIcon(dataMap.getInt(KEY_WEATHER_ID));
                }
            }
        }
        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }


        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {

                registerReceiver();
                mGoogleApiClient.connect();
                // Update time zone in case it changed while we weren't visible.

                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()){
                    mGoogleApiClient.disconnect();
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);


        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {

                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mCalendar.setTimeInMillis(System.currentTimeMillis());
            String timeString;
            String dateString;
            if (mCalendar.get(Calendar.MINUTE) < 10) {
                 timeString = mCalendar.get(Calendar.HOUR_OF_DAY) + ":0" + mCalendar.get(Calendar.MINUTE);
            }else{
                timeString = mCalendar.get(Calendar.HOUR_OF_DAY) + ":" + mCalendar.get(Calendar.MINUTE);
            }
            dateString=getDate(mCalendar);
            time.setText(timeString);
            date.setText(dateString);
            high.setText(highTemp + "\u00B0");
            low.setText(lowTemp + "\u00B0");
            imageView.setImageResource(iconInt);

            mLayout.measure(specW, specH);
            mLayout.layout(0, 0, mLayout.getMeasuredWidth(),
                    mLayout.getMeasuredHeight());
            //canvas.drawColor(Color.BLACK);
            mLayout.draw(canvas);
        }

        public String getDate(Calendar calendar){
            String stringDate;
            switch(calendar.get(Calendar.DAY_OF_WEEK)){
                case 1:
                    stringDate="SUN, ";
                    break;
                case 2:
                    stringDate="MON, ";
                    break;
                case 3:
                    stringDate="TUE, ";
                    break;
                case 4:
                    stringDate="WED, ";
                    break;
                case 5:
                    stringDate="THU, ";
                    break;
                case 6:
                    stringDate="FRI, ";
                    break;
                case 7:
                    stringDate="SAT, ";
                    break;
                default:
                    stringDate="";
            }
            switch (calendar.get(Calendar.MONTH)){
                case 0:
                    stringDate=stringDate+"JAN ";
                    break;
                case 1:
                    stringDate=stringDate+"FEB ";
                    break;
                case 2:
                    stringDate=stringDate+"MAR ";
                    break;
                case 3:
                    stringDate=stringDate+"APR ";
                    break;
                case 4:
                    stringDate=stringDate+"MAY ";
                    break;
                case 5:
                    stringDate=stringDate+"JUN ";
                    break;
                case 6:
                    stringDate=stringDate+"JUL ";
                    break;
                case 7:
                    stringDate=stringDate+"AUG ";
                    break;
                case 8:
                    stringDate=stringDate+"SEP ";
                    break;
                case 9:
                    stringDate=stringDate+"OCT ";
                    break;
                case 10:
                    stringDate=stringDate+"NOV ";
                    break;
                case 11:
                    stringDate=stringDate+"DEC ";
                    break;
                default:
                    break;
            }
            stringDate=stringDate+ calendar.get(Calendar.DAY_OF_MONTH)+ " " + calendar.get(Calendar.YEAR);
            return stringDate;
        }
        //from app Utility
        public int getIcon(int weatherId) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            }
            return -1;
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

    }
}
