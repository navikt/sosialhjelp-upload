import { Upload, type UploadOptions } from 'tus-js-client';
import { EventSource } from "eventsource";
import { readFileSync } from 'fs';
import path from 'path';
import { randomUUID } from 'crypto';

const filePath = process.argv[2]; // Get file path from command line argument
if (!filePath) {
  console.error('Usage: bun upload.js <file-path>');
  process.exit(1);
}

console.log(`Uploading file ${filePath}...`)

const fileData = readFileSync(filePath);
const fileName = path.basename(filePath);
const vedleggType = "dummyType"
const soknadId = randomUUID()

const uploadOptions: UploadOptions = {
  endpoint: 'http://localhost:8084/files/',
  retryDelays: [0, 1000, 3000, 5000],
  chunkSize: 1000,
  metadata: {
    filename: fileName,
    soknadId,
    vedleggType,
  },
  uploadSize: fileData.length,
  onError: (error: any) => console.error('Upload failed:', error),
  onUploadUrlAvailable: () => { console.log(tusUpload.url!.split("/").at(-1)) },
  onProgress: (bytesUploaded, bytesTotal) => {
    const percentage = ((bytesUploaded / bytesTotal) * 100).toFixed(2);
    console.log(`Uploading: ${percentage}%`);
  },
  onSuccess: () => console.log('Upload complete!'),
};

const url = `http://localhost:3007/sosialhjelp/upload/status/${soknadId}/${vedleggType}`;

async function connectSSE() {
  const eventSource = new EventSource(url);

  eventSource.onmessage = (event) => {
    console.log("Received event:", JSON.parse(event.data));
  };

  eventSource.onerror = (error) => {
    console.error("SSE Error:", error);
    eventSource.close();
  };
}

connectSSE();


const tusUpload = new Upload(fileData, uploadOptions);
tusUpload.start();
