import { randomUUID } from "crypto";
import { EventSource } from "eventsource";

const uuid = randomUUID()
const url = `http://localhost:3007/sosialhjelp/upload/status/${uuid}`;

async function connectSSE() {
  const eventSource = new EventSource(url);

  eventSource.onmessage = (event) => {
    console.log("Received event:", event.data);
  };

  eventSource.onerror = (error) => {
    console.error("SSE Error:", error);
    eventSource.close();
  };
}

connectSSE();

