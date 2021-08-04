// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.codelab.mlkit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;

import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import com.pedro.library.AutoPermissions;
import com.pedro.library.AutoPermissionsListener;

import android.speech.tts.TextToSpeech;

@RequiresApi(api = Build.VERSION_CODES.O)
public class MainActivity extends AppCompatActivity implements AutoPermissionsListener {

    // Permissions
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AutoPermissions.Companion.parsePermissions( this, requestCode, permissions, this);
    }

    @Override
    public void onGranted(int i, String[] strings) {
        Toast.makeText(this, "permissions granted: " + strings.length, Toast.LENGTH_LONG).show();
    }
    @Override
    public void onDenied(int i, String[] strings) {
        Toast.makeText(this, "permissions denied: " + strings.length, Toast.LENGTH_LONG).show();
    }

    // Define camera display
    class CameraSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
        private SurfaceHolder mHolder;
        private Camera camera = null;

        public CameraSurfaceView (Context context) {
            super (context);
            mHolder = getHolder();
            mHolder.addCallback(this);
        }

        public void surfaceCreated (SurfaceHolder holder) {
            camera = Camera.open();
            camera.setDisplayOrientation(90);
            try{
                camera.setPreviewDisplay(mHolder);

            }catch (Exception e){
                e.printStackTrace();
            }
        }

        public void surfaceChanged (SurfaceHolder holder, int format, int width, int height) {
            camera.startPreview();
        }

        public void surfaceDestroyed (SurfaceHolder holder){
            camera.stopPreview();
            camera.release();
            camera = null;
        }

        public boolean capture (Camera.PictureCallback handler){
            if (camera != null) {
                camera.takePicture (null, null, handler);
                return true;
            } else {
                return false;
            }
        }
    }

    CameraSurfaceView cameraView;

    // Define the rest of the objects
    private static final String TAG = "MainActivity";
    private Button mTextButton;
    private Button mFaceButton;
    private GraphicOverlay cGraphicOverlay;
    private TextToSpeech text2speech;
    private Dictionary dict;
    private Timer timerObj;
    private Boolean loopFlag;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Add camera view
        FrameLayout previewFrame = findViewById(R.id.previewFrame);
        cameraView = new CameraSurfaceView( this);
        previewFrame.addView(cameraView);
        // Add buttons
        mTextButton = findViewById(R.id.button_text);
        mFaceButton = findViewById(R.id.button_face);
        // Add graphic overlay object to draw boxes
        cGraphicOverlay = findViewById(R.id.camera_graphic_overlay);
        // Add buttons and define listeners
        mTextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loop();
            }
        });
        mFaceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                snapshot();
            }
        });
        // Initialize text to speech
        text2speech=new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(status != TextToSpeech.ERROR) {
                    text2speech.setLanguage(Locale.UK);
                    text2speech.setSpeechRate((float) 1.5);
                }
            }
        });
        // Initialize english dictionary (load english words from text file)
        dict = new Dictionary("english_dictionary.txt");

        // Initialize Timer and schedule snapshot function to run every 2 seconds(2000ms)
        timerObj = new Timer();
        timerObj.schedule(timerTaskObj, 0, 2000);
        // Set loop flag to false at the start of the application
        loopFlag = false;
        AutoPermissions.Companion.loadAllPermissions(  this,  101);
    }

    // Toggle loop flag when left button is pressed
    private void loop() {
        loopFlag = !loopFlag;
        // Set button text accordingly
        if (loopFlag) { mTextButton.setText("STOP"); }
        else { mTextButton.setText("START"); }
    }

    // Snapshot function implements the main functionality of the app
    // It takes a photo with the camera, runs the text detector on it,
    // filters the detected words and feeds the valid ones
    // to the textToSpeech module to produce sound
    private void snapshot() {
        cameraView.capture(new Camera.PictureCallback() {
            public void onPictureTaken(byte[] data, Camera camera) {
                try{
                    // convert image into bitmap and store into memory
                    Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                    // Rotate bitmap because it has wrong orientation by default
                    Bitmap rotated_bitmap = RotateBitmap(bitmap, 90);
                    // Get camera view dimensions to resize camera photo to the same size
                    // This is done to draw the boxes correctly on the image
                    // but it also saves some time as we shrink the image
                    int targetWidth = cameraView.getWidth();
                    int maxHeight = cameraView.getHeight();
                    // Determine how much to scale down the image
                    float scaleFactor =
                            Math.max(
                                    (float) rotated_bitmap.getWidth() / (float) targetWidth,
                                    (float) rotated_bitmap.getHeight() / (float) maxHeight);
                    // After calculating the scale factor the bitmap is resized
                    Bitmap resizedBitmap =
                            Bitmap.createScaledBitmap(
                                    rotated_bitmap,
                                    (int) (rotated_bitmap.getWidth() / scaleFactor),
                                    (int) (rotated_bitmap.getHeight() / scaleFactor),
                                    true);
                    // Prepare image for text recognition (Bitmap to Image)
                    InputImage image = InputImage.fromBitmap(resizedBitmap, 0);
                    TextRecognizer recognizer = TextRecognition.getClient();
                    // Deactivate button temporarily
                    mTextButton.setEnabled(false);
                    // Process image
                    recognizer.process(image)
                            // If successful call another function to process the results
                            .addOnSuccessListener(
                                    new OnSuccessListener<Text>() {
                                        @Override
                                        public void onSuccess(Text texts) {
                                            // Reactivate button
                                            mTextButton.setEnabled(true);
                                            // Pass detected text to another function to filter
                                            // the results and speak valid words
                                            processTextRecognitionResult(texts, cGraphicOverlay);
                                        }
                                    })
                            // If failed print error
                            .addOnFailureListener(
                                    new OnFailureListener() {
                                        @Override
                                        public void onFailure(@NonNull Exception e) {
                                            // Task failed with an exception
                                            mTextButton.setEnabled(true);
                                            e.printStackTrace();
                                        }
                                    });
                    // Restart camera (?)
                    camera.startPreview();

                } catch (Exception e) {
                    e.printStackTrace();

                }
            }
        });
    }

    private void processTextRecognitionResult(Text texts, GraphicOverlay graphicOverlay) {
        // Check if the is any text detected
        List<Text.TextBlock> blocks = texts.getTextBlocks();
        // If not print toast message and exit
        if (blocks.size() == 0) {
            showToast("No text found");
            return;
        }
        // If text is found clean the red boxes from the previous image
        // and stop the speech generator
        graphicOverlay.clear();
        text2speech.stop();
        // Take each text block and brake it down to its lines
        for (int i = 0; i < blocks.size(); i++) {
            List<Text.Line> lines = blocks.get(i).getLines();
            // For every line get its words and put them into a list
            for (int j = 0; j < lines.size(); j++) {
                List<Text.Element> elements = lines.get(j).getElements();
                // For each word of the line
                for (int k = 0; k < elements.size(); k++) {
                    // Check if the word exists in the dictionary
                    if (dict.isWord(elements.get(k).getText()))
                    {
                        // If it is a valid word pass it to the speech generator
                        text2speech.speak(elements.get(k).getText(), TextToSpeech.QUEUE_ADD, null); // QUEUE_FLUSH
                    }
                    // Draw a box for every detected word, all of them
                    GraphicOverlay.Graphic textGraphic = new TextGraphic(graphicOverlay, elements.get(k));
                    graphicOverlay.add(textGraphic);
                }
            }
        }
    }

    // This class acts as an dictionary that checks if a word exists in its database
    // The words are loaded with a text file
    private class Dictionary
    {
        private static final String TAG = "Eng_dict";
        // List that contains all words
        List<String> words = new LinkedList<String>();

        // Constructor, takes filename as an argument
        public Dictionary(String filename)
        {
            // Read text file and load all words into the list
            BufferedReader reader = null;
            try {
                // Open file buffer
                reader = new BufferedReader(new InputStreamReader(getAssets().open(filename)));
                String line;
                // Read each line and add the corresponding word into the list
                while ((line = reader.readLine()) != null)
                {
                    if (line != null){ words.add(line); }
                }
                // Set every word in the list to lower case
                words.replaceAll(String::toLowerCase);

            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        // Not really useful
        public void setWords(List<String> words) {
            this.words = words;
        }

        // Check if a word is in the dictionary list
        public Boolean isWord(String word) {
            Log.d(TAG, "Word: " + word);
            // Set input word to lower case to mach the dictionary
            return words.contains(word.toLowerCase());
        }
    }

    // Simple function to rotate a bitmap
    private static Bitmap RotateBitmap(Bitmap source, float angle)
    {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    // Show toast text
    private void showToast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    // The task  runs every half-second
    TimerTask timerTaskObj = new TimerTask() {
        public void run() {
            // Run only when loop flag is true (toggled with the left button)
            if (loopFlag)
            {
                // Check if speech generator is still running and
                // says the words from the previous frame
                // If not call the snapshot function
                if (text2speech.isSpeaking() != true)
                {
                    snapshot();
                }
            }
        }
    };
}

