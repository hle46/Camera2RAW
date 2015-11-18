package edu.illinois.sba.camera2raw.services;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.exception.DropboxException;
import com.dropbox.client2.exception.DropboxFileSizeException;
import com.dropbox.client2.exception.DropboxIOException;
import com.dropbox.client2.exception.DropboxParseException;
import com.dropbox.client2.exception.DropboxPartialFileException;
import com.dropbox.client2.exception.DropboxServerException;
import com.dropbox.client2.exception.DropboxUnlinkedException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;


/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 * <p/>
 *
 * helper methods.
 */
public class NetworkService extends IntentService {
    // IntentService performs upload action
    private static final String ACTION_UPLOAD = "action.UPLOAD";

    private static final String EXTRA_UPLOAD_FILE = "extra.UPLOAD";

    private static final String TAG = "NETWORK SERVICE";
    private static DropboxAPI<?> mDBApi;

    public static void setmDBApi(DropboxAPI<?> _mDBApi) {
        mDBApi = _mDBApi;
    }
    /**
     * Starts this service to perform action Upload with the given parameters. If
     * the service is already performing a task this action will be queued.
     *
     * @see IntentService
     */
    public static void startActionUpload(Context context, String file) {
        Intent intent = new Intent(context, NetworkService.class);
        intent.setAction(ACTION_UPLOAD);
        intent.putExtra(EXTRA_UPLOAD_FILE, file);
        context.startService(intent);
    }


    public NetworkService() {
        super("NetworkService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            if (ACTION_UPLOAD.equals(action)) {
                final String filePath = intent.getStringExtra(EXTRA_UPLOAD_FILE);
                handleActionUploadFile(filePath);
            }
        }
    }

    /**
     * Handle action Upload in the provided background thread with the provided
     * parameters.
     */
    private void handleActionUploadFile(String filePath) {
        if (mDBApi == null) {
            Log.e(TAG, "No connection to Dropbox");
            return;
        }
        File file = new File(filePath);
        Log.d(TAG, filePath);
        String mErrorMsg;
        try {
            FileInputStream inputStream = new FileInputStream(file);
            String fileName = file.getName();
            Log.d(TAG, fileName);
            mDBApi.putFileOverwrite(fileName, inputStream, file.length(), null);
            mErrorMsg = "Upload successfully";
        } catch (FileNotFoundException e) {
            // File not found
            mErrorMsg = "File cannot found to upload";
        } catch (DropboxUnlinkedException e) {
            // This session wasn't authenticated properly or user unlinked
            mErrorMsg = "This app wasn't authenticated properly.";
        } catch (DropboxFileSizeException e) {
            // File size too big to upload via the API
            mErrorMsg = "This file is too big to upload";
        } catch (DropboxPartialFileException e) {
            // We canceled the operation
            mErrorMsg = "Upload canceled";
        } catch (DropboxServerException e) {
            // Server-side exception.  These are examples of what could happen,
            // but we don't do anything special with them here.
            if (e.error == DropboxServerException._401_UNAUTHORIZED) {
                // Unauthorized, so we should unlink them.  You may want to
                // automatically log the user out in this case.
            } else if (e.error == DropboxServerException._403_FORBIDDEN) {
                // Not allowed to access this
            } else if (e.error == DropboxServerException._404_NOT_FOUND) {
                // path not found (or if it was the thumbnail, can't be
                // thumbnailed)
            } else if (e.error == DropboxServerException._507_INSUFFICIENT_STORAGE) {
                // user is over quota
            } else {
                // Something else
            }
            // This gets the Dropbox error, translated into the user's language
            mErrorMsg = e.body.userError;
            if (mErrorMsg == null) {
                mErrorMsg = e.body.error;
            }
        } catch (DropboxIOException e) {
            // Happens all the time, probably want to retry automatically.
            mErrorMsg = "Network error.  Try again.";
        } catch (DropboxParseException e) {
            // Probably due to Dropbox server restarting, should retry
            mErrorMsg = "Dropbox error.  Try again.";
        } catch (DropboxException e) {
            // Unknown error
            mErrorMsg = "Unknown error.  Try again.";
        }
        Log.d(TAG, mErrorMsg);
    }
}
