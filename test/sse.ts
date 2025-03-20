import { EventSource } from "eventsource";

const uuid = "0865e370-3c58-429d-95d4-51aa96971e72"
const url = `http://localhost:3007/sosialhjelp/upload/status/${uuid}/annet`;

console.log(`Connecting to ${url}...`)
const eventSource = new EventSource(url);

eventSource.onmessage = (event: MessageEvent) => {
  console.log("Received event:", event.data);
};

eventSource.onerror = (error) => {
  console.error("SSE Error:", error);
  eventSource.close();
};

eventSource.onopen = () => {
  console.info("opened")
}

await Bun.sleep(30000)
