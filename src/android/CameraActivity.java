package com.cordovaplugincamerapreview;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Environment;
import android.media.ExifInterface;
import android.util.Base64;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.Bundle;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import org.apache.cordova.LOG;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.Exception;
import java.lang.Integer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Arrays;

public class CameraActivity extends Fragment implements SensorEventListener {

  public interface CameraPreviewListener {
    void onPictureTaken(String originalPicture);

    void onPictureTakenError(String message);

    void onFocusSet(int pointX, int pointY);

    void onFocusSetError(String message);

    void onCameraStarted();
  }

  private CameraPreviewListener eventListener;
  private static final String TAG = "CameraActivity";
  public FrameLayout mainLayout;
  public FrameLayout frameContainerLayout;

  private Preview mPreview;
  private boolean canTakePicture = true;

  private View view;
  private Camera.Parameters cameraParameters;
  private Camera mCamera;
  private int numberOfCameras;
  private int cameraCurrentlyLocked;
  private int currentQuality;

  // The first rear facing camera
  private int defaultCameraId;
  public String defaultCamera;
  public boolean tapToTakePicture;
  public boolean dragEnabled;
  public boolean tapToFocus;

  public int width;
  public int height;
  public int x;
  public int y;
  private SensorManager	sensorManager;

  public void setEventListener(CameraPreviewListener listener) {
    eventListener = listener;
  }

  private String appResourcesPackage;
  int rotationDegrees = 0;
  int m_nOrientation = -1;

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    appResourcesPackage = getActivity().getPackageName();

    // Inflate the layout for this fragment
    view = inflater.inflate(getResources().getIdentifier("camera_activity", "layout", appResourcesPackage), container, false);
    createCameraPreview();

