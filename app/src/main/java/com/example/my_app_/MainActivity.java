package com.example.my_app_;

import androidx.annotation.NonNull;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Size;
import android.view.OrientationEventListener;
import android.widget.TextView;
import android.content.res.Configuration;
import android.graphics.Bitmap;

import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.AspectRatio;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.vision.classifier.Classifications;


public class MainActivity extends AppCompatActivity implements ImageClassifierHelper.ClassifierListener  {
    private ImageClassifierHelper imageClassifierHelper;
    private ImageAnalysis imageAnalyzer;
    private static final String[] CAMERA_PERMISSION = new String[]{"android.permission.CAMERA"};
    private static final int CAMERA_REQUEST_CODE = 10;
    private PreviewView previewView;
    private TextView textView;
    private Bitmap bitmapBuffer;
    private final Object task =new Object();
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;

    private List<Category> categories = new ArrayList<>();
    private int adapterSize = 0;
    @Override
    public void onDestroy() {
        super.onDestroy();

        // Shut down our background executor
        cameraExecutor.shutdown();
        synchronized (task) {
            imageClassifierHelper.clearImageClassifier();
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (!hasCameraPermission()){
            requestPermission();
        }
        imageClassifierHelper=ImageClassifierHelper.create(getApplicationContext(),this);
        cameraExecutor = Executors.newSingleThreadExecutor();
        previewView = findViewById(R.id.preview);
       //cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        textView = findViewById(R.id.textView);

        /*cameraProviderFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    bindImageAnalysis(cameraProvider);
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            }
        }, ContextCompat.getMainExecutor(this));*/
        previewView.post(this::setUpCamera);

    }

    private void bindImageAnalysis(@NonNull ProcessCameraProvider cameraProvider) {
        ImageAnalysis imageAnalysis =
                new ImageAnalysis.Builder().setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this), new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy image) {
                image.close();
            }
        });
        OrientationEventListener orientationEventListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                textView.setText(Integer.toString(orientation));
            }
        };
        orientationEventListener.enable();
        Preview preview = new Preview.Builder().build();
        CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        cameraProvider.bindToLifecycle((LifecycleOwner)this, cameraSelector,imageAnalysis, preview);
    }

    private void setUpCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                // Build and bind the camera use cases
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(getBaseContext()));
    }

    // Declare and bind preview, capture and analysis use cases
    private void bindCameraUseCases() {
        // CameraSelector - makes assumption that we're only using the back
        // camera
        CameraSelector.Builder cameraSelectorBuilder = new CameraSelector.Builder();
        CameraSelector cameraSelector = cameraSelectorBuilder.requireLensFacing(CameraSelector.LENS_FACING_BACK).build();

        // Preview. Only using the 4:3 ratio because this is the closest to
        // our model
        Preview preview = new Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(previewView.getDisplay().getRotation()).build();

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer = new ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(previewView.getDisplay().getRotation())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build();

        // The analyzer can then be assigned to the instance
        imageAnalyzer.setAnalyzer(cameraExecutor, image -> {
            if (bitmapBuffer == null) {
                bitmapBuffer = Bitmap.createBitmap(
                        image.getWidth(),
                        image.getHeight(),
                        Bitmap.Config.ARGB_8888);
            }
            classifyImage(image);
        });

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll();

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
            );

            // Attach the viewfinder's surface provider to preview use case
            preview.setSurfaceProvider(
                    previewView.getSurfaceProvider()
            );
        } catch (Exception exc) {
            Log.e("Main", "Use case binding failed", exc);
        }
    }

    private void classifyImage(@NonNull ImageProxy image) {
        // Copy out RGB bits to the shared bitmap buffer
        bitmapBuffer.copyPixelsFromBuffer(image.getPlanes()[0].getBuffer());

        int imageRotation = image.getImageInfo().getRotationDegrees();
        image.close();
        synchronized (task) {
            // Pass Bitmap and rotation to the image classifier helper for
            // processing and classification
            imageClassifierHelper.classify(bitmapBuffer, imageRotation);
        }
    }


    private void requestPermission() {
        ActivityCompat.requestPermissions(
                this,
                CAMERA_PERMISSION,
                CAMERA_REQUEST_CODE
        );
    }
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                "android.permission.CAMERA"
        ) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
            //classificationResultsAdapter.updateResults(new ArrayList<>());
        });

    }

    @Override
    public void onResults(List<Classifications> results, long inferenceTime) {

        runOnUiThread(() -> {
           /*float treshlod=results.get(0).getCategories().get(0).getScore();//set treshlod with the first value of the list classification
            int index=0;
            for(Category c:results.get(0).getCategories()){
            if(c.getScore()>treshlod){
                treshlod=c.getScore();
                index=c.getIndex();
            }
            }
            String output=results.get(0).getCategories().get(0).getLabel().toString();

            */
            //updateResults(results.get(0).getCategories());
            // after the sorting using the update methods ,now we can take the first element
            //textView.setText(categories.get(0).getLabel()+" with "+categories.get(0).getScore()+" %");
            if(!results.get(0).getCategories().isEmpty()){
                String label=results.get(0).getCategories().get(0).getLabel();
                float score=results.get(0).getCategories().get(0).getScore();
                textView.setText(label+" "+score+" %");
            }
            else{textView.setText(" ");
            }
            //textView.setText(results.get(0).getCategories().get(0).);
        });
    }
    @SuppressLint("NotifyDataSetChanged")
    public void updateResults(List<Category> categories) {
        List<Category> sortedCategories = new ArrayList<>(categories);
        Collections.sort(sortedCategories, new Comparator<Category>() {
            @Override
            public int compare(Category category1, Category category2) {
                return category1.getIndex() - category2.getIndex();
            }
        });
        this.categories = new ArrayList<>(Collections.nCopies(adapterSize, null));
        int min = Math.min(sortedCategories.size(), adapterSize);
        for (int i = 0; i < min; i++) {
            this.categories.set(i, sortedCategories.get(i));
        }
    }


}

