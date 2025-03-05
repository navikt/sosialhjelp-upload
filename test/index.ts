import { Upload, type UploadOptions } from 'tus-js-client';
import { readFileSync } from 'fs';
import path from 'path';

const filePath = process.argv[2]; // Get file path from command line argument
if (!filePath) {
  console.error('Usage: bun upload.js <file-path>');
  process.exit(1);
}

console.log(`Uploading file ${filePath}...`)

const fileData = readFileSync(filePath);
const fileName = path.basename(filePath);

const uploadOptions: UploadOptions = {
  endpoint: 'http://localhost:8084/files/',
  retryDelays: [0, 1000, 3000, 5000],
  metadata: { filename: fileName },
  uploadSize: fileData.length,
  onError: (error: any) => console.error('Upload failed:', error),
  onProgress: (bytesUploaded, bytesTotal) => {
    const percentage = ((bytesUploaded / bytesTotal) * 100).toFixed(2);
    console.log(`Uploading: ${percentage}%`);
  },
  onSuccess: () => console.log('Upload complete!'),
};

const tusUpload = new Upload(fileData, uploadOptions);
tusUpload.start();