    sensorManager = (SensorManager) this.getActivity().getSystemService(Context.SENSOR_SERVICE);
    sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION), SensorManager.SENSOR_DELAY_UI);

    return view;
  }
  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy){

  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    if (event.sensor.getType() == Sensor.TYPE_ORIENTATION) {
      //The coordinate-system is defined relative to the screen of the phone in its default orientation
      int orientation = 0;
      float roll = 0;
      float pitch = 0;

      switch (getActivity().getWindowManager().getDefaultDisplay().getRotation()) {
        case Surface.ROTATION_0:
          roll = event.values[2];
          pitch = event.values[1];
          break;
        case Surface.ROTATION_90:
          roll = event.values[1];
          pitch = -event.values[2];
          break;
        case Surface.ROTATION_180:
          roll = -event.values[2];
          pitch = -event.values[1];
          break;
        case Surface.ROTATION_270:
          roll = -event.values[1];
          pitch = event.values[2];
          break;
      }

      if (pitch >= -45 && pitch < 45 && roll >= 45)
        orientation = 0;
      else if (pitch < -45 && roll >= -45 && roll < 45)
        orientation = 1;
      else if (pitch >= -45 && pitch < 45 && roll < -45)
        orientation = 2;
      else if (pitch >= 45 && roll >= -45 && roll < 45)
        orientation = 3;

      if (m_nOrientation != orientation) { //orientation changed event

        m_nOrientation = orientation;
        // fire event for new notification, or update your interface here
        if (orientation == 0) {
          //landscape left
          rotationDegrees = 270;
        } else if (orientation == 1) {
          //portrait
          rotationDegrees = 0;
        } else if (orientation == 2) {
          //landscape right
          rotationDegrees = 90;
        } else {
          //upside down
          rotationDegrees = 180;
        }

        Log.d("Sensor", "onSensorChanged: orientation:" + orientation);
      }
    }
  }

  public void setRect(int x, int y, int width, int height) {
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
  }

  private void createCameraPreview() {
    if (mPreview == null) {
      setDefaultCameraId();

      //set box position and size
      FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
      layoutParams.setMargins(x, y, 0, 0);
      frameContainerLayout = (FrameLayout) view.findViewById(getResources().getIdentifier("frame_container", "id", appResourcesPackage));
      frameContainerLayout.setLayoutParams(layoutParams);

      //video view
      mPreview = new Preview(getActivity());
      mainLayout = (FrameLayout) view.findViewById(getResources().getIdentifier("video_view", "id", appResourcesPackage));
      mainLayout.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
      mainLayout.addView(mPreview);
      mainLayout.setEnabled(false);

      final GestureDetector gestureDetector = new GestureDetector(getActivity().getApplicationContext(), new TapGestureDetector());

      getActivity().runOnUiThread(new Runnable() {
        @Override
        public void run() {
          frameContainerLayout.setClickable(true);
          frameContainerLayout.setOnTouchListener(new View.OnTouchListener() {

            private int mLastTouchX;
            private int mLastTouchY;
            private int mPosX = 0;
            private int mPosY = 0;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
              FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) frameContainerLayout.getLayoutParams();


              boolean isSingleTapTouch = gestureDetector.onTouchEvent(event);
              if (event.getAction() != MotionEvent.ACTION_MOVE && isSingleTapTouch) {
                if (tapToTakePicture && tapToFocus) {
                  setFocusArea((int) event.getX(0), (int) event.getY(0), new Camera.AutoFocusCallback() {
                    public void onAutoFocus(boolean success, Camera camera) {
                      if (success) {
                        takePicture(0, 0, 85);
                      } else {
                        Log.d(TAG, "onTouch:" + " setFocusArea() did not suceed");
                      }
                    }
                  });

                } else if (tapToTakePicture) {
                  takePicture(0, 0, 85);

                } else if (tapToFocus) {
                  setFocusArea((int) event.getX(0), (int) event.getY(0), new Camera.AutoFocusCallback() {
                    public void onAutoFocus(boolean success, Camera camera) {
                      if (success) {
                        // A callback to JS might make sense here.
                      } else {
                        Log.d(TAG, "onTouch:" + " setFocusArea() did not suceed");
                      }
                    }
                  });
                }
                return true;
              } else {
                if (dragEnabled) {
                  int x;
                  int y;

                  switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                      if (mLastTouchX == 0 || mLastTouchY == 0) {
                        mLastTouchX = (int) event.getRawX() - layoutParams.leftMargin;
                        mLastTouchY = (int) event.getRawY() - layoutParams.topMargin;
                      } else {
                        mLastTouchX = (int) event.getRawX();
                        mLastTouchY = (int) event.getRawY();
                      }
                      break;
                    case MotionEvent.ACTION_MOVE:

                      x = (int) event.getRawX();
                      y = (int) event.getRawY();

                      final float dx = x - mLastTouchX;
                      final float dy = y - mLastTouchY;

                      mPosX += dx;
                      mPosY += dy;

                      layoutParams.leftMargin = mPosX;
                      layoutParams.topMargin = mPosY;

                      frameContainerLayout.setLayoutParams(layoutParams);

                      // Remember this touch position for the next move event
                      mLastTouchX = x;
                      mLastTouchY = y;

                      break;
                    default:
                      break;
                  }
                }
              }
              return true;
            }
          });
        }
      });
    }
  }

  private void setDefaultCameraId() {
    // Find the total number of cameras available
    numberOfCameras = Camera.getNumberOfCameras();

    int camId = defaultCamera.equals("front") ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;

    // Find the ID of the default camera
    Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
    for (int i = 0; i < numberOfCameras; i++) {
      Camera.getCameraInfo(i, cameraInfo);
      if (cameraInfo.facing == camId) {
        defaultCameraId = camId;
        break;
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();

    mCamera = Camera.open(defaultCameraId);

    if (cameraParameters != null) {
      mCamera.setParameters(cameraParameters);
    }

    cameraCurrentlyLocked = defaultCameraId;

    if (mPreview.mPreviewSize == null) {
      mPreview.setCamera(mCamera, cameraCurrentlyLocked);
      eventListener.onCameraStarted();
    } else {
      mPreview.switchCamera(mCamera, cameraCurrentlyLocked);
      mCamera.startPreview();
    }

    Log.d(TAG, "cameraCurrentlyLocked:" + cameraCurrentlyLocked);

    final FrameLayout frameContainerLayout = (FrameLayout) view.findViewById(getResources().getIdentifier("frame_container", "id", appResourcesPackage));

    ViewTreeObserver viewTreeObserver = frameContainerLayout.getViewTreeObserver();

    if (viewTreeObserver.isAlive()) {
      viewTreeObserver.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
          frameContainerLayout.getViewTreeObserver().removeGlobalOnLayoutListener(this);
          frameContainerLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
          final RelativeLayout frameCamContainerLayout = (RelativeLayout) view.findViewById(getResources().getIdentifier("frame_camera_cont", "id", appResourcesPackage));

          FrameLayout.LayoutParams camViewLayout = new FrameLayout.LayoutParams(frameContainerLayout.getWidth(), frameContainerLayout.getHeight());
          camViewLayout.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
          frameCamContainerLayout.setLayoutParams(camViewLayout);
        }
      });
    }

    if (sensorManager!= null)
      sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION), SensorManager.SENSOR_DELAY_NORMAL);

  }

  @Override
  public void onPause() {
    super.onPause();

    // Because the Camera object is a shared resource, it's very important to release it when the activity is paused.
    if (mCamera != null) {
      setDefaultCameraId();
      mPreview.setCamera(null, -1);
      mCamera.setPreviewCallback(null);
      mCamera.release();
      mCamera = null;
    }

    if (sensorManager != null)
      sensorManager.unregisterListener(this);
  }

  public Camera getCamera() {
    return mCamera;
  }

  public void switchCamera() {
    // check for availability of multiple cameras
    if (numberOfCameras == 1) {
      //There is only one camera available
    } else {
      Log.d(TAG, "numberOfCameras: " + numberOfCameras);

      // OK, we have multiple cameras. Release this camera -> cameraCurrentlyLocked
      if (mCamera != null) {
        mCamera.stopPreview();
        mPreview.setCamera(null, -1);
        mCamera.release();
        mCamera = null;
      }

      Log.d(TAG, "cameraCurrentlyLocked := " + Integer.toString(cameraCurrentlyLocked));
      try {
        cameraCurrentlyLocked = (cameraCurrentlyLocked + 1) % numberOfCameras;
        Log.d(TAG, "cameraCurrentlyLocked new: " + cameraCurrentlyLocked);
      } catch (Exception exception) {
        Log.d(TAG, exception.getMessage());
      }

      // Acquire the next camera and request Preview to reconfigure parameters.
      mCamera = Camera.open(cameraCurrentlyLocked);

      if (cameraParameters != null) {
        Log.d(TAG, "camera parameter not null");

        // Check for flashMode as well to prevent error on frontward facing camera.
        List<String> supportedFlashModesNewCamera = mCamera.getParameters().getSupportedFlashModes();
        String currentFlashModePreviousCamera = cameraParameters.getFlashMode();
        if (supportedFlashModesNewCamera != null && supportedFlashModesNewCamera.contains(currentFlashModePreviousCamera)) {
          Log.d(TAG, "current flash mode supported on new camera. setting params");
         /* mCamera.setParameters(cameraParameters);
            The line above is disabled because parameters that can actually be changed are different from one device to another. Makes less sense trying to reconfigure them when changing camera device while those settings gan be changed using plugin methods.
         */
        } else {
          Log.d(TAG, "current flash mode NOT supported on new camera");
        }

      } else {
        Log.d(TAG, "camera parameter NULL");
      }

      mPreview.switchCamera(mCamera, cameraCurrentlyLocked);

      mCamera.startPreview();
    }
  }

  public void setCameraParameters(Camera.Parameters params) {
    cameraParameters = params;

    if (mCamera != null && cameraParameters != null) {
      mCamera.setParameters(cameraParameters);
    }
  }

  public boolean hasFrontCamera() {
    return getActivity().getApplicationContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT);
  }

  public static Bitmap flipBitmap(Bitmap source) {
    Matrix matrix = new Matrix();
    matrix.preScale(1.0f, -1.0f);

    return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
  }

  public void copyExifData(String file1, String file2){
    try {
      ExifInterface file1Exif = new ExifInterface(file1);
      ExifInterface file2Exif = new ExifInterface(file2);

      String aperture = file1Exif.getAttribute(ExifInterface.TAG_APERTURE);
      String dateTime = file1Exif.getAttribute(ExifInterface.TAG_DATETIME);
      String exposureTime = file1Exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
      String flash = file1Exif.getAttribute(ExifInterface.TAG_FLASH);
      String focalLength = file1Exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
      String gpsAltitude = file1Exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE);
      String gpsAltitudeRef = file1Exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF);
      String gpsDateStamp = file1Exif.getAttribute(ExifInterface.TAG_GPS_DATESTAMP);
      String gpsLatitude = file1Exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE);
      String gpsLatitudeRef = file1Exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF);
      String gpsLongitude = file1Exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE);
      String gpsLongitudeRef = file1Exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF);
      String gpsProcessingMethod = file1Exif.getAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD);
      String gpsTimestamp = file1Exif.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP);
      Integer imageLength = file1Exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
      Integer imageWidth = file1Exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0);
      String iso = file1Exif.getAttribute(ExifInterface.TAG_ISO);
      String make = file1Exif.getAttribute(ExifInterface.TAG_MAKE);
      String model = file1Exif.getAttribute(ExifInterface.TAG_MODEL);
      Integer orientation = file1Exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
      Integer whiteBalance = file1Exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, 0);


      file2Exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString());
      if (aperture != null)
        file2Exif.setAttribute(ExifInterface.TAG_APERTURE, aperture);
      if (dateTime != null)
        file2Exif.setAttribute(ExifInterface.TAG_DATETIME, dateTime);
      if (exposureTime != null)
        file2Exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureTime);
      if (flash != null)
        file2Exif.setAttribute(ExifInterface.TAG_FLASH, flash);
      if (focalLength != null)
        file2Exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, focalLength);
      if (gpsAltitude != null)
        file2Exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, gpsAltitude);
      if (gpsAltitudeRef != null)
        file2Exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, gpsAltitudeRef);
      if (gpsDateStamp != null)
        file2Exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, gpsDateStamp);
      if (gpsLatitude != null)
        file2Exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, gpsLatitude);
      if (gpsLatitudeRef != null)
        file2Exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, gpsLatitudeRef);
      if (gpsLongitude != null)
        file2Exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, gpsLongitude);
      if (gpsLongitudeRef != null)
        file2Exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, gpsLongitudeRef);
      if (gpsProcessingMethod != null)
        file2Exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, gpsProcessingMethod);
      if (gpsTimestamp != null)
        file2Exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, gpsTimestamp);

      file2Exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, imageLength.toString());
      file2Exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, imageWidth.toString());
      if (iso != null)
        file2Exif.setAttribute(ExifInterface.TAG_ISO, iso);
      if (make != null)
        file2Exif.setAttribute(ExifInterface.TAG_MAKE, make);
      if (model != null)
        file2Exif.setAttribute(ExifInterface.TAG_MODEL, model);

      file2Exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, whiteBalance.toString());
      file2Exif.saveAttributes();
    }
    catch (FileNotFoundException io) {
      io.printStackTrace();
    }
    catch (IOException io) {
      io.printStackTrace();
    }
    catch (NullPointerException np){
      np.printStackTrace();
    }
  }

  private void saveThumbnail(Bitmap b, String name) {
    try {
      Matrix m = new Matrix();
      m.setRectToRect(new RectF(0, 0, b.getWidth(), b.getHeight()), new RectF(0, 0, 370, 370), Matrix.ScaleToFit.CENTER);
      Bitmap resized = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
      String fileName = "thumb_" + name;
      int index = fileName.lastIndexOf('.');
      String ext = fileName.substring(index);
      File file = new File(getActivity().getApplicationContext().getFilesDir().getPath(), fileName);
      OutputStream outStream = new FileOutputStream(file);

      if (ext.compareToIgnoreCase(".png") == 0) {
        resized.compress(Bitmap.CompressFormat.PNG, 100, outStream);
      } else {
        resized.compress(Bitmap.CompressFormat.JPEG, 100, outStream);
      }

      outStream.flush();
      outStream.close();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }


  ShutterCallback shutterCallback = new ShutterCallback() {
    public void onShutter() {
      // do nothing, availabilty of this callback causes default system shutter sound to work
    }
  };

  PictureCallback jpegPictureCallback = new PictureCallback() {
    public void onPictureTaken(byte[] data, Camera arg1) {
      Log.d(TAG, "CameraPreview jpegPictureCallback");

      try {
        File orignalFile = new File(getActivity().getApplicationContext().getFilesDir().getPath(), System.currentTimeMillis() + ".jpg");
        FileOutputStream stream = new FileOutputStream(orignalFile);
        stream.write(data);
        stream.flush();
        stream.close();


        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        Matrix matrix = new Matrix();
        if (cameraCurrentlyLocked == Camera.CameraInfo.CAMERA_FACING_FRONT)
          matrix.preScale(1.0f, -1.0f);
        matrix.preRotate(rotationDegrees);
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, currentQuality, outputStream);
        data = outputStream.toByteArray();

        //save the image to app internal storage: /data/data/com.app.bundleid/files/
        File file = new File(getActivity().getApplicationContext().getFilesDir().getPath(), System.currentTimeMillis() + ".jpg");
        Log.d(TAG, "CameraPreview save image to " + file.getAbsolutePath());

        FileOutputStream fileOutputStream = new FileOutputStream(file);
        fileOutputStream.write(data);
        fileOutputStream.flush();
        fileOutputStream.close();
        copyExifData(orignalFile.getPath(), file.getPath());
        orignalFile.delete();

        saveThumbnail(bitmap, file.getName());
        eventListener.onPictureTaken(file.getName());


        Log.d(TAG, "CameraPreview pictureTakenHandler called back");
      } catch (OutOfMemoryError e) {
        // most likely failed to allocate memory for rotateBitmap
        Log.d(TAG, "CameraPreview OutOfMemoryError");
        // failed to allocate memory
        eventListener.onPictureTakenError("Picture too large (memory)");
      } catch (Exception e) {
        Log.d(TAG, "CameraPreview onPictureTaken general exception");
      } finally {
        canTakePicture = true;
        mCamera.startPreview();
      }
    }
  };

  private Camera.Size getOptimalPictureSize(final int width, final int height, final Camera.Size previewSize, final List<Camera.Size> supportedSizes) {
    /*
      get the supportedPictureSize that:
      - matches exactly width and height
      - has the closest aspect ratio to the preview aspect ratio
      - has picture.width and picture.height closest to width and height
      - has the highest supported picture width and height up to 2 Megapixel if width == 0 || height == 0
    */
    Camera.Size size = mCamera.new Size(width, height);

    // convert to landscape if necessary
    if (size.width < size.height) {
      int temp = size.width;
      size.width = size.height;
      size.height = temp;
    }

    double previewAspectRatio = (double) previewSize.width / (double) previewSize.height;

    if (previewAspectRatio < 1.0) {
      // reset ratio to landscape
      previewAspectRatio = 1.0 / previewAspectRatio;
    }

    Log.d(TAG, "CameraPreview previewAspectRatio " + previewAspectRatio);

    double aspectTolerance = 0.1;
    double bestDifference = Double.MAX_VALUE;

    for (int i = 0; i < supportedSizes.size(); i++) {
      Camera.Size supportedSize = supportedSizes.get(i);

      // Perfect match
      if (supportedSize.equals(size)) {
        Log.d(TAG, "CameraPreview optimalPictureSize " + supportedSize.width + 'x' + supportedSize.height);
        return supportedSize;
      }

      double difference = Math.abs(previewAspectRatio - ((double) supportedSize.width / (double) supportedSize.height));

      if (difference < bestDifference - aspectTolerance) {
        // better aspectRatio found
        if ((width != 0 && height != 0) || (supportedSize.width * supportedSize.height < 2048 * 1024)) {
          size.width = supportedSize.width;
          size.height = supportedSize.height;
          bestDifference = difference;
        }
      } else if (difference < bestDifference + aspectTolerance) {
        // same aspectRatio found (within tolerance)
        if (width == 0 || height == 0) {
          // set highest supported resolution below 2 Megapixel
          if ((size.width < supportedSize.width) && (supportedSize.width * supportedSize.height < 2048 * 1024)) {
            size.width = supportedSize.width;
            size.height = supportedSize.height;
          }
        } else {
          // check if this pictureSize closer to requested width and height
          if (Math.abs(width * height - supportedSize.width * supportedSize.height) < Math.abs(width * height - size.width * size.height)) {
            size.width = supportedSize.width;
            size.height = supportedSize.height;
          }
        }
      }
    }
    Log.d(TAG, "CameraPreview optimalPictureSize " + size.width + 'x' + size.height);
    return size;
  }

  public void takePicture(final int width, final int height, final int quality) {
    Log.d(TAG, "CameraPreview takePicture width: " + width + ", height: " + height + ", quality: " + quality);

    if (mPreview != null) {
      if (!canTakePicture) {
        return;
      }

      canTakePicture = false;

      new Thread() {
        public void run() {
          Camera.Parameters params = mCamera.getParameters();

          Camera.Size size = getOptimalPictureSize(width, height, params.getPreviewSize(), params.getSupportedPictureSizes());
          params.setPictureSize(size.width, size.height);
          currentQuality = quality;

          if (cameraCurrentlyLocked == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            // The image will be recompressed in the callback
            params.setJpegQuality(99);
          } else {
            params.setJpegQuality(quality);
          }

          params.setRotation(mPreview.getDisplayOrientation());

          mCamera.setParameters(params);
          mCamera.takePicture(shutterCallback, null, jpegPictureCallback);
        }
      }.start();
    } else {
      canTakePicture = true;
    }
  }

  public void setFocusArea(final int pointX, final int pointY, final Camera.AutoFocusCallback callback) {
    if (mCamera != null) {

      mCamera.cancelAutoFocus();

      Camera.Parameters parameters = mCamera.getParameters();

      Rect focusRect = calculateTapArea(pointX, pointY, 1f);
      parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
      parameters.setFocusAreas(Arrays.asList(new Camera.Area(focusRect, 1000)));

      if (parameters.getMaxNumMeteringAreas() > 0) {
        Rect meteringRect = calculateTapArea(pointX, pointY, 1.5f);
        parameters.setMeteringAreas(Arrays.asList(new Camera.Area(meteringRect, 1000)));
      }

      try {
        setCameraParameters(parameters);
        mCamera.autoFocus(callback);
      } catch (Exception e) {
        Log.d(TAG, e.getMessage());
        callback.onAutoFocus(false, this.mCamera);
      }
    }
  }

  private Rect calculateTapArea(float x, float y, float coefficient) {
    return new Rect(
            Math.round((x - 100) * 2000 / width - 1000),
            Math.round((y - 100) * 2000 / height - 1000),
            Math.round((x + 100) * 2000 / width - 1000),
            Math.round((y + 100) * 2000 / height - 1000)
    );
  }
}