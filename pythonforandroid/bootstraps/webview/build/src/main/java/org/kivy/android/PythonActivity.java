
package org.kivy.android;

import java.net.Socket;
import java.net.InetSocketAddress;

import android.os.SystemClock;

import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;

import android.app.*;
import android.content.*;
import android.view.*;
import android.view.ViewGroup;
import android.view.SurfaceView;
import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PowerManager;
import android.graphics.PixelFormat;
import android.view.SurfaceHolder;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
import android.content.Intent;
import android.widget.ImageView;
import java.io.InputStream;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import android.widget.AbsoluteLayout;
import android.view.ViewGroup.LayoutParams;

import android.webkit.WebViewClient;
import android.webkit.WebView;

import org.kivy.android.PythonUtil;

import org.kivy.android.WebViewLoader;

import org.renpy.android.ResourceManager;
import org.renpy.android.AssetExtract;

public class PythonActivity extends Activity {
    // This activity is modified from a mixture of the SDLActivity and
    // PythonActivity in the SDL2 bootstrap, but removing all the SDL2
    // specifics.

    private static final String TAG = "PythonActivity";

    public static PythonActivity mActivity = null;

    /** If shared libraries (e.g. SDL or the native application) could not be loaded. */
    public static boolean mBrokenLibraries;

    protected static ViewGroup mLayout;
    public static WebView mWebView;

    protected static Thread mPythonThread;

    private ResourceManager resourceManager = null;
    private Bundle mMetaData = null;
    private PowerManager.WakeLock mWakeLock = null;

    public String getAppRoot() {
        String app_root =  getFilesDir().getAbsolutePath() + "/app";
        return app_root;
    }

    public String getEntryPoint(String search_dir) {
        /* Get the main file (.pyc|.pyo|.py) depending on if we
         * have a compiled version or not.
        */
        List<String> entryPoints = new ArrayList<String>();
        entryPoints.add("main.pyo");  // python 2 compiled files
        entryPoints.add("main.pyc");  // python 3 compiled files
		for (String value : entryPoints) {
            File mainFile = new File(search_dir + "/" + value);
            if (mainFile.exists()) {
                return value;
            }
        }
        return "main.py";
    }

    public static void initialize() {
        // The static nature of the singleton and Android quirkyness force us to initialize everything here
        // Otherwise, when exiting the app and returning to it, these variables *keep* their pre exit values
        mWebView = null;
        mLayout = null;
        mBrokenLibraries = false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "My oncreate running");
        resourceManager = new ResourceManager(this);
        super.onCreate(savedInstanceState);

