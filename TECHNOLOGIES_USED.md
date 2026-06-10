# Technologies & Services Used

This document outlines all the major technologies, APIs, and services utilized in the Attendance Scanner project.

## 📱 Frontend (Android App)
- **Java**: Primary programming language for the Android application.
- **Android SDK**: Core framework for building the mobile interface and logic.
- **Retrofit & OkHttp**: Used for handling network HTTP requests and API communication between the Android app and the backend.
- **Gson**: Used for JSON data serialization and deserialization.

## ⚙️ Backend (Python Server)
- **Python**: Primary programming language for the backend infrastructure.
- **Flask**: Lightweight web framework used to build the API endpoints.
- **Waitress**: Production-quality WSGI server used to serve the Flask application.
- **OpenCV (`opencv-python`) & Scikit-Image**: Used for image preprocessing, manipulation, and computer vision tasks (like cleaning up the scanned documents).
- **NumPy & Pandas**: Used for data manipulation, array operations, and formatting attendance data into structured formats.
- **PyTesseract & EasyOCR**: Optical Character Recognition (OCR) engines used to extract raw text from the scanned images.
- **Pillow (PIL)**: Used for general image handling and saving.

## ☁️ Cloud, APIs & Services
- **Hugging Face Spaces**: Cloud hosting platform used to deploy and run the Python backend continuously.
- **Google Gemini API**: Used for advanced AI capabilities (e.g., parsing, structuring, or analyzing the extracted text intelligently).
- **Docker**: Used to containerize the backend environment, ensuring it runs consistently when deployed to Hugging Face.
- **GitHub**: Used for version control, source code hosting, and distributing the final APK release.
