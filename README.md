# visual-translator-app
# Visual Translator

An Android application that uses computer vision and optical character recognition (OCR) to detect and recognize text from images, then provide translations of the extracted text.

The project combines a custom-trained OCR model with an Android application developed in Kotlin using Android Studio. The application uses the device camera to capture text and processes the image through a computer vision pipeline before displaying the recognized text and its translation.

## Features

* Capture text using the device camera
* Detect text regions within images
* Preprocess images to improve OCR performance
* Recognize text using a custom-trained OCR model
* Translate recognized text
* Display OCR and translation results within the Android application
* Process images directly on an Android device

## System Architecture

The application follows the following processing pipeline:

```text
Camera Input
     ↓
Image Capture
     ↓
Image Preprocessing
     ↓
Text Detection
     ↓
OCR Model
     ↓
Recognized Text
     ↓
Translation
     ↓
Translated Output
```

## Technologies Used

### Android Application

* Kotlin
* Android Studio
* Android SDK
* CameraX

### Computer Vision

* OpenCV
* Image preprocessing techniques
* EAST text detection model

### Machine Learning

* Python
* Custom-trained OCR model
* Model training and evaluation pipeline

## OCR Model

The OCR component was developed and trained to recognize text from synthetic generated text images. The model forms the core text-recognition component of the application.

The OCR pipeline consists of:

1. Input image acquisition
2. Image preprocessing
3. Text region detection
4. Extraction of text regions
5. OCR model inference
6. Text recognition

The trained model is integrated into the Android application to allow text recognition directly from captured images.

## Image Processing Pipeline

Before OCR inference, captured images can be processed to improve the quality of the input. The preprocessing pipeline may include operations such as:

* Grayscale conversion
* Noise reduction
* Gaussian blurring
* Adaptive thresholding
* Morphological operations

These techniques help improve the visibility and consistency of text before it is passed to the OCR model.

## Android Application

The Android application provides the user interface for the complete text-recognition and translation workflow.

The application allows the user to:

1. Capture an image using the device camera.
2. Detect text within the captured image.
3. Process the detected text regions.
4. Recognize the text using the OCR model.
5. Translate the recognized text.
6. Display the final translated result.

## Installation

### Prerequisites

* Android Studio
* Android SDK
* A physical Android device or Android emulator
* Python environment for training or modifying the OCR model

### Running the Android Application

1. Clone the repository:

```bash
git clone https://github.com/6lxck-Schofield/visual-translator-app
```
2. Run the OCR.ipynb to train the OCR model 

3. Open the project in Android Studio.

4. Allow Android Studio to synchronize the Gradle dependencies.

5. add the prediction_model.tflite file to the project

6. Add your google translate api key to the local.properties file

7. Connect an Android device or start an emulator.

8. Build and run the application.

> Depending on the project configuration, additional model files or API credentials may be required.

## Project Structure

```text
Project/
├── app/
│   └── src/
│       └── main/
│           ├── java/
│           │   └── Application source code
│           │
│           ├── res/
│           │   └── Android resources and user interface components
│           │
│           └── assets/
│               └── OCR and text-detection model files
│
├── model/
│   └── OCR model training and evaluation files
│
└── README.md
```

## Model Training

The OCR model was trained using a dataset containing text samples suitable for the target recognition task.

The general training workflow consists of:

```text
Dataset
   ↓
Data Preparation
   ↓
Preprocessing
   ↓
Model Training
   ↓
Model Evaluation
   ↓
Model Export
   ↓
Android Integration
```

The trained model is then integrated into the Android application for inference.

## Results

The application successfully combines computer vision, OCR, and translation into a single Android-based workflow.

Model performance can be documented using metrics such as:

| Metric               | Result      |
| -------------------- | ----------- |
| OCR Accuracy         | To be added |
| Character Error Rate | To be added |
| Word Error Rate      | To be added |
| Inference Time       | To be added |

Performance may vary depending on:

* Image quality
* Lighting conditions
* Text size and font
* Text orientation
* Device hardware

## Limitations

* OCR accuracy may decrease when text is blurred or poorly illuminated.
* Complex backgrounds may make text detection more difficult.
* Unusual fonts and heavily distorted text may reduce recognition accuracy.
* Translation quality depends on the translation system used by the application.
* Processing performance may vary between Android devices.

## Future Improvements

Potential improvements include:

* Support for additional languages
* Improved recognition of handwritten text
* Improved recognition accuracy using a pre-existing training dataset
* Real-time text recognition through the camera preview
* Improved model optimization for mobile devices
* Offline translation support
* Improved handling of rotated and distorted text

## License

This project is intended for educational and development purposes.

## Author

**Olwami Ndaba**

Computer Engineering Student