        this.mActivity = this;
        this.showLoadingScreen();
        new UnpackFilesTask().execute(getAppRoot());
    }

    private class UnpackFilesTask extends AsyncTask<String, Void, String> {
        @Override
        protected String doInBackground(String... params) {
            File app_root_file = new File(params[0]);
            Log.v(TAG, "Ready to unpack");
            unpackData("private", app_root_file);
            return null;
        }

        @Override
        protected void onPostExecute(String result) {
            Log.v("Python", "Device: " + android.os.Build.DEVICE);
            Log.v("Python", "Model: " + android.os.Build.MODEL);

            PythonActivity.initialize();

            // Load shared libraries
            String errorMsgBrokenLib = "";
            try {
                loadLibraries();
            } catch(UnsatisfiedLinkError e) {
                System.err.println(e.getMessage());
                mBrokenLibraries = true;
                errorMsgBrokenLib = e.getMessage();
            } catch(Exception e) {
                System.err.println(e.getMessage());
                mBrokenLibraries = true;
                errorMsgBrokenLib = e.getMessage();
            }

            if (mBrokenLibraries)
            {
                AlertDialog.Builder dlgAlert  = new AlertDialog.Builder(PythonActivity.mActivity);
                dlgAlert.setMessage("An error occurred while trying to load the application libraries. Please try again and/or reinstall."
                      + System.getProperty("line.separator")
                      + System.getProperty("line.separator")
                      + "Error: " + errorMsgBrokenLib);
                dlgAlert.setTitle("Python Error");
                dlgAlert.setPositiveButton("Exit",
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog,int id) {
                            // if this button is clicked, close current activity
                            PythonActivity.mActivity.finish();
                        }
                    });
               dlgAlert.setCancelable(false);
               dlgAlert.create().show();

               return;
            }

            // Set up the webview
            String app_root_dir = getAppRoot();

            mWebView = new WebView(PythonActivity.mActivity);
            mWebView.getSettings().setJavaScriptEnabled(true);
            mWebView.getSettings().setDomStorageEnabled(true);
            mWebView.loadUrl("file:///" + app_root_dir + "/_load.html");

            mWebView.setLayoutParams(new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT));
            mWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        view.loadUrl(url);
                        return false;
                    }
                });
            mLayout = new AbsoluteLayout(PythonActivity.mActivity);
            mLayout.addView(mWebView);

            setContentView(mLayout);
            // keep the loading screen up until the webview load fully completes
            PythonActivity.mActivity.showLoadingScreen();

            String mFilesDirectory = mActivity.getFilesDir().getAbsolutePath();
            String entry_point = getEntryPoint(app_root_dir);

            Log.v(TAG, "Setting env vars for start.c and Python to use");
            PythonActivity.nativeSetenv("ANDROID_ENTRYPOINT", entry_point);
            PythonActivity.nativeSetenv("ANDROID_ARGUMENT", app_root_dir);
            PythonActivity.nativeSetenv("ANDROID_APP_PATH", app_root_dir);
            PythonActivity.nativeSetenv("ANDROID_PRIVATE", mFilesDirectory);
            PythonActivity.nativeSetenv("ANDROID_UNPACK", app_root_dir);
            PythonActivity.nativeSetenv("PYTHONHOME", app_root_dir);
            PythonActivity.nativeSetenv("PYTHONPATH", app_root_dir + ":" + app_root_dir + "/lib");
            PythonActivity.nativeSetenv("PYTHONOPTIMIZE", "2");

            try {
                Log.v(TAG, "Access to our meta-data...");
                mActivity.mMetaData = mActivity.getPackageManager().getApplicationInfo(
                        mActivity.getPackageName(), PackageManager.GET_META_DATA).metaData;

                PowerManager pm = (PowerManager) mActivity.getSystemService(Context.POWER_SERVICE);
                if ( mActivity.mMetaData.getInt("wakelock") == 1 ) {
                    mActivity.mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Screen On");
                    mActivity.mWakeLock.acquire();
                }
            } catch (PackageManager.NameNotFoundException e) {
            }

            final Thread pythonThread = new Thread(new PythonMain(), "PythonThread");
            PythonActivity.mPythonThread = pythonThread;
            pythonThread.start();

            final Thread wvThread = new Thread(new WebViewLoaderMain(), "WvThread");
            wvThread.start();
        }
    }

    @Override
    public void onDestroy() {
        Log.i("Destroy", "end of app");
        super.onDestroy();

        // make sure all child threads (python_thread) are stopped
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public void loadLibraries() {
        String app_root = new String(getAppRoot());
        File app_root_file = new File(app_root);
        PythonUtil.loadLibraries(app_root_file,
            new File(getApplicationInfo().nativeLibraryDir));
    }

    public void recursiveDelete(File f) {
        if (f.isDirectory()) {
            for (File r : f.listFiles()) {
                recursiveDelete(r);
            }
        }
        f.delete();
    }

    /**
     * Show an error using a toast. (Only makes sense from non-UI
     * threads.)
     */
    public void toastError(final String msg) {

        final Activity thisActivity = this;

        runOnUiThread(new Runnable () {
            public void run() {
                Toast.makeText(thisActivity, msg, Toast.LENGTH_LONG).show();
            }
        });

        // Wait to show the error.
        synchronized (this) {
            try {
                this.wait(1000);
            } catch (InterruptedException e) {
            }
        }
    }

    public void unpackData(final String resource, File target) {

        Log.v(TAG, "UNPACKING!!! " + resource + " " + target.getName());

        // The version of data in memory and on disk.
        String data_version = resourceManager.getString(resource + "_version");
        String disk_version = null;

        Log.v(TAG, "Data version is " + data_version);

        // If no version, no unpacking is necessary.
        if (data_version == null) {
            return;
        }

        // Check the current disk version, if any.
        String filesDir = target.getAbsolutePath();
        String disk_version_fn = filesDir + "/" + resource + ".version";

        try {
            byte buf[] = new byte[64];
            InputStream is = new FileInputStream(disk_version_fn);
            int len = is.read(buf);
            disk_version = new String(buf, 0, len);
            is.close();
        } catch (Exception e) {
            disk_version = "";
        }

        // If the disk data is out of date, extract it and write the
        // version file.
        // if (! data_version.equals(disk_version)) {
        if (! data_version.equals(disk_version)) {
            Log.v(TAG, "Extracting " + resource + " assets.");

            recursiveDelete(target);
            target.mkdirs();

            AssetExtract ae = new AssetExtract(this);
            if (!ae.extractTar(resource + ".mp3", target.getAbsolutePath())) {
                toastError("Could not extract " + resource + " data.");
            }

            try {
                // Write .nomedia.
                new File(target, ".nomedia").createNewFile();

                // Write version file.
                FileOutputStream os = new FileOutputStream(disk_version_fn);
                os.write(data_version.getBytes());
                os.close();
            } catch (Exception e) {
                Log.w("python", e);
            }
        }
    }

    public static void loadUrl(String url) {
        class LoadUrl implements Runnable {
            private String mUrl;

            public LoadUrl(String url) {
                mUrl = url;
            }

            public void run() {
                mWebView.loadUrl(mUrl);
            }
        }

        Log.i(TAG, "Opening URL: " + url);
        mActivity.runOnUiThread(new LoadUrl(url));
    }

    public static ViewGroup getLayout() {
        return   mLayout;
    }

    long lastBackClick = SystemClock.elapsedRealtime();
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Check if the key event was the Back button and if there's history
        if ((keyCode == KeyEvent.KEYCODE_BACK)) {
            if(mWebView.canGoBack()) {
                mWebView.goBack();
                return true;
            }
            if (SystemClock.elapsedRealtime() - lastBackClick > 2000){
                lastBackClick = SystemClock.elapsedRealtime();
                Toast.makeText(this, "Click again to close the app",
                Toast.LENGTH_LONG).show();
                return true;
            }
        }
        
        // If it wasn't the Back key or there's no web page history, bubble up to the default
        // system behavior (probably exit the activity)
        return super.onKeyDown(keyCode, event);
    }

    // loading screen implementation
    public static ImageView mImageView = null;
    public void removeLoadingScreen() {
      runOnUiThread(new Runnable() {
        public void run() {
          if (PythonActivity.mImageView != null &&
                  PythonActivity.mImageView.getParent() != null) {
            ((ViewGroup)PythonActivity.mImageView.getParent()).removeView(
            PythonActivity.mImageView);
            PythonActivity.mImageView = null;
          }
        }
      });
    }

    protected void showLoadingScreen() {
      // load the bitmap
      // 1. if the image is valid and we don't have layout yet, assign this bitmap
      // as main view.
      // 2. if we have a layout, just set it in the layout.
      // 3. If we have an mImageView already, then do nothing because it will have
      // already been made the content view or added to the layout.

      if (mImageView == null) {
        int presplashId = this.resourceManager.getIdentifier("presplash", "drawable");
        InputStream is = this.getResources().openRawResource(presplashId);
        Bitmap bitmap = null;
        try {
          bitmap = BitmapFactory.decodeStream(is);
        } finally {
          try {
            is.close();
          } catch (IOException e) {};
        }

        mImageView = new ImageView(this);
        mImageView.setImageBitmap(bitmap);

        /*
     * Set the presplash loading screen background color
         * https://developer.android.com/reference/android/graphics/Color.html
         * Parse the color string, and return the corresponding color-int.
         * If the string cannot be parsed, throws an IllegalArgumentException exception.
         * Supported formats are: #RRGGBB #AARRGGBB or one of the following names:
         * 'red', 'blue', 'green', 'black', 'white', 'gray', 'cyan', 'magenta', 'yellow',
         * 'lightgray', 'darkgray', 'grey', 'lightgrey', 'darkgrey', 'aqua', 'fuchsia',
         * 'lime', 'maroon', 'navy', 'olive', 'purple', 'silver', 'teal'.
         */
        String backgroundColor = resourceManager.getString("presplash_color");
        if (backgroundColor != null) {
          try {
            mImageView.setBackgroundColor(Color.parseColor(backgroundColor));
          } catch (IllegalArgumentException e) {}
        }
        mImageView.setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.FILL_PARENT,
        ViewGroup.LayoutParams.FILL_PARENT));
        mImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

      }

      if (mLayout == null) {
        setContentView(mImageView);
      } else if (PythonActivity.mImageView.getParent() == null){
        mLayout.addView(mImageView);
      }
    }

    //----------------------------------------------------------------------------
    // Listener interface for onNewIntent
    //

    public interface NewIntentListener {
        void onNewIntent(Intent intent);
    }

    private List<NewIntentListener> newIntentListeners = null;

    public void registerNewIntentListener(NewIntentListener listener) {
        if ( this.newIntentListeners == null )
            this.newIntentListeners = Collections.synchronizedList(new ArrayList<NewIntentListener>());
        this.newIntentListeners.add(listener);
    }

    public void unregisterNewIntentListener(NewIntentListener listener) {
        if ( this.newIntentListeners == null )
            return;
        this.newIntentListeners.remove(listener);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if ( this.newIntentListeners == null )
            return;
        this.onResume();
        synchronized ( this.newIntentListeners ) {
            Iterator<NewIntentListener> iterator = this.newIntentListeners.iterator();
            while ( iterator.hasNext() ) {
                (iterator.next()).onNewIntent(intent);
            }
        }
    }

    //----------------------------------------------------------------------------
    // Listener interface for onActivityResult
    //

    public interface ActivityResultListener {
        void onActivityResult(int requestCode, int resultCode, Intent data);
    }

    private List<ActivityResultListener> activityResultListeners = null;

    public void registerActivityResultListener(ActivityResultListener listener) {
        if ( this.activityResultListeners == null )
            this.activityResultListeners = Collections.synchronizedList(new ArrayList<ActivityResultListener>());
        this.activityResultListeners.add(listener);
    }

    public void unregisterActivityResultListener(ActivityResultListener listener) {
        if ( this.activityResultListeners == null )
            return;
        this.activityResultListeners.remove(listener);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if ( this.activityResultListeners == null )
            return;
        this.onResume();
        synchronized ( this.activityResultListeners ) {
            Iterator<ActivityResultListener> iterator = this.activityResultListeners.iterator();
            while ( iterator.hasNext() )
                (iterator.next()).onActivityResult(requestCode, resultCode, intent);
        }
    }

    public static void start_service(
            String serviceTitle,
            String serviceDescription,
            String pythonServiceArgument
            ) {
        _do_start_service(
            serviceTitle, serviceDescription, pythonServiceArgument, true
        );
    }

    public static void start_service_not_as_foreground(
            String serviceTitle,
            String serviceDescription,
            String pythonServiceArgument
            ) {
        _do_start_service(
            serviceTitle, serviceDescription, pythonServiceArgument, false
        );
    }

    public static void _do_start_service(
            String serviceTitle,
            String serviceDescription,
            String pythonServiceArgument,
            boolean showForegroundNotification
            ) {
        Intent serviceIntent = new Intent(PythonActivity.mActivity, PythonService.class);
        String argument = PythonActivity.mActivity.getFilesDir().getAbsolutePath();
        String filesDirectory = argument;
        String app_root_dir = PythonActivity.mActivity.getAppRoot();
        String entry_point = PythonActivity.mActivity.getEntryPoint(app_root_dir + "/service");
        serviceIntent.putExtra("androidPrivate", argument);
        serviceIntent.putExtra("androidArgument", app_root_dir);
        serviceIntent.putExtra("serviceEntrypoint", "service/" + entry_point);
        serviceIntent.putExtra("pythonName", "python");
        serviceIntent.putExtra("pythonHome", app_root_dir);
        serviceIntent.putExtra("pythonPath", app_root_dir + ":" + app_root_dir + "/lib");
        serviceIntent.putExtra("serviceStartAsForeground",
            (showForegroundNotification ? "true" : "false")
        );
        serviceIntent.putExtra("serviceTitle", serviceTitle);
        serviceIntent.putExtra("serviceDescription", serviceDescription);
        serviceIntent.putExtra("pythonServiceArgument", pythonServiceArgument);
        PythonActivity.mActivity.startService(serviceIntent);
    }

    public static void stop_service() {
        Intent serviceIntent = new Intent(PythonActivity.mActivity, PythonService.class);
        PythonActivity.mActivity.stopService(serviceIntent);
    }


    public static native void nativeSetenv(String name, String value);
    public static native int nativeInit(Object arguments);

}


class PythonMain implements Runnable {
    @Override
    public void run() {
        PythonActivity.nativeInit(new String[0]);
    }
}

class WebViewLoaderMain implements Runnable {
    @Override
    public void run() {
        WebViewLoader.testConnection();
    }
}
