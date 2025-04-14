/*
Copyright (c) 2017-2019 Divested Computing Group

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package wv.applemaps;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private WebView mapsWebView = null;
    private WebSettings mapsWebSettings = null;
    private final Context context = this;
    private LocationManager locationManager;

    private static final ArrayList<String> allowedDomains = new ArrayList<>();
    private static final ArrayList<String> allowedDomainsStart = new ArrayList<>();
    private static final ArrayList<String> allowedDomainsEnd = new ArrayList<>();
    private static final ArrayList<String> allowedURLs = new ArrayList<>();
    private static final ArrayList<String> blockedURLs = new ArrayList<>();

    private static final String TAG = "AppleMapsWV";
    private static LocationListener locationListenerGPS;
    private static int locationRequestCount = 0;

    @Override
    protected void onPause() {
        super.onPause();
        if (locationListenerGPS != null) removeLocationListener();
    }

    @Override
    protected void onResume() {
        super.onResume();
        locationListenerGPS = getNewLocationListener();
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListenerGPS);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setTheme(android.R.style.Theme_DeviceDefault_DayNight);
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        String urlToLoad = "https://maps.apple.com/";
        try {
            Intent intent = getIntent();
            Uri data = intent.getData();
            urlToLoad = data.toString();
            if (data.toString().startsWith("https://")) {
                urlToLoad = data.toString();
            } else if (data.toString().startsWith("geo:")) {
                //TODO add support for later
                urlToLoad = "https://maps.apple.com/frame?center=" + data.toString().substring(4);
            }
        } catch (Exception e) {
            Log.d(TAG, "No or Invalid URL passed. Opening homepage instead.");
        }

        //Create the WebView
        mapsWebView = findViewById(R.id.mapsWebView);

        //Set cookie options
        resetWebView(false);
        // As of right now, not accepting cookies works
        CookieManager.getInstance().setAcceptCookie(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(mapsWebView, false);
        //Deprecated
        CookieManager.setAcceptFileSchemeCookies(false);
        initURLs();

        //Lister for Link sharing
        initShareLinkListener();

        //Give location access
        mapsWebView.setWebChromeClient(new WebChromeClient() {
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    if (locationRequestCount < 2) { //Don't annoy the user
                        new AlertDialog.Builder(context)
                                .setTitle(R.string.title_location_permission)
                                .setMessage(R.string.text_location_permission)
                                .setNegativeButton(android.R.string.no, (dialogInterface, i) -> {
                                    //Disable prompts
                                    locationRequestCount = 100;
                                }).setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                                    //Prompt the user once explanation has been shown
                                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 100);
                                })
                                .create()
                                .show();
                    }
                    locationRequestCount++;
                } else {
                    if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        Toast.makeText(context, R.string.error_no_gps, Toast.LENGTH_LONG).show();
                    }
                }
                if (origin.contains("apple.com")) {
                    callback.invoke(origin, true, false);
                }
            }
        });

        mapsWebView.setWebViewClient(new WebViewClient() {
            //Keep these in sync!
            @Override
            public WebResourceResponse shouldInterceptRequest(final WebView view, WebResourceRequest request) {
                if (request.getUrl().toString().equals("about:blank")) {
                    return null;
                }
                // TODO migrate to use URLUtil
                if (!URLUtil.isHttpsUrl(request.getUrl().toString())) {
                // if (!request.getUrl().toString().startsWith("https://")) {
                    //TODO implement try to upgrade http to https and block the request if it fails
                    Log.d(TAG, "[shouldInterceptRequest][NON-HTTPS] Blocked access to " + request.getUrl().toString());
                    return new WebResourceResponse("text/javascript", "UTF-8", null); //Deny URLs that aren't HTTPS
                }
                boolean allowed = false;
                for (String url : allowedDomains) {
                    if (request.getUrl().getHost().equals(url)) {
                        allowed = true;
                    }
                }
                for (String url : allowedDomainsStart) {
                    if (request.getUrl().getHost().startsWith(url)) {
                        allowed = true;
                    }
                }
                for (String url : allowedDomainsEnd) {
                    if (request.getUrl().getHost().endsWith(url)) {
                        allowed = true;
                    }
                }
                for (String url : allowedURLs) {
                    if (request.getUrl().toString().contains(url)) {
                        allowed = true;
                    }
                }
                if (!allowed) {
                    Log.d(TAG, "[shouldInterceptRequest][NOT ON ALLOWLIST] Blocked access to " + request.getUrl().getHost());
                    return new WebResourceResponse("text/javascript", "UTF-8", null); //Deny URLs not on ALLOWLIST
                }
                for (String url : blockedURLs) {
                    if (request.getUrl().toString().contains(url)) {
                        Log.d(TAG, "[shouldInterceptRequest][ON DENYLIST] Blocked access to " + request.getUrl().toString());
                        return new WebResourceResponse("text/javascript", "UTF-8", null); //Deny URLs on DENYLIST
                    }
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.getUrl().toString().equals("about:blank")) {
                    return false;
                }
                if (request.getUrl().toString().startsWith("tel:")) {
                    Intent dial = new Intent(Intent.ACTION_DIAL, request.getUrl());
                    startActivity(dial);
                    return true;
                }
                if (URLUtil.isJavaScriptUrl(request.getUrl().toString())) {
                    Log.d(TAG, "[shouldOverrideUrlLoading] Blocked access to javascript URI" + request.getUrl().toString());
                    return true; //Block loading javascript uris
                }
                if (!request.getUrl().toString().startsWith("https://")) {
                    Log.d(TAG, "[shouldOverrideUrlLoading][NON-HTTPS] Blocked access to " + request.getUrl().toString());
                    return true; //Deny URLs that aren't HTTPS
                }
                boolean allowed = false;
                for (String url : allowedDomains) {
                    if (request.getUrl().getHost().equals(url)) {
                        allowed = true;
                    }
                }
                for (String url : allowedDomainsStart) {
                    if (request.getUrl().getHost().startsWith(url)) {
                        allowed = true;
                    }
                }
                for (String url : allowedDomainsEnd) {
                    if (request.getUrl().getHost().endsWith(url)) {
                        allowed = true;
                    }
                }
                for (String url : allowedURLs) {
                    if (request.getUrl().toString().contains(url)) {
                        allowed = true;
                    }
                }
                if (!allowed) {
                    Log.d(TAG, "[shouldOverrideUrlLoading][NOT ON ALLOWLIST] Blocked access to " + request.getUrl().getHost());
                    if (request.getUrl().toString().startsWith("https://")) {
                        (new AlertDialog.Builder(context)
                            .setTitle(R.string.title_open_link)
                            .setMessage(context.getString(R.string.text_open_link, request.getUrl().toString()))
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(
                                android.R.string.ok,
                                (dialogInterface, i) ->
                                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()))
                            )
                        )
                        .create()
                        .show();
                    }

                    return true; //Deny URLs not on ALLOWLIST
                }
                for (String url : blockedURLs) {
                    if (request.getUrl().toString().contains(url)) {
                        Log.d(TAG, "[shouldOverrideUrlLoading][ON DENYLIST] Blocked access to " + request.getUrl().toString());
                        return true; //Deny URLs on DENYLIST
                    }
                }
                return false;
            }
        });

        //Set more options
        mapsWebSettings = mapsWebView.getSettings();
        //Enable some WebView features
        mapsWebSettings.setJavaScriptEnabled(true);
        mapsWebSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        mapsWebSettings.setGeolocationEnabled(true);
        //Disable some WebView features
        mapsWebSettings.setAllowContentAccess(false);
        mapsWebSettings.setAllowFileAccess(false);
        mapsWebSettings.setBuiltInZoomControls(false);
        mapsWebSettings.setDatabaseEnabled(false);
        mapsWebSettings.setDisplayZoomControls(false);
        mapsWebSettings.setDomStorageEnabled(false);
        mapsWebSettings.setAllowUniversalAccessFromFileURLs(false);
        mapsWebSettings.setJavaScriptCanOpenWindowsAutomatically(false);
        mapsWebSettings.setSupportMultipleWindows(false);
        mapsWebSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        mapsWebSettings.setSafeBrowsingEnabled(false);
        //Deprecated
        mapsWebSettings.setAllowFileAccessFromFileURLs(false);

        //Change the User-Agent
        //https://chromium.googlesource.com/chromium/src.git/+/HEAD/docs/ios/user_agent.md
        mapsWebSettings.setUserAgentString("Mozilla/5.0 (iPhone; CPU iPhone OS 18_3_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/134.0.6998.99 Mobile/15E148 Safari/604.");

        //Load Google Maps
        mapsWebView.loadUrl(urlToLoad);
    }

    @Override
    protected void onDestroy() {
        resetWebView(true);
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        //Credit (CC BY-SA 3.0): https://stackoverflow.com/a/6077173
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BACK:
                    if (mapsWebView.canGoBack() && !mapsWebView.getUrl().equals("about:blank")) {
                        mapsWebView.goBack();
                    } else {
                        finish();
                    }
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void resetWebView(boolean exit) {
        mapsWebView.stopLoading();
        mapsWebView.clearFormData();
        mapsWebView.clearHistory();
        mapsWebView.clearMatches();
        mapsWebView.clearSslPreferences();
        mapsWebView.clearCache(true);
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().removeSessionCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
        // Probably not necessary
        WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword();
        if (exit) {
            mapsWebView.loadUrl("about:blank");
            mapsWebView.removeAllViews();
            mapsWebSettings.setJavaScriptEnabled(false);
            mapsWebView.destroyDrawingCache();
            mapsWebView.destroy();
            mapsWebView = null;
        }
    }

    private static void initURLs() {
        //Allowed Domains
        allowedDomains.add("maps.apple.com");
        allowedDomains.add("cdn.apple-mapkit.com");
        allowedDomains.add("sat-cdn.apple-mapkit.com");

        //TODO Add a setting for allowing/blocking 3rd party domains at activitystart for each new activity
        // 3p domains

        // For loading images from yelp, tripadvisor and foursquare
        // example: https://is1-ssl.mzstatic.com and https://is3-ssl.mzstatic.com
        allowedDomainsEnd.add("-ssl.mzstatic.com");
        allowedDomainsEnd.add(".4sqi.net");
        // example: https://media-cdn.tripadvisor.com/media/photo-o/2a/45/2c/80/crazy-about-you.jpg
        allowedDomains.add("media-cdn.tripadvisor.com");
        // example: https://s3-media0.fl.yelpcdn.com/bphoto/H9Of-lnb-ZAlmQKgFiMacQ/o.jpg
        allowedDomainsEnd.add(".fl.yelpcdn.com");
        // example: https://images.otstatic.com/prod1/49200509/3/huge.jpg
        allowedDomains.add("images.otstatic.com");
        allowedDomains.add("resizer.otstatic.com");

        // for clicking on "More" under a review under "Ratings & Reviews"
//        allowedURLs.add("https://yelp.com/apple_maps_action");
//        allowedURLs.add("https://www.tripadvisor.com/AppleMapsAction");

        // for loading platform (e.g. yelp) icons under the review summary (e.g. 4.5 stars with 40 reviews)
        allowedDomains.add("gspe21-ssl.ls.apple.com");

        // TODO figure out what is for
//        cdn.parkopedia.com

        //Blocked Domains
        blockedURLs.add("gsp10.apple-mapkit.com");
        blockedURLs.add("xp.apple.com");

        //Blocked URLs
        blockedURLs.add("maps.apple.com/data/performanceAnalytics");
        blockedURLs.add("maps.apple.com/data/analyticsStatus");
        blockedURLs.add("/mw/v1/reportAnalytics");
        blockedURLs.add("/reportAnalytics");
        blockedURLs.add("/report/2/xp_amp_web_perf_log");
        blockedURLs.add("/xp_amp_web_perf_log");
    }

    private LocationListener getNewLocationListener() {
        return new LocationListener() {
            @Override
            public void onLocationChanged(android.location.Location location) {
            }

            @Deprecated
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
    }

    private void removeLocationListener() {
        if (locationListenerGPS != null) {
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (locationListenerGPS != null) locationManager.removeUpdates(locationListenerGPS);
        }
        locationListenerGPS = null;
    }

    private void initShareLinkListener() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.addPrimaryClipChangedListener(new ClipboardManager.OnPrimaryClipChangedListener() {
            @Override
            public void onPrimaryClipChanged() {
                String url = mapsWebView.getUrl();
                if (url != null) {
                    // TODO check the regex
                    String regex = "@(-?d*\\d+.\\d+),(-?d*\\d+.\\d+)";
                    Pattern p = Pattern.compile(regex);
                    Matcher m = p.matcher(url);
                    if (m.find()) {
                        String latlon = m.group(1) + "," + m.group(2);
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + latlon + "?q=" + latlon)));
                        } catch (ActivityNotFoundException ignored) {
                            Toast.makeText(context, R.string.no_app_installed, Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
        });
    }
}
