package com.example.camera2test;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.StrictMode;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Arrays;


public class MainActivity extends AppCompatActivity {


    public static final String LOG_TAG = "MainActivity";
    public static Surface surface;

    CameraService[] myCameras;

    private CameraManager mCameraManager;
    private final int CAMERA1 = 0;

    public static TextureView mImageView;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    private MediaCodec mCodec; // кодер
    private Surface mEncoderSurface; // Surface как вход данных для кодера
    private BufferedOutputStream outputStream;
    private ByteBuffer outPutByteBuffer;
    private DatagramSocket udpSocket;
    private String ip_address = "192.168.43.52";
    private InetAddress address;
    int port = 40002;

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

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

    private TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            setUpMediaCodec();
            myCameras[CAMERA1].openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

        }
    };


    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
//        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        setContentView(R.layout.activity_main);

        Log.d(LOG_TAG, "Запрашиваем разрешение");
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                ||
                (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
        ) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }

        try {
            udpSocket = new DatagramSocket();
            Log.i(LOG_TAG, "Socket created");
        } catch (SocketException e) {
            Log.i(LOG_TAG, "Cannot create UDP socket " + e.toString());
        }

        try {
            address = InetAddress.getByName(ip_address);
        } catch (Exception ignored) {

        }

        mCameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            myCameras = new CameraService[mCameraManager.getCameraIdList().length];

            for (String cameraID : mCameraManager.getCameraIdList()) {
                Log.i(LOG_TAG, "cameraID: " + cameraID);
                int id = Integer.parseInt(cameraID);
                myCameras[id] = new CameraService(mCameraManager, cameraID);
            }
        } catch (CameraAccessException e) {
            Log.e(LOG_TAG, e.getMessage());
            e.printStackTrace();
        }

        mImageView = findViewById(R.id.texture_view);
        mImageView.setSurfaceTextureListener(mSurfaceTextureListener);
    }


    public class CameraService {

        private String mCameraID;
        private CameraDevice mCameraDevice = null;
        private CameraCaptureSession mSession;
        private CaptureRequest.Builder mPreviewBuilder;


        public CameraService(CameraManager cameraManager, String cameraID) {
            mCameraManager = cameraManager;
            mCameraID = cameraID;
        }

        private CameraDevice.StateCallback mCameraCallback = new CameraDevice.StateCallback() {

            @Override
            public void onOpened(CameraDevice camera) {
                mCameraDevice = camera;
                Log.i(LOG_TAG, "Open camera  with id:" + mCameraDevice.getId());

                startCameraPreviewSession();
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                mCameraDevice.close();

                Log.i(LOG_TAG, "disconnect camera  with id:" + mCameraDevice.getId());
                mCameraDevice = null;
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                Log.i(LOG_TAG, "error! camera id: " + camera.getId() + " error: " + error);
            }
        };

        private void startCameraPreviewSession() {
            SurfaceTexture texture = mImageView.getSurfaceTexture();
            texture.setDefaultBufferSize(1280, 720);
            surface = new Surface(texture);


            try {

                mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                mPreviewBuilder.addTarget(surface);
                mPreviewBuilder.addTarget(mEncoderSurface);


                mCameraDevice.createCaptureSession(Arrays.asList(surface, mEncoderSurface),
                        new CameraCaptureSession.StateCallback() {

                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                mSession = session;

                                try {
                                    mSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackgroundHandler);
                                } catch (CameraAccessException e) {
                                    e.printStackTrace();
                                }
                            }

                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                            }
                        }, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

        }

        public boolean isOpen() {
            return mCameraDevice != null;
        }

        public void openCamera() {
            try {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {

                    mCameraManager.openCamera(mCameraID, mCameraCallback, mBackgroundHandler);

                }

            } catch (CameraAccessException e) {
                Log.i(LOG_TAG, e.getMessage());

            }
        }

        public void closeCamera() {
            stopStreamingVideo();
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }

        public void stopStreamingVideo() {

            if (mCameraDevice != null & mCodec != null) {

                try {
                    mSession.stopRepeating();
                    mSession.abortCaptures();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }

//                mCodec.stop();
                mCodec.release();
                mEncoderSurface.release();
                closeCamera();
            }
        }
    }


    private void setUpMediaCodec() {
        try {
            mCodec = MediaCodec.createEncoderByType("video/avc"); // H264 кодек

        } catch (Exception e) {
            Log.i(LOG_TAG, "Cannot create codec");
        }

        int width = 1280; // ширина видео
        int height = 720; // высота видео
        int colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface; // формат ввода цвета
        int videoBitrate = 5_000_000; // битрейт видео в bps (бит в секунду)
        int videoFramePerSecond = 20; // FPS
        int iframeInterval = 3; // I-Frame интервал в секундах

        MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFramePerSecond);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeInterval);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,1024);



        mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); // конфигурируем кодек как кодер
        mEncoderSurface = mCodec.createInputSurface(); // получаем Surface кодера

        mCodec.setCallback(new EncoderCallback());
        mCodec.start(); // запускаем кодер
        Log.i(LOG_TAG, "Codec has been started");

    }

    private class EncoderCallback extends MediaCodec.Callback {

        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {

        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            Log.d(LOG_TAG, Integer.toString(info.size));
            outPutByteBuffer = mCodec.getOutputBuffer(index);
            byte[] outData = new byte[info.size];
            outPutByteBuffer.get(outData);

            int count = 0;
            int temp = outData.length ;

            do {
                byte[] ds;
                temp -= 1024;

                if (temp >= 0) {
                    ds = new byte[1024];
                }
                else {
                    ds = new byte[temp + 1024];
                }

                for (int i = 0; i < ds.length; i++) {
                    ds[i]=outData[i + 1024 * count];
                }

                count=count+1;

                try {
                    DatagramPacket packet = new DatagramPacket(ds, ds.length, address, port);
                    udpSocket.send(packet);
                } catch (IOException e) {
                    Log.i(LOG_TAG, e.toString());
                }
                mCodec.releaseOutputBuffer(index, false);
            }
            while (temp >= 0);
            
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.i(LOG_TAG, "Error: " + e);
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            Log.i(LOG_TAG, "Encoder output format changed: " + format);
        }
    }

    @Override
    public void onPause() {
        if (myCameras[CAMERA1].isOpen()) {
            myCameras[CAMERA1].closeCamera();
        }
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();
    }
}