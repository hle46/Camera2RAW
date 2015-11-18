package edu.illinois.sba.camera2raw;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.session.AppKeyPair;

import edu.illinois.sba.camera2raw.services.NetworkService;


public class WelcomeActivity extends Activity {
    private static final String TAG = "PT WELCOME ACTIVITY";
    private static final String APP_KEY = "kc1v1iej4203576";
    private static final String APP_SECRET = "220wawlygcbdu2e";

    private static final String ACCOUNT_PREFS_NAME = "prefs";
    private static final String ACCESS_KEY_NAME = "ACCESS_KEY";
    private static final String ACCESS_SECRET_NAME = "ACCESS_SECRET";

    private static final String SWITCH_PREFS = "switch_prefs";
    private static final String SWITCH_STATE = "SWITCH_STATE";

    private DropboxAPI<AndroidAuthSession> mDBApi = null;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        Button intro_next = (Button) findViewById(R.id.intro_next);
        intro_next.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent intent = new Intent(WelcomeActivity.this, CameraActivity.class);
                        startActivity(intent);
                    }
                }
        );

        // We create a new AuthSession so that we can use the Dropbox API.
        AndroidAuthSession session = buildSession();
        mDBApi = new DropboxAPI<>(session);
        Switch dropboxSync = (Switch) findViewById(R.id.dropbox_sync);
        dropboxSync.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Is the toggle on
                        boolean isOn = ((Switch) v).isChecked();
                        if (isOn) {
                            mDBApi.getSession().startOAuth2Authentication(WelcomeActivity.this);
                            NetworkService.setmDBApi(mDBApi);
                        } else {
                            if (mDBApi != null) {
                                logOut();
                            }
                            NetworkService.setmDBApi(null);
                        }
                    }
                }
        );
    }

    private void logOut() {
        // Remove credentials from the session
        mDBApi.getSession().unlink();

        // Clear our stored keys
        clearKeys();
        storeSwitchState(false);
    }

    private void clearKeys() {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        SharedPreferences.Editor edit = prefs.edit();
        edit.clear();
        edit.commit();
    }

    private AndroidAuthSession buildSession() {
        AppKeyPair appKeyPair = new AppKeyPair(APP_KEY, APP_SECRET);
        AndroidAuthSession session = new AndroidAuthSession(appKeyPair);
        loadAuth(session);
        return session;
    }

    private void loadAuth(AndroidAuthSession session) {
        SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
        String key = prefs.getString(ACCESS_KEY_NAME, null);
        String secret = prefs.getString(ACCESS_SECRET_NAME, null);
        if (key == null || secret == null || key.length() == 0 || secret.length() == 0) {
            return;
        }
        Log.d(TAG, "success");
        session.setOAuth2AccessToken(secret);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_welcome, menu);
        return true;
    }

    private void storeAuth(AndroidAuthSession session) {
        String oauth2AccessToken = session.getOAuth2AccessToken();
        if (oauth2AccessToken != null) {
            SharedPreferences prefs = getSharedPreferences(ACCOUNT_PREFS_NAME, 0);
            SharedPreferences.Editor edit = prefs.edit();
            edit.putString(ACCESS_KEY_NAME, "oauth2:");
            edit.putString(ACCESS_SECRET_NAME, oauth2AccessToken);
            edit.commit();
        }
    }

    private void storeSwitchState(boolean state) {
        SharedPreferences switchPrefs = getSharedPreferences(SWITCH_PREFS, 0);
        SharedPreferences.Editor edit = switchPrefs.edit();
        edit.putBoolean(SWITCH_STATE, state);
        edit.commit();
    }

    private boolean loadSwitchState() {
        SharedPreferences switchPrefs = getSharedPreferences(SWITCH_PREFS, 0);
        return switchPrefs.getBoolean(SWITCH_STATE, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AndroidAuthSession session = mDBApi.getSession();
        if (session.authenticationSuccessful()) {
            try {
                // Required to complete auth, sets the access token on the session
                session.finishAuthentication();
                // Store it locally in our app for later use
                storeAuth(session);
                storeSwitchState(true);
            } catch (IllegalStateException e) {
                Log.i(TAG, "Error authenticating", e);
            }
        } else {
            Switch dropboxSync = (Switch) findViewById(R.id.dropbox_sync);
            boolean isSwitchOn = loadSwitchState();
            if (isSwitchOn) {
                dropboxSync.setChecked(true);
                NetworkService.setmDBApi(mDBApi);
            } else {
                dropboxSync.setChecked(false);
                NetworkService.setmDBApi(null);
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
