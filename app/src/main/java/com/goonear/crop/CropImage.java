package com.goonear.crop;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.FaceDetector;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import com.print.demo.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CountDownLatch;

import utils.ApplicationContext;

public class CropImage extends MonitoredActivity {
    private static final String TAG = "CropImage";

    // These are various options can be specified in the intent.
    private Bitmap.CompressFormat mOutputFormat =
            Bitmap.CompressFormat.JPEG; // only used with mSaveUri
    private Uri mSaveUri = null;
    private int mAspectX, mAspectY;
    private boolean mDoFaceDetection = true;
    private boolean mCircleCrop = false;
    private final Handler mHandler = new Handler();
    private ApplicationContext context;
    boolean mWaitingToPick; // Whether we are wait the user to pick a face.
    boolean mSaving;  // Whether the "save" button is already clicked.
    private CropImageView mImageView;
    private Bitmap mBitmap;
    HighlightView mCrop;
    private IImageList mAllImages;
    private IImage mImage;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.cropimage);
        context = (ApplicationContext) getApplicationContext();
        mImageView = (CropImageView) findViewById(R.id.image);
        byte[] bis = getIntent().getByteArrayExtra("bitmap");
        mBitmap = BitmapFactory.decodeByteArray(bis, 0, bis.length);
        mAspectX = 1;
        mAspectY = 1;

        // 获取Jpeg图片，并保存在sd卡上
        // 用日期作为文件名
        Date date = new Date();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        String mPicName = formatter.format(date);
        File file = getFiles(mPicName + ".jpg");

        mSaveUri = Uri.fromFile(file);
        if (mSaveUri != null) {
            String outputFormatString = Bitmap.CompressFormat.JPEG.toString();
            if (outputFormatString != null) {
                mOutputFormat = Bitmap.CompressFormat.valueOf(
                        outputFormatString);
            }
        }

        if (mBitmap == null) {
            finish();
            return;
        }

        // Make UI fullscreen.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        findViewById(R.id.discard).setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        setResult(RESULT_CANCELED);
                        finish();
                    }
                });

        findViewById(R.id.save).setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        onSaveClicked();
                    }
                });

        startFaceDetection();
    }

    private void startFaceDetection() {
        if (isFinishing()) {
            return;
        }

        mImageView.setImageBitmapResetBase(mBitmap, true);
        mImageView.center(true, true);
        mImageView.mHighlightViews.clear();

        Util.startBackgroundJob(this, null,
                getResources().getString(R.string.runningFaceDetection),
                new Runnable() {
                    public void run() {
                        final CountDownLatch latch = new CountDownLatch(1);
                        final Bitmap b = (mImage != null)
                                ? mImage.fullSizeBitmap(IImage.UNCONSTRAINED,
                                1024 * 1024)
                                : mBitmap;
                        mHandler.post(new Runnable() {
                            public void run() {
                                if (b != mBitmap && b != null) {
                                    mImageView.setImageBitmapResetBase(b, true);
                                    mBitmap.recycle();
                                    mBitmap = b;
                                }
                                if (mImageView.getScale() == 1F) {
                                    mImageView.center(true, true);
                                }
                                latch.countDown();
                            }
                        });
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        mRunFaceDetection.run();
                    }
                }, mHandler);
    }

    private void onSaveClicked() {
        if (mCrop == null) {
            return;
        }
        if (mSaving) return;
        mSaving = true;
        Bitmap croppedImage;
        int width = 0;
        int height = 0;
        Rect r = mCrop.getCropRect();
        width = r.width();
        height = r.height();
        croppedImage = Bitmap.createBitmap(width, height,
                mCircleCrop
                        ? Bitmap.Config.ARGB_8888
                        : Bitmap.Config.RGB_565);

        Canvas canvas = new Canvas(croppedImage);
        Rect dstRect = new Rect(0, 0, width, height);
        canvas.drawBitmap(mBitmap, r, dstRect, null);
        // Release bitmap memory as soon as possible
        mImageView.clear();
        mBitmap.recycle();

        //打印图片
        context.getObject().
                CON_PageStart(context.getState(), true, width, height);
        context.getObject().
                ASCII_CtrlReset(context.getState());
        context.getObject().
                DRAW_SetFillMode(false);
        context.getObject().
                DRAW_SetLineWidth(4);
        context.getObject().
                DRAW_PrintPicture(context.getState(), croppedImage, 0, 0, 0, 0);
        context.getObject().
                CON_PageEnd(context.getState(), context.getPrintway());
        context.getObject().ASCII_PrintBuffer(context.getState(), new byte[]{0x1B, 0x66, 1,
                (byte) 2}, 4);
        CropImage.this.finish();
    }


    private File getFiles(String fileName) {
        File file = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName);
        if (file != null) {
            return file;
        }
        return null;
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (mAllImages != null) {
            mAllImages.close();
        }
        super.onDestroy();
    }

    Runnable mRunFaceDetection = new Runnable() {
        @SuppressWarnings("hiding")
        float mScale = 1F;
        Matrix mImageMatrix;
        FaceDetector.Face[] mFaces = new FaceDetector.Face[3];
        int mNumFaces;

        // For each face, we create a HightlightView for it.
        private void handleFace(FaceDetector.Face f) {
            PointF midPoint = new PointF();

            int r = ((int) (f.eyesDistance() * mScale)) * 2;
            f.getMidPoint(midPoint);
            midPoint.x *= mScale;
            midPoint.y *= mScale;

            int midX = (int) midPoint.x;
            int midY = (int) midPoint.y;

            HighlightView hv = new HighlightView(mImageView);

            int width = mBitmap.getWidth();
            int height = mBitmap.getHeight();

            Rect imageRect = new Rect(0, 0, width, height);

            RectF faceRect = new RectF(midX, midY, midX, midY);
            faceRect.inset(-r, -r);
            if (faceRect.left < 0) {
                faceRect.inset(-faceRect.left, -faceRect.left);
            }

            if (faceRect.top < 0) {
                faceRect.inset(-faceRect.top, -faceRect.top);
            }

            if (faceRect.right > imageRect.right) {
                faceRect.inset(faceRect.right - imageRect.right,
                        faceRect.right - imageRect.right);
            }

            if (faceRect.bottom > imageRect.bottom) {
                faceRect.inset(faceRect.bottom - imageRect.bottom,
                        faceRect.bottom - imageRect.bottom);
            }

            hv.setup(mImageMatrix, imageRect, faceRect, mCircleCrop, false
                     /*mAspectX != 0 && mAspectY != 0*/);

            mImageView.add(hv);
        }

        // Create a default HightlightView if we found no face in the picture.
        private void makeDefault() {
            HighlightView hv = new HighlightView(mImageView);

            int width = mBitmap.getWidth();
            int height = mBitmap.getHeight();

            Rect imageRect = new Rect(0, 0, width, height);

            // make the default size about 4/5 of the width or height
            int cropWidth = Math.min(width, height) * 4 / 5;
            int cropHeight = cropWidth;
//            int cropWidth = width;
//            int cropHeight = height;

            if (mAspectX != 0 && mAspectY != 0) {
                if (mAspectX > mAspectY) {
                    // 自由缩放
                    cropHeight = cropWidth * mAspectY;// mAspectX;
                } else {
                    cropWidth = cropHeight * mAspectX;// mAspectY;
                }
            }

            int x = (width - cropWidth) / 2;
            int y = (height - cropHeight) / 2;

            RectF cropRect = new RectF(x, y, x + cropWidth, y + cropHeight);
            // 自由缩放
            hv.setup(mImageMatrix, imageRect, cropRect, mCircleCrop, false
                     /*mAspectX != 0 && mAspectY != 0*/);
            mImageView.add(hv);
        }

        // Scale the image down for faster face detection.
        private Bitmap prepareBitmap() {
            if (mBitmap == null) {
                return null;
            }

            // 256 pixels wide is enough.
            if (mBitmap.getWidth() > 256) {
                mScale = 256.0F / mBitmap.getWidth();
            }
            Matrix matrix = new Matrix();
            matrix.setScale(mScale, mScale);
            Bitmap faceBitmap = Bitmap.createBitmap(mBitmap, 0, 0, mBitmap
                    .getWidth(), mBitmap.getHeight(), matrix, true);
            return faceBitmap;
        }

        public void run() {
            mImageMatrix = mImageView.getImageMatrix();
            Bitmap faceBitmap = prepareBitmap();

            mScale = 1.0F / mScale;
            if (faceBitmap != null && mDoFaceDetection) {
                FaceDetector detector = new FaceDetector(faceBitmap.getWidth(),
                        faceBitmap.getHeight(), mFaces.length);
                mNumFaces = detector.findFaces(faceBitmap, mFaces);
            }

            if (faceBitmap != null && faceBitmap != mBitmap) {
                faceBitmap.recycle();
            }

            mHandler.post(new Runnable() {
                public void run() {
                    mWaitingToPick = mNumFaces > 1;
                    if (mNumFaces > 0) {
                        for (int i = 0; i < mNumFaces; i++) {
                            handleFace(mFaces[i]);
                        }
                    } else {
                        makeDefault();
                    }
                    mImageView.invalidate();
                    if (mImageView.mHighlightViews.size() == 1) {
                        mCrop = mImageView.mHighlightViews.get(0);
                        mCrop.setFocus(true);
                    }

                    if (mNumFaces > 1) {
                        Toast t = Toast.makeText(CropImage.this,
                                R.string.multiface_crop_help,
                                Toast.LENGTH_SHORT);
                        t.show();
                    }
                }
            });
        }
    };
}


