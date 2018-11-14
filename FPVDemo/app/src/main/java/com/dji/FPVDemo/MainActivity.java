package com.dji.FPVDemo;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.TextureView;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.TextureView.SurfaceTextureListener;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.File;
import java.io.FileOutputStream;

import dji.common.battery.BatteryState;
import dji.common.camera.ExposureSettings;
import dji.common.camera.ResolutionAndFrameRate;
import dji.common.camera.SettingsDefinitions;
import dji.common.camera.SystemState;
import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.gimbal.GimbalState;
import dji.common.gimbal.Attitude;
import dji.common.product.Model;
import dji.common.useraccount.UserAccountState;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.gimbal.Gimbal;
import dji.sdk.media.MediaManager;
import dji.sdk.useraccount.UserAccountManager;

public class MainActivity extends Activity implements SurfaceTextureListener,OnClickListener{

    private static final String TAG = MainActivity.class.getName();
    protected VideoFeeder.VideoDataListener mReceivedVideoDataListener = null;

    // Codec for video live view
    protected DJICodecManager mCodecManager = null;

    protected TextureView mVideoSurface = null;
    private Button mCaptureBtn, mShootPhotoModeBtn, mRecordVideoModeBtn;
    private ToggleButton mRecordBtn;
    private TextView recordingTime;

    protected PowerManager mPowerManager;
    protected PowerManager.WakeLock mWakeLock;
    protected TextView mTextViewPower;
    protected TextView mTextViewExp;
    protected TextView mTextViewOSD;

    //added for pose display and storage;

    protected StringBuffer mStringBuffer;
    protected StringBuffer mStringBufferPower;
    protected StringBuffer mStringBufferExp;

    protected float gimbalR=0,gimbalP=0,gimbalY=0;
    protected Attitude mGimbalAttitude;

    protected static final int CHANGE_TEXT_VIEW = 0;
    protected static final int RECORD_BUTTON_DOWN=1;
    protected static final int BATTERY_STATUS_CHANGED=2;
    protected static final int EXPOSURE_STATUS_CHANGED=3;


