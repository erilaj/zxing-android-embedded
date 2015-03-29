package com.journeyapps.barcodescanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.android.FinishListener;
import com.google.zxing.client.android.R;

/**
 *
 */
public class BarcodeView extends ViewGroup {
  public static enum DecodeMode {
    NONE,
    SINGLE,
    CONTINUOUS
  };

  private static final String TAG = BarcodeView.class.getSimpleName();

  private CameraThread.CameraInstance cameraInstance;
  private boolean hasSurface;
  private Activity activity;
  private DecoderThread decoderThread;

  private Handler resultHandler;

  private Decoder decoder;

  private DecodeMode decodeMode = DecodeMode.NONE;
  private BarcodeCallback callback = null;

  private SurfaceView surfaceView;

  private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
      if (holder == null) {
        Log.e(TAG, "*** WARNING *** surfaceCreated() gave us a null surface!");
      }
      if (!hasSurface) {
        hasSurface = true;
        initCamera(holder);
      }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
      hasSurface = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }
  };

  public BarcodeView(Context context) {
    super(context);
    initialize();
  }

  public BarcodeView(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public BarcodeView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  /**
   * Call from UI thread only.
   *
   * The decoder's decode method will only be called from a dedicated DecoderThread.
   *
   * @param decoder the decoder used to decode barcodes.
   */
  public void setDecoder(Decoder decoder) {
    this.decoder = decoder;
    if(this.decoderThread != null) {
      this.decoderThread.setDecoder(decoder);
    }
  }

  public Decoder getDecoder() {
    return decoder;
  }

  public void decodeSingle(BarcodeCallback callback) {
    this.decodeMode = DecodeMode.SINGLE;
    this.callback = callback;
  }

  public void decodeContinuous(BarcodeCallback callback) {
    this.decodeMode = DecodeMode.CONTINUOUS;
    this.callback = callback;
  }

  public void stopDecoding() {
    this.decodeMode = DecodeMode.NONE;
    this.callback = null;
    // TODO: stop the actual decoding process
  }

  private final Handler.Callback resultCallback = new Handler.Callback() {
    @Override
    public boolean handleMessage(Message message) {
      if(message.what == R.id.zxing_decode_succeeded) {
        Result result = (Result) message.obj;

        if(result != null) {
          if(callback != null && decodeMode != DecodeMode.NONE) {
            callback.barcodeResult(result);
            if(decodeMode == DecodeMode.SINGLE) {
              stopDecoding();
            }
          }
        }
        Log.d(TAG, "Decode succeeded");
      } else if(message.what == R.id.zxing_decode_failed) {
        // Failed. Next preview is automatically tried.
      } else if(message.what == R.id.zxing_prewiew_ready) {
        Log.d(TAG, "Preview Ready");
        requestLayout();
      }
      return false;
    }
  };

  private void initialize() {
    activity = (Activity) getContext();

    resultHandler = new Handler(resultCallback);

    decoder = new Decoder(new MultiFormatReader());

    surfaceView = new SurfaceView(getContext());
    addView(surfaceView);
  }

  private Point getPreviewSize() {
    if(cameraInstance == null) {
      return null;
    } else {
      return cameraInstance.getCameraManager().getPreviewSize();
    }
  }

  private boolean layoutWithPreview = false;

  private boolean center = false;

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    if ((changed || !layoutWithPreview) && getChildCount() > 0) {
      final View child = surfaceView;
      final int width = r - l;
      final int height = b - t;

      int previewWidth = width;
      int previewHeight = height;
      Point previewSize = getPreviewSize();
      if (previewSize != null) {
        previewWidth = previewSize.x;
        previewHeight = previewSize.y;
        layoutWithPreview = true;
      }

      // Center the child SurfaceView within the parent.
      if (center ^ (width * previewHeight < height * previewWidth)) {
        final int scaledChildWidth = previewWidth * height / previewHeight;
        child.layout((width - scaledChildWidth) / 2, 0,
                (width + scaledChildWidth) / 2, height);
      } else {
        final int scaledChildHeight = previewHeight * width / previewWidth;
        child.layout(0, (height - scaledChildHeight) / 2,
                width, (height + scaledChildHeight) / 2);
      }
    }

  }

  /**
   * Call from UI thread only.
   */
  public void resume() {
    Util.validateMainThread();

    SurfaceHolder surfaceHolder = surfaceView.getHolder();
    if (hasSurface) {
      // The activity was paused but not stopped, so the surface still exists. Therefore
      // surfaceCreated() won't be called, so init the camera here.
      initCamera(surfaceHolder);
    } else {
      // Install the callback and wait for surfaceCreated() to init the camera.
      surfaceHolder.addCallback(surfaceCallback);
    }
  }


  /**
   * Call from UI thread only.
   */
  public void pause() {
    Util.validateMainThread();

    if(decoderThread != null) {
      decoderThread.stop();
      decoderThread = null;
    }
    if(cameraInstance != null) {
      cameraInstance.close();
      cameraInstance = null;
    }
    if (!hasSurface) {
      SurfaceHolder surfaceHolder = surfaceView.getHolder();
      surfaceHolder.removeCallback(surfaceCallback);
    }
  }

  private void initCamera(SurfaceHolder surfaceHolder) {
    if (surfaceHolder == null) {
      throw new IllegalStateException("No SurfaceHolder provided");
    }

    if(cameraInstance != null || decoderThread != null) {
      Log.w(TAG, "initCamera called twice");
      return;
    }

    cameraInstance = CameraThread.getInstance().open(getContext(), surfaceHolder);
    cameraInstance.setReadyHandler(resultHandler);

    decoderThread = new DecoderThread(cameraInstance, decoder, resultHandler);
    decoderThread.start();
  }

  private void displayFrameworkBugMessageAndExit() {
    AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
    builder.setTitle(getContext().getString(R.string.zxing_app_name));
    builder.setMessage(getContext().getString(R.string.zxing_msg_camera_framework_bug));
    builder.setPositiveButton(R.string.zxing_button_ok, new FinishListener(activity));
    builder.setOnCancelListener(new FinishListener(activity));
    builder.show();
  }
}