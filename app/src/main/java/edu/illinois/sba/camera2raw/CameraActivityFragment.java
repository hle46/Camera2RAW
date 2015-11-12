package edu.illinois.sba.camera2raw;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static edu.illinois.sba.camera2raw.Native.saveImageAsBin;
import static edu.illinois.sba.camera2raw.Native.saveImageAsCSV;


public class CameraActivityFragment extends Fragment
        implements View.OnClickListener {
    static {
        System.loadLibrary("raw");
    }

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * UI stuffs
     */
    private Switch mExposureSwitch;
    private Switch mBurstSwitch;
    private LinearLayout mExposureLinearLayout;
    private LinearLayout mBurstLinearLayout;
    private TextView mExposureTextView;
    private TextView mBurstTextView;
    private FrameLayout control;

    private boolean isAutoExposure() {
        return mExposureSwitch.isChecked();
    }

    private boolean isBurstMode() {
        return mBurstSwitch.isChecked();
    }

    private void setEnabledLinearLayout(LinearLayout ll, boolean b) {
        for ( int i = 0; i < ll.getChildCount();  i++ ){
            View childView = ll.getChildAt(i);
            childView.setEnabled(b);
        }
    }

    private void setEnabledFrameLayout(FrameLayout fl, boolean b) {
        for ( int i = 0; i < fl.getChildCount();  i++ ){
            View childView = fl.getChildAt(i);
            childView.setEnabled(b);
        }
    }


    // Durations in nanoseconds
    private static final long MICRO_SECOND = 1000;
    private static final long MILLI_SECOND = MICRO_SECOND * 1000;
    private static final long ONE_SECOND = MILLI_SECOND * 1000;

    private ArrayList<Long> mExposureTimes;
    private int mCurExposureIdx;

    private int mCurMaxBurst;


    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Camera2BasicFragment";

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;

    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }

    };

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    /**
     * A CameraCharacteristics {@link CameraCharacteristics}
     */
    private CameraCharacteristics mCameraCharacteristics;

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;
    private ImageReader mImageReaderJPEG;
    /**
     * Number of images in a burst
     */
    private final int maxBurstImages = 8;

    /**
     * Current image
     */
    private int imageNum = 0;
    private int imageCount;
    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.d(TAG, "onImageAvailable");
            ++imageCount;
            Log.d(TAG, "Produce Count: " + imageCount);
        }

    };
    private int jpegCount = 0;
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListenerJPEG
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {

            if (isBurstMode()) {
                mBackgroundHandler.post(new ImageSaverJPEG(reader.acquireNextImage(), new File(getActivity().getExternalFilesDir(null), mFilePrefix + "_" + (++jpegCount) + ".jpg"), getActivity()));
                if (jpegCount == mCurMaxBurst) {
                    jpegCount = 0;
                }
            } else {
                mBackgroundHandler.post(new ImageSaverJPEG(reader.acquireNextImage(), new File(getActivity().getExternalFilesDir(null), mFilePrefix + ".jpg"), getActivity()));
            }
        }

    };

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     * to cache the lastest preview CaptureRequest
     */
    private CaptureRequest mPreviewRequest;

    /**
     * The current state of camera state for taking pictures.
     *
     * @see #mCaptureCallback
     */
    private int mState = STATE_PREVIEW;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        if (isAutoExposure()) {
                            // CONTROL_AE_STATE can be null on some devices
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null ||
                                    aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                mState = STATE_PICTURE_TAKEN;
                                captureStillPicture();
                            } else {
                                runPrecaptureSequence();
                            }
                        } else {
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }

            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }

    };

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(final String text) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    public static CameraActivityFragment newInstance() {
        return new CameraActivityFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        view.findViewById(R.id.picture).setOnClickListener(this);
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);

        mExposureSwitch = (Switch) view.findViewById(R.id.exposureSwitch);
        mExposureLinearLayout = (LinearLayout) view.findViewById(R.id.exposureLinearLayout);
        setEnabledLinearLayout(mExposureLinearLayout, !isAutoExposure());
        mExposureSwitch.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        setEnabledLinearLayout(mExposureLinearLayout, !isChecked);
                        try {
                            mCaptureSession.stopRepeating();
                            // Adjust exposure parameters appropriately based on exposure mode
                            setExposureAppropriately(mPreviewRequestBuilder);

                            // Finally, we start displaying the camera preview.
                            mPreviewRequest = mPreviewRequestBuilder.build();
                            mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                    mCaptureCallback, mBackgroundHandler);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }
                }
        );

        mExposureTextView = (TextView) view.findViewById(R.id.exposureTextView);


        Button mExposureButtonLess = (Button) view.findViewById(R.id.exposureButtonLess);
        mExposureButtonLess.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mCurExposureIdx == 0) {
                            showToast("Reach minimum of manual exposure time");
                            return;
                        }
                        --mCurExposureIdx;
                        showExposureTime(mExposureTimes.get(mCurExposureIdx));
                        // Adjust exposure parameters appropriately based on exposure mode
                        setExposureAppropriately(mPreviewRequestBuilder);

                        // Finally, we start displaying the camera preview.
                        mPreviewRequest = mPreviewRequestBuilder.build();
                        try {
                            mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                    mCaptureCallback, mBackgroundHandler);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }
                }
        );
        Button mExposureButtonGreater = (Button) view.findViewById(R.id.exposureButtonGreater);
        mExposureButtonGreater.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mCurExposureIdx == (mExposureTimes.size() - 1)) {
                            showToast("Reach maximum of manual exposure time");
                            return;
                        }
                        ++mCurExposureIdx;
                        showExposureTime(mExposureTimes.get(mCurExposureIdx));
                        // Adjust exposure parameters appropriately based on exposure mode
                        setExposureAppropriately(mPreviewRequestBuilder);

                        // Finally, we start displaying the camera preview.
                        mPreviewRequest = mPreviewRequestBuilder.build();
                        try {
                            mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                    mCaptureCallback, mBackgroundHandler);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }
                }
        );



        mBurstSwitch = (Switch) view.findViewById(R.id.burstSwitch);
        mBurstLinearLayout = (LinearLayout) view.findViewById(R.id.burstLinearLayout);
        setEnabledLinearLayout(mBurstLinearLayout, isBurstMode());
        mBurstSwitch.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        setEnabledLinearLayout(mBurstLinearLayout, isChecked);
                    }
                }
        );

        mBurstTextView = (TextView) view.findViewById(R.id.burstTextView);
        mCurMaxBurst = maxBurstImages / 2;
        mBurstTextView.setText(String.format("%d", mCurMaxBurst));
        Button mBurstButtonLess = (Button) view.findViewById(R.id.burstButtonLess);
        mBurstButtonLess.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mCurMaxBurst == 1) {
                            showToast("Reach minimum of burst");
                            return;
                        }
                        mBurstTextView.setText(String.format("%d", --mCurMaxBurst));
                    }
                }
        );
        Button mBurstButtonGreater = (Button) view.findViewById(R.id.burstButtonGreater);
        mBurstButtonGreater.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mCurMaxBurst == maxBurstImages) {
                            showToast("Reach maximum of burst");
                            return;
                        }
                        mBurstTextView.setText(String.format("%d", ++mCurMaxBurst));
                    }
                }
        );
        control = (FrameLayout) view.findViewById(R.id.control);

    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void showExposureTime(long exposureTime) {
        // Format exposure time nicely
        String exposureText;
        if (exposureTime > ONE_SECOND) {
            exposureText = String.format("%.0f s", exposureTime / 1e9);
        } else if (exposureTime > MILLI_SECOND) {
            exposureText = String.format("%.0f ms", exposureTime / 1e6);
        } else if (exposureTime > MICRO_SECOND) {
            exposureText = String.format("%.0f us", exposureTime / 1e3);
        } else {
            exposureText = String.format("%d ns", exposureTime);
        }
        mExposureTextView.setText(exposureText);
    }
    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private void setUpCameraOutputs(int width, int height) {
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // Get the range of image exposure times supported by this camera device.
                Range<Long> range = characteristics.get(
                        CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                long lowerExposureTime = range.getLower();
                long upperExposureTime = range.getUpper();

                // Setup exposure times

                long[] mStandardExposureTimes = new long[]{50 * MICRO_SECOND, 100 * MICRO_SECOND, 200 * MICRO_SECOND,
                        400 * MICRO_SECOND, 800 * MICRO_SECOND, MILLI_SECOND, 5 * MILLI_SECOND, 10 * MILLI_SECOND,
                        20 * MILLI_SECOND, 40 * MILLI_SECOND, 80 * MILLI_SECOND, 100 * MILLI_SECOND, 200 * MILLI_SECOND, 300 * MILLI_SECOND,
                        400 * MILLI_SECOND, 500 * MILLI_SECOND, 600 * MILLI_SECOND, 650 * MILLI_SECOND};
                mExposureTimes = new ArrayList<>();
                for (long exposureTime : mStandardExposureTimes) {
                    if (exposureTime > lowerExposureTime && exposureTime < upperExposureTime) {
                        mExposureTimes.add(exposureTime);
                    }
                }
                mCurExposureIdx = mExposureTimes.size() / 2;
                showExposureTime(mExposureTimes.get(mCurExposureIdx));



                // For still image captures, we use the largest available size.
                Size largest = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.RAW_SENSOR)),
                        new CompareSizesByArea());
                
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.RAW_SENSOR, /*maxImages*/maxBurstImages + 1);
                mImageReader.setOnImageAvailableListener(
                        mOnImageAvailableListener, null); // Run on main thread

                Size largestJPEG = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new CompareSizesByArea());
                mImageReaderJPEG = ImageReader.newInstance(largestJPEG.getWidth(), largestJPEG.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/ maxBurstImages + 1);
                mImageReaderJPEG.setOnImageAvailableListener(
                        mOnImageAvailableListenerJPEG, mBackgroundHandler);

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        width, height, new Size(4, 3)); // Choose aspect ration of 4:3

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                mCameraCharacteristics = characteristics;
                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
    }

    /**
     * Opens the camera specified by {@link CameraActivityFragment#mCameraId}.
     */
    private void openCamera(int width, int height) {
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        } catch (SecurityException e) {
            throw new RuntimeException("No permission");
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface(), mImageReaderJPEG.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                                // Turn off auto white balance
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);

                                // Turn off flash,
                                mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                                        CaptureRequest.FLASH_MODE_OFF);

                                // Adjust exposure parameters appropriately based on exposure mode
                                setExposureAppropriately(mPreviewRequestBuilder);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setExposureAppropriately(CaptureRequest.Builder builder) {
        if (isAutoExposure()) {
            // To turn off flash, CONTROL_AE_MODE can only be ON or OFF
            // https://developer.android.com/reference/android/hardware/camera2/CaptureRequest.html#FLASH_MODE
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            // Turn off antibanding
            builder.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF);
        } else { // Manual exposure
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF);
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, mExposureTimes.get(mCurExposureIdx));
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, 100);
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("MM_dd_yyyy_hh_mm_ss");
    private String mFilePrefix;
    /**
     * Initiate a still image capture.
     */
    private void takePicture() {
        lockFocus();
        if (imageCount == 0) {
            mFilePrefix = "IMG_" + simpleDateFormat.format(new Date());
        }
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        try {
            // This is how to tell the camera to lock focus.
            // Trigger the auto focus algorithm CONTROL_AF_TRIGGER to START
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);

            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;

            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    /**
     * Capture a still picture. This method should be called when we get a response in
     * {@link #mCaptureCallback} from both {@link #lockFocus()}.
     */
    private void captureStillPicture() {
        try {
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());
            captureBuilder.addTarget(mImageReaderJPEG.getSurface());

            captureBuilder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON); // required for raw

            /** Lock the focus since it is stated that when android.control.aeMode (auto exposure)
             *  is OFF, the behavior of AF is device dependent. It is recommended to lock AF by using
             *  android.control.afTrigger before setting android.control.aeMode to OFF.
             *  Look up android.control.afTrigger, there are only 3 states:
             *    - CANCEL: Autofocus will return to its initial state, and cancel any currently
             *      active trigger.
             *    - IDLE: The trigger is idle.
             *    - START: Autofocus will trigger now.
             *  I believe IDLE is what we want since there other two don't seem right
             */
            captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            // Turn off auto white balance
            captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);

            // Turn off flash
            captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);

            setExposureAppropriately(captureBuilder);

            CameraCaptureSession.CaptureCallback CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    Log.d(TAG, "onCaptureCompleted");
                    Log.d(TAG, "Consume count: " + imageCount);
                    while (imageCount == 0) {
                        // Wait
                    }
                    // Acquire next image
                    --imageCount;
                    Log.d(TAG, "ImageNum: " + imageNum + " Real exposure time: " + result.get(CaptureResult.SENSOR_EXPOSURE_TIME));
                    if (isBurstMode()) {
                        ++imageNum;
                        File mFile = new File(getActivity().getExternalFilesDir(null), mFilePrefix + "_" + imageNum);
                        mBackgroundHandler.post(new ImageSaver(mImageReader.acquireNextImage(), mFile, mCameraCharacteristics, result, getActivity()));
                        if (imageNum == mCurMaxBurst) {
                            imageNum = 0;
                            unlockFocus();
                        }

                    } else {
                        File mFile = new File(getActivity().getExternalFilesDir(null), mFilePrefix);
                        mBackgroundHandler.post(new ImageSaver(mImageReader.acquireNextImage(), mFile, mCameraCharacteristics, result, getActivity()));
                        unlockFocus();
                    }
                }
            };

            // Stop preview
            mCaptureSession.stopRepeating();

            if (isBurstMode()) {
                List<CaptureRequest> captureRequests = new ArrayList<>();
                for (int i = 0; i < mCurMaxBurst; ++i) {
                    captureRequests.add(captureBuilder.build());
                }
                mCaptureSession.captureBurst(captureRequests, CaptureCallback, mBackgroundHandler);
            } else {
                mCaptureSession.capture(captureBuilder.build(), CaptureCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private void unlockFocus() {
        try {
            // Reset the auto-focus trigger
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);

            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private ScheduledExecutorService scheduler;
    private int countRepeat = 0;
    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.picture: {
                setEnabledFrameLayout(control, false);
                scheduler =
                        Executors.newSingleThreadScheduledExecutor();

                scheduler.scheduleWithFixedDelay
                        (new Runnable() {
                            public void run() {
                                if (countRepeat++ == 60) {
                                    scheduler.shutdown();
                                    return;
                                }
                                takePicture();
                            }
                        }, 0, 30, TimeUnit.SECONDS);
                break;
            }
        }
    }

    /**
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private static class ImageSaver implements Runnable {

        /**
         * The JPEG image
         */
        private final Image mImage;
        /**
         * The file we save the image into.
         */
        private final File mFile;

        private final CameraCharacteristics mCameraCharacteristics;

        private final CaptureResult mCaptureResult;

        private final Activity mActivity;

        public ImageSaver(Image image, File file, CameraCharacteristics cameraCharacteristics, CaptureResult captureResult, Activity activity) {
            mImage = image;
            mFile = file;
            mCameraCharacteristics = cameraCharacteristics;
            mCaptureResult = captureResult;
            mActivity = activity;
        }

        @Override
        public void run() {
            DngCreator dngCreator = new DngCreator(mCameraCharacteristics, mCaptureResult);
            Image.Plane[] planes = mImage.getPlanes();
            ByteBuffer byteBuffer = planes[0].getBuffer();

            int width = mImage.getWidth();
            int height = mImage.getHeight();
            /*
            File mFileCSV = new File(mFile.getAbsolutePath() + ".csv");
            saveImageAsCSV(mFileCSV.getAbsolutePath(), byteBuffer, width, height);
            mActivity.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(mFileCSV))); */

            File mFileBin = new File(mFile.getAbsolutePath() + ".bin");
            saveImageAsBin(mFileBin.getAbsolutePath(), byteBuffer, width, height);
            mActivity.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(mFileBin)));

            File mFileDNG = new File(mFile.getAbsolutePath() + ".dng");
            Log.d(TAG, mFileDNG + "");
            try {
                FileOutputStream outputDNG = new FileOutputStream(mFileDNG);
                dngCreator.writeImage(outputDNG, mImage);
                outputDNG.flush();
                outputDNG.close();
                dngCreator.close();
                mImage.close();
                mActivity.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(mFileDNG)));
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    /**
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private static class ImageSaverJPEG implements Runnable {

        /**
         * The JPEG image
         */
        private final Image mImage;
        /**
         * The file we save the image into.
         */
        private final File mFile;
        private final Activity mActivity;

        public ImageSaverJPEG(Image image, File file, Activity activity) {
            mImage = image;
            mFile = file;
            mActivity = activity;
        }

        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            mActivity.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(mFile)));
        }

    }

    /**
     * Shows an error message dialog.
     */
    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            activity.finish();
                        }
                    })
                    .create();
        }

    }

}