    private Handler handler=new Handler(new Handler.Callback() {

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case CHANGE_TEXT_VIEW:
                    mTextViewOSD.setText(mStringBuffer.toString());
                    break;
                case RECORD_BUTTON_DOWN:
                    mRecordBtn.performClick();
                    break;
                case BATTERY_STATUS_CHANGED:
                    mTextViewPower.setText(mStringBufferPower.toString());
                    break;
                case EXPOSURE_STATUS_CHANGED:
                    String evString=mStringBufferExp.toString();
                    evString=evString.replaceAll("ShutterSpeed1_","ShutterSpeed1/");
                    mTextViewExp.setText(evString);
                    break;
                default:
                    break;
            }
            return false;
        }
    });
    protected File mPoseOutputFile;
    protected FileOutputStream mPoseOutputStream;
    protected MediaManager newMovMedia=null;
    protected boolean isRecording=false;
    long startTime = 0;
    protected File mFlyLogDir;
    protected StringBuffer outMessage;
    protected SettingsDefinitions.VideoResolution videoResolution;
    protected int screenWidth,screenHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //handler = new Handler();

        initUI();

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataListener = new VideoFeeder.VideoDataListener() {

            @Override
            public void onReceive(byte[] videoBuffer, int size) {
                if (mCodecManager != null) {
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                }
            }
        };

        Camera camera = FPVDemoApplication.getCameraInstance();

        if (camera != null) {

            camera.setSystemStateCallback(new SystemState.Callback() {
                @Override
                public void onUpdate(SystemState cameraSystemState) {
                    if (null != cameraSystemState) {

                        int recordTime = cameraSystemState.getCurrentVideoRecordingTimeInSeconds();
                        int minutes = (recordTime % 3600) / 60;
                        int seconds = recordTime % 60;

                        final String timeString = String.format("%02d:%02d", minutes, seconds);
                        final boolean isVideoRecording = cameraSystemState.isRecording();

                        MainActivity.this.runOnUiThread(new Runnable() {

                            @Override
                            public void run() {

                                recordingTime.setText(timeString);

                                /*
                                 * Update recordingTime TextView visibility and mRecordBtn's check state
                                 */
                                if (isVideoRecording){
                                    recordingTime.setVisibility(View.VISIBLE);
                                }else
                                {
                                    recordingTime.setVisibility(View.INVISIBLE);
                                }
                            }
                        });
                    }
                }
            });

            CommonCallbacks.CompletionCallbackWith<ResolutionAndFrameRate> resolutionAndFrameRateCompletionCallbackWith = new CommonCallbacks.CompletionCallbackWith<ResolutionAndFrameRate>(){
                @Override
                public void onSuccess(ResolutionAndFrameRate resolutionAndFrameRate) {

                    videoResolution=resolutionAndFrameRate.getResolution();
                    showToast(videoResolution.toString());



                }

                @Override
                public void onFailure(DJIError djiError) {

                }



            };
            camera.getVideoResolutionAndFrameRate(resolutionAndFrameRateCompletionCallbackWith);

        }

        Gimbal gimbal= FPVDemoApplication.getProductInstance().getGimbal();
        if (gimbal != null) {
            gimbal.setStateCallback(new GimbalState.Callback() {
                  @Override
                  public void onUpdate(@NonNull GimbalState gimbalState) {
                        mGimbalAttitude=gimbalState.getAttitudeInDegrees();
                  }
            });
        }






        mPowerManager=(PowerManager)getSystemService(Context.POWER_SERVICE);
        mWakeLock=mPowerManager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK,getString(R.string.mWakeLock_created));

        mWakeLock.acquire();


        mStringBuffer = new StringBuffer();
        mStringBufferPower = new StringBuffer();
        mStringBufferExp = new StringBuffer();
        outMessage=new StringBuffer();
        mGimbalAttitude=new Attitude(0,0,0);


        screenSize();


        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);

        params.height = screenHeight;
        params.width =params.height/512*640;
        params.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);

        //
        StringBuffer toastString=new StringBuffer();
        toastString.append("Original height:").append(mVideoSurface.getHeight())
                .append("Original width:").append(mVideoSurface.getWidth());

        showToast(toastString.toString());

        toastString=new StringBuffer();
        toastString.append("new height:").append(params.height)
                .append("new width:").append(params.width);

        showToast(toastString.toString());

        mVideoSurface.setLayoutParams(params);

    }

    protected void onProductChange() {
        initPreviewer();
        initStatusPreviewer();
        loginAccount();
    }

    private void loginAccount(){

        UserAccountManager.getInstance().logIntoDJIUserAccount(this,
                new CommonCallbacks.CompletionCallbackWith<UserAccountState>() {
                    @Override
                    public void onSuccess(final UserAccountState userAccountState) {
                        Log.e(TAG, "Login Success");
                    }
                    @Override
                    public void onFailure(DJIError error) {
                        showToast("Login Error:"
                                + error.getDescription());
                    }
                });
    }

    @Override
    public void onResume() {
        Log.e(TAG, "onResume");
        super.onResume();
        initPreviewer();
        onProductChange();
        mWakeLock.acquire();
        if(mVideoSurface == null) {
            Log.e(TAG, "mVideoSurface is null");
        }
    }

    @Override
    public void onPause() {
        Log.e(TAG, "onPause");
        uninitPreviewer();
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.e(TAG, "onStop");
        super.onStop();
    }

    public void onReturn(View view){
        Log.e(TAG, "onReturn");
        this.finish();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        uninitPreviewer();
        super.onDestroy();
    }

    private void initUI() {
        // init mVideoSurface
        mVideoSurface = (TextureView)findViewById(R.id.video_previewer_surface);

        recordingTime = (TextView) findViewById(R.id.timer);
        mCaptureBtn = (Button) findViewById(R.id.btn_capture);
        mRecordBtn = (ToggleButton) findViewById(R.id.btn_record);
        mShootPhotoModeBtn = (Button) findViewById(R.id.btn_shoot_photo_mode);
        mRecordVideoModeBtn = (Button) findViewById(R.id.btn_record_video_mode);

        if (null != mVideoSurface) {
            mVideoSurface.setSurfaceTextureListener(this);
        }

        mCaptureBtn.setOnClickListener(this);
        mRecordBtn.setOnClickListener(this);
        mShootPhotoModeBtn.setOnClickListener(this);
        mRecordVideoModeBtn.setOnClickListener(this);

        recordingTime.setVisibility(View.INVISIBLE);

        mRecordBtn.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startRecord();
                } else {
                    stopRecord();
                }
            }
        });

        mTextViewOSD = (TextView) findViewById(R.id.textView_osd);

        mTextViewPower=(TextView) findViewById(R.id.textView_power);
        mTextViewExp=(TextView) findViewById(R.id.textView_exp);

    }

    private void initPreviewer() {

        BaseProduct product = FPVDemoApplication.getProductInstance();

        if (product == null || !product.isConnected()) {
            showToast(getString(R.string.disconnected));
        } else {
            if (null != mVideoSurface) {
                mVideoSurface.setSurfaceTextureListener(this);
            }
            if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                Camera camera = product.getCamera();


                VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(mReceivedVideoDataListener);
                if (camera != null) {
                    camera.setExposureSettingsCallback(new ExposureSettings.Callback() {
                           @Override
                           public void onUpdate(@NonNull ExposureSettings values) {
                               mStringBufferExp.delete(0, mStringBufferExp.length());
                               mStringBufferExp
                                       .append(values.getExposureCompensation()).append("EV, ")
                                       .append(values.getAperture()).append(", ")
                                       .append(values.getShutterSpeed()).append("s, ")
                                       .append(values.getISO());
                               Message msg = new Message();
                               msg.what = EXPOSURE_STATUS_CHANGED;
                               handler.sendMessage(msg);
                           }


                       }
                    );
                }

                if(null!=product.getBattery()){
                    product.getBattery().setStateCallback(new BatteryState.Callback(){
                        @Override
                        public void onUpdate(BatteryState djiBatteryState) {
                            mStringBufferPower.delete(0, mStringBufferPower.length());
                            mStringBufferPower.append(djiBatteryState.getChargeRemainingInPercent())
                                    .append("%  ");
                            Message msg = new Message();
                            msg.what = BATTERY_STATUS_CHANGED;
                            handler.sendMessage(msg);
                        }
                    });

                }


            }


        }
    }
    private void initStatusPreviewer(){

        if (FPVDemoApplication.isFlightControllerAvailable()) {
            FPVDemoApplication.getAircraftInstance().getFlightController().setStateCallback(
                    new FlightControllerState.Callback() {


                        @Override
                        public void onUpdate(@NonNull FlightControllerState djiFlightControllerCurrentState) {
                            //DJIGimbal.DJIGimbalAttitude gimbalAtitude
                            //       =FPVDemoApplication.getProductInstance().getGimbal().getAttitudeInDegrees();

                            mStringBuffer.delete(0, mStringBuffer.length());
                            mStringBuffer.append("B_R: ").
                                    append(djiFlightControllerCurrentState.getAttitude().roll).append(",  ");
                            mStringBuffer.append("B_P: ").
                                    append(djiFlightControllerCurrentState.getAttitude().pitch).append(",  ");
                            mStringBuffer.append("B_Y: ").
                                    append(djiFlightControllerCurrentState.getAttitude().yaw).append(",  ");
                            mStringBuffer.append("Homeheight: ").
                                    append(djiFlightControllerCurrentState.getHomePointAltitude()).append("\n");


                            mStringBuffer.append("C_R: ").
                                    append(mGimbalAttitude.getRoll()).append(",  ");
                            mStringBuffer.append("C_P: ").
                                    append(mGimbalAttitude.getPitch()).append(",  ");
                            mStringBuffer.append("C_Y: ").
                                    append(mGimbalAttitude.getYaw()).append(",  ");
                            mStringBuffer.append("Altitude: ").
                                    append(djiFlightControllerCurrentState.getAircraftLocation().getAltitude()).append("\n");




                            mStringBuffer.append("Longitude: ").
                                    append(djiFlightControllerCurrentState.getAircraftLocation().getLongitude()).append(",  ");

                            mStringBuffer.append("Latitude: ").
                                    append(djiFlightControllerCurrentState.getAircraftLocation().getLatitude());


                            mStringBuffer.append(" ").append(videoResolution.toString());



                            handler.sendEmptyMessage(CHANGE_TEXT_VIEW);

                            if(isRecording){


                                try{
                                    outMessage.delete(0,outMessage.length());
                                    long elapsedTime=System.currentTimeMillis()-startTime;
                                    //time (ms)
                                    outMessage.append(elapsedTime).append(",");
                                    //Longitude
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getAircraftLocation().getLongitude())
                                            .append(",");

                                    //latitude
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getAircraftLocation().getLatitude())
                                            .append(",");

                                    //altitude
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getAircraftLocation().getAltitude())
                                            .append(",");

                                    //homeheight
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getHomePointAltitude())
                                            .append(",");

                                    //body roll,pitch,yaw
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getAttitude().roll)
                                            .append(",");
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getAttitude().pitch)
                                            .append(",");
                                    outMessage.
                                            append(djiFlightControllerCurrentState.getAttitude().yaw)
                                            .append(",");

                                    //camera roll,pitch,yaw
                                    /*
                                    outMessage.
                                            append(gimbalAtitude.roll).append(",");
                                    outMessage.
                                            append(gimbalAtitude.pitch).append(",");
                                    outMessage.
                                            append(gimbalAtitude.yaw).append("\n");
                                    */
                                    mPoseOutputStream.write(outMessage.toString().getBytes());






                                }
                                catch(Exception e){
                                    showToast(e.getMessage());
                                    e.printStackTrace();
                                }
                            }

                        }

                    }

            );

        }

    }


    private void uninitPreviewer() {
        Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null){
            // Reset the callback
            VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(null);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureAvailable");
        if (mCodecManager == null) {
            mCodecManager = new DJICodecManager(this, surface, width, height);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.e(TAG, "onSurfaceTextureSizeChanged");
        mCodecManager = new DJICodecManager(this, surface, width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        Log.e(TAG,"onSurfaceTextureDestroyed");
        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager = null;
        }

        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    public void showToast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onClick(View v) {

        switch (v.getId()) {
            case R.id.btn_capture:{
                captureAction();
                break;
            }
            case R.id.btn_shoot_photo_mode:{
                switchCameraMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO);
                break;
            }
            case R.id.btn_record_video_mode:{
                switchCameraMode(SettingsDefinitions.CameraMode.RECORD_VIDEO);
                break;
            }
            default:
                break;
        }
    }

    private void switchCameraMode(SettingsDefinitions.CameraMode cameraMode){

        Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            camera.setMode(cameraMode, new CommonCallbacks.CompletionCallback() {
                @Override
                public void onResult(DJIError error) {

                    if (error == null) {
                        showToast("Switch Camera Mode Succeeded");
                    } else {
                        showToast(error.getDescription());
                    }
                }
            });
        }
        

        /*



        //mVideoSurface.setLayoutParams(params);



        mReceivedVideoDataListener = new VideoFeeder.VideoDataListener() {

            @Override
            public void onReceive(byte[] videoBuffer, int size) {
                if (mCodecManager != null) {
                    mCodecManager.sendDataToDecoder(videoBuffer, size);
                }
            }
        };

        */



    }

    // Method for taking photo
    private void captureAction(){

        final Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {

            SettingsDefinitions.ShootPhotoMode photoMode = SettingsDefinitions.ShootPhotoMode.SINGLE; // Set the camera capture mode as Single mode
            camera.setShootPhotoMode(photoMode, new CommonCallbacks.CompletionCallback(){
                    @Override
                    public void onResult(DJIError djiError) {
                        if (null == djiError) {
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    camera.startShootPhoto(new CommonCallbacks.CompletionCallback() {
                                        @Override
                                        public void onResult(DJIError djiError) {
                                            if (djiError == null) {
                                                showToast("take photo: success");
                                            } else {
                                                showToast(djiError.getDescription());
                                            }
                                        }
                                    });
                                }
                            }, 2000);
                        }
                    }
            });
        }
    }

    // Method for starting recording
    private void startRecord(){

        final Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            camera.startRecordVideo(new CommonCallbacks.CompletionCallback(){
                @Override
                public void onResult(DJIError djiError)
                {
                    if (djiError == null) {
                        showToast("Record video: success");
                    }else {
                        showToast(djiError.getDescription());
                    }
                }
            }); // Execute the startRecordVideo API
        }
    }

    // Method for stopping recording
    private void stopRecord(){

        Camera camera = FPVDemoApplication.getCameraInstance();
        if (camera != null) {
            camera.stopRecordVideo(new CommonCallbacks.CompletionCallback(){

                @Override
                public void onResult(DJIError djiError)
                {
                    if(djiError == null) {
                        showToast("Stop recording: success");
                    }else {
                        showToast(djiError.getDescription());
                    }
                }
            }); // Execute the stopRecordVideo API
        }

    }

    private void screenSize(){
        DisplayMetrics dm;
        dm = getResources().getDisplayMetrics();

        float density  = dm.density;        // 屏幕密度（像素比例：0.75/1.0/1.5/2.0）
        int densityDPI = dm.densityDpi;     // 屏幕密度（每寸像素：120/160/240/320）
        float xdpi = dm.xdpi;
        float ydpi = dm.ydpi;

        Log.e(TAG + "  DisplayMetrics", "xdpi=" + xdpi + "; ydpi=" + ydpi);
        Log.e(TAG + "  DisplayMetrics", "density=" + density + "; densityDPI=" + densityDPI);

        screenWidth = dm.widthPixels;      // 屏幕宽（像素，如：480px）
        screenHeight = dm.heightPixels;     // 屏幕高（像素，如：800px）


        StringBuffer toastString=new StringBuffer();
        toastString.append("screen width:").append(screenWidth)
                .append(" screen height:").append(screenHeight)
                .append(" density:").append(density);
        showToast(toastString.toString());
    }




}
