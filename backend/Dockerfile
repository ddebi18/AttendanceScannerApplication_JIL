# Use an official Python runtime as a parent image
FROM python:3.10-slim

# Set the working directory to /app
WORKDIR /app

# Install system dependencies
# - tesseract-ocr for PyTesseract
# - libgl1 and libglib2.0-0 for OpenCV
RUN apt-get update && apt-get install -y \
    tesseract-ocr \
    tesseract-ocr-eng \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Copy the requirements file into the container
COPY requirements.txt .

# Install Python dependencies
# We use --no-cache-dir to keep the image size small
RUN pip install --no-cache-dir -r requirements.txt

# Create a non-root user (Hugging Face Spaces requirement for security)
RUN useradd -m -u 1000 user
USER user
ENV HOME=/home/user \
    PATH=/home/user/.local/bin:$PATH

# Change working directory to the user's home directory
WORKDIR $HOME/app

# Copy the current directory contents into the container
# Make sure the user owns the files
COPY --chown=user . $HOME/app

# Hugging Face Spaces expose port 7860
ENV PORT=7860
EXPOSE 7860

# Run the app using waitress (which is in your requirements.txt)
CMD ["waitress-serve", "--port=7860", "app:app"]
