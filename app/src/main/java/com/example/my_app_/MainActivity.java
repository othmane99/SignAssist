package com.example.my_app_;


import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;

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
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.content.res.Configuration;
import android.graphics.Bitmap;

import android.util.Log;
import android.widget.Toast;
import androidx.camera.core.AspectRatio;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.vision.classifier.Classifications;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

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
    private FusedLocationProviderClient mFusedLocationClient;
    int PERMISSION_ID = 44;
    private Button translate,sos;
    private Spinner spino_lang;
    private String languagePair ;
    private String sos_msg;
    private String recognite_text="";


    private List<Category> categories = new ArrayList<>();

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
        spino_lang=findViewById(R.id.spino);
        translate=findViewById(R.id.button);
        sos=findViewById(R.id.button2);

        textView = findViewById(R.id.textView);
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        spino_lang.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int position, long id)
            {//the user desire language
                 /*<item>English</item>
                <item>French</item>
                <item>Arabic</item>
            <item>spnanish</item>*/
                switch (position) {
                    case 1:
                        //languagePair ="eng";
                        break;
                    case 2:
                        languagePair = "en-fr";
                        break;
                    case 3:
                        languagePair ="en-ar";
                        break;
                    case 4:
                        languagePair = "en-sp";
                        break;
                    default:
                        //languagePair = "eng";
                        }
                            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                // no-op
            }
        });
        translate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                TranslatorBackgroundTask translatorBackgroundTask= new TranslatorBackgroundTask(getBaseContext());
                AsyncTask<String, Void, String> translationResult = translatorBackgroundTask.execute(recognite_text,languagePair);
                textView.setText(translationResult.toString());
            }
        });
        sos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                getLastLocation();
                Toast.makeText(getBaseContext(), sos_msg,Toast.LENGTH_LONG).show();
                //todo sent sos_msg to a number using intent
            }
        });
        previewView.post(this::setUpCamera);

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
            if(!results.get(0).getCategories().isEmpty()){
                float treshlod=results.get(0).getCategories().get(0).getScore();
                int index=0;
                for(Category c:results.get(0).getCategories()){
                    if(c.getScore()>treshlod){
                        treshlod=c.getScore();
                        index=c.getIndex();
                    }
                }
                String label=results.get(0).getCategories().get(index).getLabel();
                float score=results.get(0).getCategories().get(index).getScore();
                //Log.d("Debug", "onResults: "+results.get(0).getCategories() );
                //textView.setText(label+" "+score+" %");
                   if(label.equals("nothing"))label="";
                    if(label.equals("space"))label=" ";
                if(label.equals("del"))recognite_text.substring(0, recognite_text.length() - 1);

                if(score>0.8)recognite_text=recognite_text+label;
                textView.setText(recognite_text);
            }
            else{
                //textView.setText(" ");
            }

        });
    }
    @SuppressLint("MissingPermission")
    private void getLastLocation() {
        // check if permissions are given
        if (checkPermissions()) {

            // check if location is enabled
            if (isLocationEnabled()) {

                mFusedLocationClient.getLastLocation().addOnCompleteListener(new OnCompleteListener<Location>() {
                    @Override
                    public void onComplete(@NonNull Task<Location> task) {
                        Location location = task.getResult();
                        if (location == null) {
                            requestNewLocationData();
                        } else {

                            sos_msg="j'ai besoin d'aide,je suis à Latitude "+location.getLatitude()+" Longitude "+location.getLongitude();


                        }
                    }
                });
            } else {
                Toast.makeText(this, "Please turn on" + " your location...", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                startActivity(intent);
            }
        } else {
            // if permissions aren't available,
            // request for permissions
            requestPermissions();
        }
    }

    @SuppressLint("MissingPermission")
    private void requestNewLocationData() {

        // Initializing LocationRequest
        // object with appropriate methods
        LocationRequest mLocationRequest = new LocationRequest();
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        mLocationRequest.setInterval(5);
        mLocationRequest.setFastestInterval(0);
        mLocationRequest.setNumUpdates(1);

        // setting LocationRequest
        // on FusedLocationClient
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        mFusedLocationClient.requestLocationUpdates(mLocationRequest, mLocationCallback, Looper.myLooper());
    }

    private LocationCallback mLocationCallback = new LocationCallback() {

        @Override
        public void onLocationResult(LocationResult locationResult) {
            Location mLastLocation = locationResult.getLastLocation();
            //latitudeTextView.setText("Latitude: " + mLastLocation.getLatitude() + "");
            //longitTextView.setText("Longitude: " + mLastLocation.getLongitude() + "");
        }
    };

    // method to check for permissions
    private boolean checkPermissions() {
        return ActivityCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // method to request for permissions
    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                ACCESS_COARSE_LOCATION,
                ACCESS_FINE_LOCATION}, PERMISSION_ID);
    }

    // method to check
    // if location is enabled
    private boolean isLocationEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    // If everything is alright then
    @Override
    public void
    onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_ID) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getLastLocation();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (checkPermissions()) {
            getLastLocation();
        }
    }



}

